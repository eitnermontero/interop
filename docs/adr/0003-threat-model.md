# ADR-0003 — Modelo de Amenazas: Flujos Inbound y Outbound

Estado: Propuesto · Fecha: 2026-06-22

> Complementa a ADR-0001 (decisiones de arquitectura del hub) y ADR-0002 (diseño
> concreto sobre la base HUB). Este ADR no redefine la arquitectura: la analiza
> bajo el lente de un atacante usando **STRIDE** y fija qué controles son
> obligatorios antes de producción.

---

## Alcance y supuestos

**En alcance.** Las dos direcciones de tráfico del hub y todo lo que las soporta:

- **Inbound**: tercero (partner M2M) → `hub-gateway` → `lb://hubbaseservice`
  (`hub-ms-base`) → lógica de negocio (p. ej. `qr.decode`) → Tuxedo API.
- **Outbound**: app interna → `hub-gateway` → `hub-ms-base` (framework ACL,
  Fase 5) → proveedor externo.
- Las capas transversales: mTLS + RFC 8705, las dos cadenas de seguridad del
  gateway, la cadena de auditoría (hash-chain + Vault Transit, Fases 0/1), el
  outbox de facturación (Fase 4), la PKI de Vault y los secretos de proveedor.

**Fuera de alcance.** Seguridad física de los hosts, hardening del SO,
endurecimiento de Keycloak/Consul/Vault como productos (se asume que su propia
configuración es correcta y que están en red no pública), el frontend Angular
(`apps/admin`, `apps/portal`) más allá de su uso de tokens, y la seguridad
interna de la Tuxedo API (Go) que es un sistema de terceros.

**Supuestos.**

1. El estado del código es el verificado en ADR-0002: el gateway **no termina
   mTLS hoy**, ms-base está en **MODO DESARROLLO** (`anyRequest().permitAll()`,
   JWT comentado en `hub-ms-base/.../config/SecurityConfiguration.java`), y la
   librería de auditoría **aún no tiene** `requestHash`/`responseHash`/`prevHash`/
   `recordHash`/`signature` (la costura `AuditEventSink`/`InProcessSink` existe
   pero `InProcessSink.toRequest` no propaga esos campos porque no existen).
2. En producción ms-base (`:8081`) y ms-auth (`:8083`) **no están expuestos
   directo**: el único ingreso público es el gateway (`:8080`). Es la mitigación
   compensatoria principal mientras dura el MODO DESARROLLO.
3. Keycloak emite el claim `cnf.x5t#S256` (holder-of-key) una vez habilitado el
   binding RFC 8705 en el client `unilink-api` (Fase 3).
4. Vault (PKI + Transit + KV) y Consul corren en red de confianza, no expuestos
   a Internet.
5. El "issuer JWT consistente" (`127.0.0.1:8180` / `external-url`) está
   correctamente configurado; un issuer mal configurado se trata como amenaza,
   no como supuesto.

**Convención de estado de mitigación.**

- **Mitigado por diseño** — ADR-0001/0002 lo contempla con detalle suficiente y/o
  el código actual ya lo implementa.
- **Mitigación pendiente** — el diseño lo menciona pero queda por implementar; se
  indica la fase del plan de ADR-0002.
- **Riesgo aceptado** — se conoce, se decide no mitigar (o solo compensar) y se
  documenta por qué.

---

## Actores y superficies de ataque

| Actor | Confianza | Capacidad de ataque |
|-------|-----------|---------------------|
| **Partner legítimo malicioso o comprometido** | Bajo | Tiene cert mTLS + client M2M válidos; intenta abusar de scope, IDOR de otro partner, replay, evasión de facturación. |
| **Atacante externo sin credenciales** | Nulo | Internet abierto contra `:8080`; busca bypass de auth, DoS, robo de token, downgrade mTLS. |
| **App interna comprometida** | Medio | Puede llamar rutas outbound; intenta SSRF a través del hub, abusar de credenciales de proveedor centralizadas. |
| **Insider / operador** | Alto | Acceso a DB, logs, Vault o red interna; intenta manipular auditoría/facturación o exfiltrar PII. |
| **Proveedor externo malicioso (outbound)** | Bajo | Responde al hub; intenta payloads maliciosos, deserialización, agotamiento de recursos. |
| **Atacante en red interna** | Medio | Si alcanza `:8081`/`:8083` directo, saltea el gateway (MODO DESARROLLO de ms-base). |

**Superficies de ataque principales.**

- S1 — Terminación TLS/mTLS en el edge (proxy delante o Netty del gateway).
- S2 — Endpoint público `POST /oauth2/token` (proxy a Keycloak partner).
- S3 — Rutas inbound `/partner/v1/**` y outbound `/outbound/v1/**`.
- S4 — Cadenas de seguridad y filtros globales del gateway (`IpWhitelistFilter`,
  `RateLimitFilter`, `DomainWhitelistFilter`).
- S5 — ms-base directo (`:8081`) y ms-auth directo (`:8083`) en red interna.
- S6 — Cadena de auditoría: tablas `audit_log`/`decode_log`, hash-chain, claves
  Vault Transit.
- S7 — Outbox de facturación (`outbox_event`) y relay de medición.
- S8 — Adaptadores outbound (ACL): URLs y credenciales de proveedor (Vault).
- S9 — Cadena de suministro: imágenes de contenedor (Jib), dependencias,
  secretos en config/código, PKI root CA.

---

## Flujos bajo análisis

```
INBOUND (tercero consume API de negocio)

  Partner M2M
     │  (1) POST /oauth2/token   [TLS, sin auth — público]
     ▼
 ┌──────────── hub-gateway :8080 ───────────────────────────────────────┐
 │  [S1] terminación mTLS (proxy delante en prod / Netty en dev) ── Fase 3│
 │        └─ valida cert cliente contra CA PKI Vault; CN/SAN = partner    │
 │  [S4] IpWhitelistFilter(Order=1)  → Redis whitelist:ip:{azp}           │
 │  [S4] RateLimitFilter(Order=3)    → Redis ratelimit:{azp} (sliding win)│
 │  partnerSecurityChain(Order=1): JWT realm hub-partner (issuer chk)    │
 │        └─ CertBindingFilter (cnf.x5t#S256 == thumbprint mTLS) ── Fase 3│
 │  RewritePath /partner/v1/** → /api/**                                  │
 └───────────────────────────────┬───────────────────────────────────────┘
                                  │ lb://hubbaseservice (Consul + LB)
                                  ▼
 ┌──────────── hub-ms-base :8081 ───────────────────────────────────────┐
 │  [S5] SecurityConfiguration: MODO DESARROLLO permitAll ── Fase 6 pend. │
 │  negocio: QrDecryptionService / CryptoService (RSA, BouncyCastle)      │
 │  [S6] audit-commons: canonicaliza → SHA-256 req/resp → hash-chain      │
 │        → firma Vault Transit (Fases 0/1)                               │
 │  [S7] misma TX: escribe audit_log/decode_log + outbox_event           │
 │  [S8] (si aplica) → TuxedoApiClient → Tuxedo API :5050                 │
 └────────────────────────────────────────────────────────────────────────┘

OUTBOUND (app interna consume tercero a través del hub)

  App interna
     │  (auth como client de servicio, realm hub-admin/scope servicio)
     ▼  /outbound/v1/**   [cadena interna del gateway]
 hub-gateway :8080 ──► lb://hubbaseservice ──► OutboundAdapter (ACL, Fase 5)
                                                   │ resilience4j (timeout/
                                                   │ retry/CB/bulkhead)
                                                   │ credenciales ← Vault KV
                                                   ▼
                                              Proveedor externo
                         (auditoría + outbox + medición igual que inbound)
```

Controles por salto: TLS/mTLS (S1) → IP/rate (S4) → JWT+RFC8705 (partnerChain) →
red interna (S5) → autorización de método en ms-base (Fase 6) → auditoría con
hash-chain (S6) → outbox transaccional (S7) → resiliencia + Vault en outbound
(S8).

---

## Amenazas por dominio

### 1. Capa de transporte y mTLS

**T1.1 — Acceso inbound sin mTLS (downgrade de transporte).** _[Spoofing /
Elevation of Privilege]_
- Superficie: S1.
- Vector: hoy el gateway **no termina mTLS** (ADR-0002: "No termina mTLS hoy").
  Un atacante con solo un token bearer (o que obtiene uno por el endpoint
  público) llega a `/partner/v1/**` sin presentar certificado de cliente. La
  capa de transporte de identidad simplemente no existe todavía.
- Impacto: C/I — se pierde la primera capa de identidad y el no-repudio de
  transporte; cualquiera con un token válido entra.
- Mitigación: terminar TLS mutuo en proxy delante (Envoy/NGINX recomendado en
  prod por rotación de CA/CRL sin reiniciar JVM) o en el Netty del gateway en
  dev; validar el cert de cliente contra la CA emisora de la PKI de Vault;
  mapear CN/SAN a un `partner` activo (tabla `partner` + `partner_certificate`,
  ms-auth). Propagar el cert/huella aguas abajo en header confiable
  (`X-Client-Cert` / `X-Client-Cert-Thumbprint`).
- Estado: **Mitigación pendiente (Fase 3)**.

**T1.2 — CA de la PKI comprometida o cert emitido fraudulentamente.**
_[Spoofing]_
- Superficie: S1, S9.
- Vector: si la root/intermediate CA de la PKI de Vault se ve comprometida, un
  atacante emite certs de cliente válidos y se hace pasar por cualquier partner.
- Impacto: C/I/No-repudio — colapso total de la identidad de transporte.
- Mitigación: PKI de Vault con CA intermedia de vida corta y root offline;
  certs de cliente de **vida corta y rotables** (ADR-0001); separación de
  permisos Vault (solo ms-auth puede pedir emisión, vía rol PKI restringido al
  partner); monitoreo de emisiones. Plan de rotación/revocación de root
  documentado.
- Estado: **Mitigación pendiente (Fase 2/3)** — el motor PKI y la orquestación
  los implementa ms-auth en Fase 2; la operación de la root CA es operacional.

**T1.3 — Cert de cliente revocado pero aún aceptado (CRL/OCSP stale).**
_[Spoofing]_
- Superficie: S1.
- Vector: un partner dado de baja o cuyo cert fue robado sigue presentando un
  cert que el edge no ha marcado como revocado (CRL no propagada).
- Impacto: C/I — acceso de un partner que debería estar bloqueado.
- Mitigación: terminación mTLS en proxy delante para recargar CRL sin reiniciar
  (decisión de ADR-0002); ms-auth gestiona estado `revoked` en
  `partner_certificate` y revoca en Vault PKI; además la **suscripción activa**
  se valida en línea (el partner se resuelve por `azp` + suscripción
  sincronizada a Redis, no solo por cert) → un partner suspendido se bloquea por
  Redis aunque el cert no haya caducado.
- Estado: **Mitigación pendiente (Fase 2/3)**. La doble verificación
  (cert + suscripción Redis) es defensa en profundidad por diseño.

**T1.4 — Terminación TLS dentro de Netty rígida ante rotación de CA en prod.**
_[Denial of Service operacional]_
- Superficie: S1.
- Vector: si se terminara mTLS en el Netty del gateway en prod, rotar la CA/CRL
  exige reiniciar la JVM → ventana de indisponibilidad o de aceptación de certs
  caducos.
- Impacto: D.
- Mitigación: ADR-0002 descarta explícitamente terminar mTLS en Netty para prod;
  recomienda proxy delante (Envoy/NGINX) reservando Netty para dev.
- Estado: **Mitigado por diseño** (decisión de despliegue).

### 2. Binding de token (RFC 8705)

**T2.1 — Robo y reuso de access token (bearer sin binding).** _[Spoofing]_
- Superficie: S2, S3.
- Vector: un token interceptado (logs, proxy, app comprometida) se reusa desde
  otra máquina. Sin RFC 8705 el token es un bearer puro.
- Impacto: C/I/No-repudio/Facturación — suplantación del partner; las
  transacciones se facturan a la víctima.
- Mitigación: token cert-bound RFC 8705. Filtro `CertBindingFilter` en
  `partnerSecurityChain` del gateway que valida que `cnf.x5t#S256` del JWT
  coincida con el thumbprint del cert mTLS propagado en
  `X-Client-Cert-Thumbprint`; si no coinciden → 401. Sin la clave privada del
  partner el token robado es inútil.
- Estado: **Mitigación pendiente (Fase 3)**. Es la razón de ser del binding
  (ADR-0001 §1, alternativa "mTLS sin binding" descartada).

**T2.2 — Downgrade a bearer sin binding (claim `cnf` ausente).** _[Tampering /
Elevation of Privilege]_
- Superficie: partnerSecurityChain.
- Vector: un token emitido sin `cnf` (cliente sin holder-of-key, o emitido por
  un endpoint mal configurado) pasaría la validación de issuer/firma del
  `partnerJwtDecoder` actual, que **solo valida issuer** (`JwtValidators
  .createDefaultWithIssuer`), sin exigir `cnf`.
- Impacto: C/I — se evade el binding por omisión del claim.
- Mitigación: `CertBindingFilter` debe tratar la **ausencia** de `cnf.x5t#S256`
  como fallo (fail-closed), no solo el mismatch; habilitar holder-of-key
  obligatorio en el client `unilink-api` de Keycloak para que todo token partner
  lleve `cnf`.
- Estado: **Mitigación pendiente (Fase 3)** — explicitar fail-closed en el
  filtro y en la config de Keycloak.

**T2.3 — Replay del token dentro de su ventana de validez.** _[Spoofing]_
- Superficie: S3.
- Vector: aun con binding, un token capturado junto con tráfico mTLS terminado
  podría reusarse si el thumbprint no se reverifica en cada request.
- Impacto: C/I/Facturación.
- Mitigación: el binding se valida **por request** en el gateway (no solo al
  emitir); TTL de token corto en Keycloak; mTLS por conexión. Ver también §7
  (idempotencia) para replay a nivel de transacción.
- Estado: **Mitigación pendiente (Fase 3)** combinada con §7.

### 3. Autenticación y autorización en el gateway

**T3.1 — Bypass de cadena de seguridad por ruta no cubierta.** _[Elevation of
Privilege]_
- Superficie: S4.
- Vector: una ruta nueva (p. ej. outbound) que no encaje en ninguna cadena, o
  un cambio de `@Order` que altere el matcheo, podría caer sin `denyAll()`.
- Impacto: C/I.
- Mitigación: cada cadena mantiene `anyExchange().denyAll()` como red de
  seguridad (verificado en `partnerSecurityChain` y `adminSecurityChain`);
  restricción arquitectónica de ADR-0002 §3 (conservar `denyAll` por cadena al
  añadir la cadena/orden outbound). Tests de seguridad de rutas por cadena.
- Estado: **Mitigado por diseño** para las cadenas actuales; **pendiente**
  reverificar al añadir la cadena outbound (Fase 5).

**T3.2 — Confusión de realms (token de `hub-admin` aceptado en `/partner/**`
o viceversa).** _[Spoofing / Elevation of Privilege]_
- Superficie: partnerSecurityChain / adminSecurityChain.
- Vector: un partner usa un token del realm admin (o al revés) para alcanzar
  rutas de la otra cadena.
- Impacto: C/I — cruce de privilegios entre el plano externo y el interno.
- Mitigación: decoders JWT **separados por realm** y por cadena
  (`partnerJwtDecoder` valida issuer `…/realms/hub-partner`, `adminJwtDecoder`
  issuer `…/realms/hub-admin`); cada cadena enlaza su decoder explícitamente en
  `oauth2ResourceServer`. Un token de admin falla la validación de issuer en la
  cadena partner.
- Estado: **Mitigado por diseño** (verificado en `SecurityConfiguration` del
  gateway).

**T3.3 — JWT con issuer incorrecto / JWKS suplantado.** _[Spoofing /
Tampering]_
- Superficie: decoders del gateway.
- Vector: si `external-url` (issuer) y `auth-server-url` (JWKS) no son
  consistentes, o si un atacante redirige el JWKS, se podrían aceptar tokens
  firmados por una clave atacante.
- Impacto: C/I.
- Mitigación: issuer validado contra `external-url` con
  `JwtValidators.createDefaultWithIssuer`; JWKS servido sobre canal de confianza
  (Keycloak en red interna). Consistencia issuer/JWKS documentada como gotcha
  (CLAUDE.md, ADR config). Validación de firma vía JWKS de Keycloak.
- Estado: **Mitigado por diseño** (config), con la salvedad operacional de
  mantener Keycloak en red no pública y el issuer consistente.

**T3.4 — `alg=none` o algoritmo débil en el JWT.** _[Tampering]_
- Superficie: decoders.
- Vector: token con `alg: none` o algoritmo simétrico inesperado.
- Impacto: C/I.
- Mitigación: `NimbusReactiveJwtDecoder` construido desde JWKS solo acepta los
  algoritmos asimétricos publicados por Keycloak (RS256/ES256); `none` se
  rechaza por defecto. No se acepta clave simétrica inline.
- Estado: **Mitigado por diseño** (comportamiento de Nimbus + JWKS).

**T3.5 — Rate-limit e IP-whitelist fail-open (bypass por caída de Redis).**
_[Denial of Service / Elevation of Privilege]_
- Superficie: S4.
- Vector: `RateLimitFilter` e `IpWhitelistFilter` usan `.onErrorResume` que, ante
  Redis no disponible, **dejan pasar el request** (fail-open). Un atacante que
  degrade Redis evade tanto el rate limit como la whitelist de IP. Es una
  decisión deliberada (gotcha 13: no provocar 500 si Redis cae) con coste de
  seguridad.
- Impacto: D (abuso de rate limit) / C-I parcial (bypass de IP whitelist).
- Mitigación: el fail-open es **riesgo aceptado** por disponibilidad, pero debe
  acotarse: monitoreo/alerta de salud de Redis, y para entornos sensibles
  evaluar fail-closed selectivo en `IpWhitelistFilter` (la whitelist de IP es
  control de acceso, no solo de capacidad). El binding mTLS (§2) sigue activo
  aunque Redis caiga, así que el bypass no anula la identidad.
- Estado: **Riesgo aceptado** (rate limit) / **Mitigación pendiente**
  (revisar fail-closed para IP whitelist en partners críticos).

**T3.6 — Spoofing de IP de origen vía `X-Forwarded-For`.** _[Spoofing]_
- Superficie: `IpWhitelistFilter`.
- Vector: `extractClientIp` toma el **primer** valor de `X-Forwarded-For` sin
  validar que provenga de un proxy de confianza. Un atacante que controle ese
  header se atribuye una IP en la whitelist del partner.
- Impacto: bypass del control de IP whitelist (C/I parcial).
- Mitigación: confiar en `X-Forwarded-For` solo cuando el edge esté detrás de un
  proxy controlado que **reescriba** el header (no lo concatene del cliente);
  configurar `ForwardedHeaderTransformer`/trusted-proxies en el gateway o tomar
  la IP del último hop confiable. Documentar que la whitelist de IP solo es
  fiable con un proxy front controlado (coherente con la terminación mTLS en
  proxy de Fase 3).
- Estado: **Mitigación pendiente (Fase 3)** — ligado a la introducción del
  proxy front.

**T3.7 — Filtros aplican política a requests sin `azp` resuelto.** _[Elevation
of Privilege]_
- Superficie: S4.
- Vector: ambos filtros hacen `switchIfEmpty(chain.filter(exchange))` y, si no
  hay `Jwt` o falta `azp`, dejan pasar **sin** aplicar IP/rate. En la cadena
  partner siempre habrá JWT autenticado antes (orden 1 de seguridad corre antes
  que el filtro global orden 1/3), pero conviene asegurar que ninguna ruta
  protegida llegue al filtro sin principal.
- Impacto: D (rutas sin rate limit) / bypass de whitelist.
- Mitigación: la cadena de seguridad rechaza requests no autenticados antes de
  estos filtros para `/partner/**` (`authenticated()` + `denyAll`); el
  `switchIfEmpty` solo aplica a rutas públicas (`/oauth2/token`). Verificar con
  test que ninguna ruta de negocio dependa del `azp` para su control y a la vez
  permita principal nulo.
- Estado: **Mitigado por diseño** (orden de cadenas), **verificación pendiente**
  con tests al añadir rutas.

**T3.8 — Abuso del endpoint público `POST /oauth2/token`.** _[Denial of Service
/ Spoofing]_
- Superficie: S2.
- Vector: el token endpoint partner es público (proxy a Keycloak). Fuerza bruta
  de `client_secret`, o flood para agotar Keycloak.
- Impacto: D / C (si secret débil).
- Mitigación: client_credentials con secret fuerte rotable (gestionado por
  ms-auth/Keycloak); rate limit del propio Keycloak; el `RateLimitFilter` del
  gateway **excluye** `/oauth2/token` del conteo por `azp` (no hay token aún),
  por lo que debe añadirse un rate limit por IP específico para esa ruta.
- Estado: **Mitigación pendiente** — rate limit por IP sobre `/oauth2/token`
  (no cubierto por el filtro por `azp` actual).

### 4. Integridad de la cadena de auditoría

**T4.1 — Manipulación directa de registros de auditoría en DB.** _[Tampering /
Repudiation]_
- Superficie: S6 (`audit_log`, `decode_log`).
- Vector: un insider con acceso a la DB edita/borra filas para ocultar o alterar
  una transacción.
- Impacto: I/No-repudio/Facturación — se destruye el valor probatorio.
- Mitigación: hash-chain tamper-evident (`prevHash` + `recordHash`) — alterar
  una fila rompe la cadena de todas las posteriores; firma del `recordHash` con
  clave **Vault Transit** (la DB no tiene la clave de firma, así que no puede
  re-firmar); verificación periódica de la cadena. Diseñado en ADR-0001 §2 y
  ADR-0002 (audit-commons Fase 1).
- Estado: **Mitigación pendiente (Fase 1)**. Hoy `audit_log`/`decode_log` son
  manipulables sin evidencia: `InProcessSink.toRequest` ni siquiera transporta
  campos de hash (no existen aún).

**T4.2 — Ruptura/forja del hash-chain (ataque al `prev_hash`).** _[Tampering /
Repudiation]_
- Superficie: S6.
- Vector: un atacante con escritura recalcula `prev_hash`/`record_hash` de un
  segmento para insertar/eliminar registros y dejar la cadena "consistente".
- Impacto: I/No-repudio.
- Mitigación: cada `recordHash` se **firma con Vault Transit**; recalcular la
  cadena exige re-firmar todos los registros posteriores, lo que requiere la
  clave de firma de Vault (no accesible desde la DB ni desde el servicio sin
  permiso Transit). Verificación de firma además del encadenamiento. La cadena se
  construye en el **sink que persiste** (no en el publisher), garantizando orden.
- Estado: **Mitigación pendiente (Fase 1)** — depende de habilitar firma Transit.

**T4.3 — Race condition en escritura concurrente del hash-chain.** _[Tampering /
Integrity]_
- Superficie: S6.
- Vector: dos transacciones concurrentes leen el mismo `prev_hash` (último de la
  cadena) y escriben dos registros con el mismo predecesor → bifurcación/cadena
  inválida.
- Impacto: I — la cadena deja de ser verificable; falsos positivos de
  manipulación.
- Mitigación: **particionar la cadena por partner** (cada partner una secuencia
  `prev_hash` propia) para reducir contención (ADR-0001 §"negativas", ADR-0002
  audit-commons §3 y restricción 6); dentro de una partición, serializar la
  escritura (lock por partición / tabla de cabeza, o secuencia transaccional).
  La cadena se calcula en el sink que persiste, no en el publisher
  fire-and-forget.
- Estado: **Mitigación pendiente (Fase 1)** — el particionado y la
  serialización por partición están diseñados pero no implementados.

**T4.4 — Pérdida de eventos de auditoría por buffer lleno (publisher
fire-and-forget).** _[Repudiation / Denial of Service de evidencia]_
- Superficie: S6 (audit-commons).
- Vector: `AuditEventPublisher.publish` hace `buffer.offer()` y **descarta** el
  evento si el `LinkedBlockingQueue` está lleno (incrementa `audit.events
  .dropped`). Bajo carga o si el sink está lento, se pierden registros de
  auditoría — y, si el outbox dependiera de esta vía, eventos facturables.
- Impacto: No-repudio/Facturación — huecos en la evidencia; potencial pérdida de
  ingresos.
- Mitigación: la **auditoría/medición facturable NO debe ir por el publisher
  fire-and-forget**: ADR-0002 escribe el `outbox_event` y el registro con hash en
  la **misma TX de negocio** (sink que persiste), no por el buffer en memoria. El
  publisher fire-and-forget se reserva para auditoría operativa no crítica.
  Monitorear el contador `audit.events.dropped` y alertar.
- Estado: **Mitigado por diseño** (separación outbox transaccional vs. publisher)
  + **Mitigación pendiente (Fases 1/4)** para implementar el camino transaccional;
  el drop del publisher es **riesgo aceptado** para auditoría no facturable.

**T4.5 — Compromiso de la clave de firma Vault Transit.** _[Tampering /
Repudiation]_
- Superficie: S6, S9.
- Vector: si la clave Transit que firma `recordHash` se filtra, un atacante
  re-firma una cadena manipulada.
- Impacto: I/No-repudio total de la auditoría.
- Mitigación: clave Transit **no exportable** (las operaciones de firma ocurren
  dentro de Vault, la clave nunca sale); políticas Vault de mínimo privilegio
  (solo el rol del sink puede firmar, nadie puede leer/exportar); rotación de
  versión de clave Transit con retención de versiones para verificar firmas
  históricas; auditoría de accesos a Vault. Ver §9 para rotación.
- Estado: **Mitigación pendiente (Fase 1)** — Transit activable por propiedad
  (`audit.integrity.enabled`, `audit.transit.*`), config de políticas pendiente.

### 5. Fuga de PII y datos sensibles

**T5.1 — Payload de negocio en claro en logs.** _[Information Disclosure]_
- Superficie: S5 (ms-base), logs del gateway.
- Vector: logging DEBUG (perfil local) o trazas de error que vuelcan el cuerpo
  del request/response (datos de QR desencriptado = PII financiera).
- Impacto: C — exposición de PII.
- Mitigación: no loguear payloads de negocio; la auditoría guarda **hash
  SHA-256** del payload, no el contenido (ADR-0001 §2); cuando se requiere
  reproducir contenido se guarda **cifrado** con envelope encryption Transit,
  nunca en claro (ADR-0001, alternativa "guardar en claro" descartada). Revisar
  niveles de log en prod (no DEBUG). En `decode_log` ya se guarda `qr_string_hash`
  (hash), no el QR en claro.
- Estado: **Mitigado por diseño** (hash + cifrado bajo demanda) +
  **verificación pendiente** de niveles de log/redaction en prod.

**T5.2 — Payload sin cifrar persistido en `audit_log`/`decode_log`.**
_[Information Disclosure]_
- Superficie: S6.
- Vector: por error de implementación se persiste el payload de negocio crudo en
  una columna `details`/`metadata` (jsonb) en lugar del hash.
- Impacto: C.
- Mitigación: contrato canónico guarda `requestHash`/`responseHash`, no payload;
  el payload solo se guarda **cifrado Transit** y solo si lo exige un requisito
  legal (envelope encryption). Revisión de código del sink para garantizar que
  `details` no contenga PII en claro.
- Estado: **Mitigado por diseño** (hash + cifrado) + **verificación pendiente**
  en implementación del sink (Fase 1).

**T5.3 — Datos sensibles en Redis sin TTL.** _[Information Disclosure]_
- Superficie: Redis (gateway/ms-base).
- Vector: la caché de QR decode o de capabilities outbound deja PII en Redis sin
  expiración o accesible a quien comprometa Redis.
- Impacto: C.
- Mitigación: TTL explícito en todas las claves (la caché de QR decode tiene
  TTL 1440 min / 24h — CLAUDE.md; el `RateLimitFilter` setea TTL 2 min);
  Redis en red interna; evaluar no cachear payloads con PII o cachear solo
  resultados hasheados/no sensibles. Verificar que la caché outbound (Fase 5)
  defina TTL y no almacene PII.
- Estado: **Mitigado por diseño** (TTL en QR/rate limit) + **Mitigación
  pendiente (Fase 5)** para la caché outbound.

**T5.4 — Clave privada / cert en logs o memoria volcada.** _[Information
Disclosure]_
- Superficie: ms-base (`CryptoService`, BouncyCastle), Tuxedo.
- Vector: una clave privada RSA o el PEM de un cert se loguea (p. ej. en una
  excepción de descifrado) o se propaga en un mensaje de error.
- Impacto: C — compromiso de la capacidad de descifrado de la entidad
  financiera.
- Mitigación: claves gestionadas en el JKS del lado Tuxedo (ms-base pide el PEM
  por `TuxedoApiClient`, no almacena la privada); nunca loguear material de
  clave; `TuxedoApiException` no debe incluir el PEM. Revisar que los `log.error`
  de `CryptoService`/`TuxedoApiClient` no vuelquen contenido sensible (el cliente
  hoy loguea `getResponseBodyAsString()` de errores HTTP, que podría contener
  material — revisar redaction).
- Estado: **Mitigación pendiente** — auditar redaction en logs de errores de
  Tuxedo/Crypto.

**T5.5 — Token / `Authorization: Bearer` en logs.** _[Information Disclosure]_
- Superficie: `AccessLogFilter`, logs del gateway, `TuxedoApiClient` (manda API
  key como `Authorization: Bearer`).
- Vector: el access log o un dump de headers registra el token bearer o la API
  key de Tuxedo.
- Impacto: C — robo de token (encadena con T2.1) o de la API key de Tuxedo.
- Mitigación: redaction de `Authorization`/`Cookie` en `AccessLogFilter` y en
  cualquier dump de headers; no loguear headers por defecto en prod. La API key
  de Tuxedo debe venir de Vault (ver §8/§9), no de config en claro.
- Estado: **Mitigación pendiente** — verificar redaction en `AccessLogFilter`.

### 6. Manipulación del log de facturación (outbox)

**T6.1 — Inyección de eventos espurios en el outbox.** _[Tampering]_
- Superficie: S7 (`outbox_event`).
- Vector: un insider inserta filas en `outbox_event` para inflar (o reducir) la
  facturación de un partner.
- Impacto: I/Facturación.
- Mitigación: solo la lógica de negocio escribe el outbox dentro de la **misma
  TX** que el registro auditado; conciliación cruzada entre `outbox_event` y la
  auditoría con hash-chain (un evento facturable debe tener su registro de
  auditoría firmado correspondiente); permisos de DB restringidos. El
  `idempotencyKey` único evita duplicados.
- Estado: **Mitigado por diseño** (TX única + conciliación) — **Mitigación
  pendiente (Fase 4)** para implementar la conciliación.

**T6.2 — Duplicación de eventos facturables (relay at-least-once).**
_[Tampering / Integrity de facturación]_
- Superficie: S7 (relay).
- Vector: el relay reprocesa un `outbox_event` (reintento, crash entre marcar
  SENT y commitear) y se factura dos veces.
- Impacto: I/Facturación (sobre-cobro).
- Mitigación: garantía **at-least-once + idempotencia por `idempotency_key`**
  (único en `outbox_event`); el consumidor deduplica por esa clave (ADR-0001 §3,
  ADR-0002 restricción 8). Estado `PENDING/SENT/FAILED` + `attempts`.
- Estado: **Mitigación pendiente (Fase 4)** — diseño claro, implementación
  pendiente.

**T6.3 — Omisión deliberada de eventos facturables.** _[Repudiation / fraude de
facturación]_
- Superficie: S7.
- Vector: un partner (o insider) provoca que una transacción exitosa **no**
  genere su `outbox_event` (p. ej. error que confirma negocio pero salta el
  outbox).
- Impacto: Facturación (sub-cobro) / No-repudio.
- Mitigación: escribir el `outbox_event` en la **misma TX** que el resultado de
  negocio (patrón outbox transaccional): si no se escribe el evento, la
  transacción de negocio hace rollback → no hay respuesta sin evento facturable.
  Conciliación auditoría↔outbox (T6.1) detecta huecos.
- Estado: **Mitigado por diseño** (outbox transaccional) — **Mitigación
  pendiente (Fase 4)**.

**T6.4 — Race condition entre la TX de negocio y el relay.** _[Integrity]_
- Superficie: S7.
- Vector: el relay lee `outbox_event` PENDING antes de que la TX de negocio
  haya commiteado (lectura sucia) o procesa un evento aún no visible.
- Impacto: I/Facturación.
- Mitigación: el outbox se escribe **dentro** de la TX de negocio (visible al
  relay solo tras commit, por aislamiento READ_COMMITTED); el relay
  `@Scheduled` lee solo filas commiteadas en estado PENDING y las marca SENT en
  su propia TX. Sin entrega exactly-once (riesgo aceptado, ADR-0002 r.8),
  cubierto por idempotencia (T6.2).
- Estado: **Mitigado por diseño** (semántica outbox) — **implementación
  pendiente (Fase 4)**.

### 7. Replay y idempotencia

**T7.1 — Replay de request inbound.** _[Spoofing / Tampering / Facturación]_
- Superficie: S3.
- Vector: un request inbound capturado se reenvía N veces (token aún válido,
  binding presente) → resultado duplicado y N veces facturado.
- Impacto: I/Facturación/D.
- Mitigación: TTL de token corto + binding mTLS (limita la ventana y el origen);
  para operaciones no idempotentes, exigir/propagar un `requestId`/idempotency
  key del contrato canónico inbound (`requestId` ya está en el contrato
  canónico de ADR-0002) y deduplicar en negocio; rate limit acota el volumen.
- Estado: **Mitigación pendiente (Fases 3/4)** — el `requestId` canónico existe
  en diseño; la deduplicación de negocio inbound queda por implementar.

**T7.2 — Replay de llamada outbound.** _[Tampering / Facturación]_
- Superficie: S8.
- Vector: un retry de resilience4j o una app interna que reintenta provoca
  doble llamada al proveedor (doble efecto/doble costo).
- Impacto: I/Facturación.
- Mitigación: idempotencia en el adaptador (propagar idempotency key al
  proveedor cuando lo soporte); resilience4j con política de retry acotada y
  solo en operaciones idempotentes; el outbox dedup por `idempotency_key` evita
  doble medición.
- Estado: **Mitigación pendiente (Fase 5)**.

**T7.3 — Colisión / reuso de `idempotency_key`.** _[Tampering]_
- Superficie: S7.
- Vector: dos transacciones distintas generan la misma `idempotency_key` →
  una se descarta como "duplicado" y se pierde facturación; o un atacante
  reutiliza una key para que su transacción no se facture.
- Impacto: Facturación/I.
- Mitigación: `idempotency_key` derivada de datos únicos de la transacción
  (p. ej. `requestId` + partner + producto + ventana temporal), no
  adivinable/forzable por el partner; restricción UNIQUE en `outbox_event`;
  generación server-side, no confiar en una key provista por el cliente sin
  namespacing por partner.
- Estado: **Mitigación pendiente (Fase 4)** — definir la derivación de la key.

### 8. Superficie de los adaptadores outbound (ACL)

**T8.1 — SSRF desde el hub hacia la red interna.** _[Information Disclosure /
Elevation of Privilege]_
- Superficie: S8 (`OutboundAdapter`, `TuxedoApiClient`).
- Vector: si la URL del proveedor (o parte de ella: path, host) se construye con
  input del request canónico, una app interna comprometida fuerza al hub a
  llamar a `http://169.254.169.254/…` (metadata cloud), a Vault/Consul o a
  servicios internos. El `TuxedoApiClient` ya toma `baseUrl` de configuración
  (no de input) — ese patrón debe mantenerse.
- Impacto: C/EoP — el hub como confused deputy hacia la red interna.
- Mitigación: los endpoints de proveedor se definen en el **catálogo de
  proveedores** (`provider` / `provider_credential_ref`), **no** se derivan del
  input del usuario; allowlist de hosts/destinos por adaptador; el adaptador
  traduce contrato canónico → formato proveedor sin permitir override de host;
  egress controlado (proxy de salida / network policy) que solo permita los
  destinos de proveedor registrados.
- Estado: **Mitigación pendiente (Fase 5)** — el catálogo `provider` está
  diseñado; allowlist de egress es control a implementar.

**T8.2 — Credenciales de proveedor en Vault comprometidas.** _[Information
Disclosure / Spoofing]_
- Superficie: S8, S9.
- Vector: las credenciales centralizadas de todos los proveedores viven en
  Vault; un compromiso de Vault o de la política de lectura expone todas.
- Impacto: C — robo de credenciales de múltiples terceros (riesgo concentrado
  por la centralización del ACL).
- Mitigación: `provider_credential_ref` guarda **referencia** a la credencial,
  no el secreto (ADR-0002); políticas Vault de mínimo privilegio por adaptador
  (cada adaptador solo lee su path); rotación de credenciales; auditoría de
  accesos Vault; namespaces Vault (`hub-base`) por servicio. La
  centralización es una decisión consciente (ADR-0001 §4: centralizar
  credenciales) cuyo coste es la concentración del riesgo.
- Estado: **Mitigado por diseño** (referencia + Vault + mínimo privilegio) —
  **implementación pendiente (Fase 5)**.

**T8.3 — Respuesta maliciosa del proveedor externo.** _[Tampering / Denial of
Service]_
- Superficie: S8.
- Vector: un proveedor (o un MITM sobre su canal) devuelve un payload enorme,
  malformado, o diseñado para deserialización insegura / billion-laughs.
- Impacto: I/D — corrupción de datos canónicos, agotamiento de memoria.
- Mitigación: el adaptador ACL **traduce y valida** la respuesta del proveedor
  contra el contrato canónico (no propaga el formato crudo); deserialización
  solo a DTOs tipados con Jackson (no `ObjectInputStream` ni polimorfismo
  inseguro); límites de tamaño de respuesta; TLS del lado proveedor verificado;
  timeouts de lectura (el `TuxedoApiClient` ya fija read/connect timeout).
- Estado: **Mitigación pendiente (Fase 5)** — la validación canónica es el
  control central del ACL; ms-base usa Jackson tipado (sin deserialización
  Java nativa), lo que ya descarta el vector clásico.

**T8.4 — Fallo de resiliencia del proveedor como DoS indirecto.** _[Denial of
Service]_
- Superficie: S8.
- Vector: un proveedor lento/caído agota hilos/conexiones del hub (el
  `TuxedoApiClient` usa `SimpleClientHttpRequestFactory` **sin pool ni
  resilience4j** hoy), degradando también los flujos inbound que comparten el
  proceso ms-base.
- Impacto: D — un tercero tumba el núcleo del hub.
- Mitigación: resilience4j por adaptador (timeout, retry acotado,
  **circuit breaker**, **bulkhead** para aislar el pool de hilos por proveedor)
  — ADR-0001 §4 y ADR-0002 Fase 5; reencuadrar `TuxedoApiClient` como primer
  adaptador con resiliencia. Mientras tanto, los timeouts ya configurados
  acotan parcialmente el bloqueo.
- Estado: **Mitigación pendiente (Fase 5)** — hoy solo hay timeouts; falta
  circuit breaker/bulkhead.

### 9. Operacional y cadena de suministro

**T9.1 — Rotación/compromiso de clave Vault Transit (auditoría).** _[Tampering /
Repudiation]_
- Superficie: S6, S9.
- Vector: rotar la clave Transit sin retener versiones invalida la verificación
  de firmas históricas; un compromiso de la versión activa permite re-firmar.
- Impacto: I/No-repudio.
- Mitigación: rotación con **retención de versiones** (Transit conserva versiones
  para verificar firmas antiguas); `min_decryption_version`/`min_encryption_version`
  gestionados; clave no exportable; políticas de mínimo privilegio (firma sí,
  export no). Procedimiento de rotación documentado.
- Estado: **Mitigación pendiente (Fase 1 + operacional)**.

**T9.2 — PKI root CA: compromiso o caducidad.** _[Spoofing / Denial of Service]_
- Superficie: S9.
- Vector: compromiso de la root → emisión fraudulenta (ver T1.2); caducidad no
  planificada → todos los certs de cliente dejan de validar (outage masivo).
- Impacto: C/I (compromiso) o D (caducidad).
- Mitigación: root CA offline + intermediate de vida media en Vault PKI; certs
  de cliente de vida corta rotables; monitoreo de expiración de CA e
  intermediates; plan de renovación de CA documentado.
- Estado: **Mitigación pendiente (Fase 2/3 + operacional)**.

**T9.3 — Secretos hardcodeados en código/config.** _[Information Disclosure]_
- Superficie: S9, perfiles de config.
- Vector: el perfil `local` lleva **secretos de Keycloak hardcodeados**
  (clients/secrets `*-secret`, CLAUDE.md) y Vault dev usa token `root`. Si esos
  valores llegan a un entorno no-local o al repo público, son explotables. La
  API key de Tuxedo se inyecta en `TuxedoApiClient` desde `ApplicationProperties`.
- Impacto: C/EoP.
- Mitigación: secretos reales **solo en Vault** (namespaces `hub-auth`/
  `hub-base`); los `*-secret` y el token `root` son **exclusivos de `local`**
  (`vault.enabled=false`, valores de juguete) y nunca deben usarse en dev/qa/prod;
  `.gitignore` de `.env`; rotación de secretos de cliente Keycloak; la API key de
  Tuxedo debe provenir de Vault, no de config en claro en prod. Escaneo de
  secretos en CI.
- Estado: **Riesgo aceptado en `local`** (valores de juguete, sin Vault) /
  **Mitigación pendiente** para garantizar por CI que no se filtren a otros
  entornos y que Tuxedo API key venga de Vault.

**T9.4 — Imagen de contenedor comprometida / dependencia vulnerable.**
_[Tampering / Elevation of Privilege]_
- Superficie: S9.
- Vector: dependencia transitiva con CVE crítico, o imagen base manipulada
  (`eclipse-temurin:25-jre-alpine`, registry `cr.sintesis.com.bo/hub-dev`).
- Impacto: C/I/EoP.
- Mitigación: build reproducible con Jib (sin Dockerfile arbitrario); fijar
  digests de imagen base; escaneo de dependencias (OWASP dependency-check /
  `gradle dependencies` + scanner) y de imágenes en CI; pull desde registry
  privado autenticado; contenedor no-root. **Nota**: este auditor no ejecutó
  `npm audit`/`dependency-check` en esta revisión (estática); debe correrse en
  CI.
- Estado: **Mitigación pendiente** — establecer escaneo en CI y pin de digests.

**T9.5 — ms-base en MODO DESARROLLO: bypass de autorización por acceso directo.**
_[Elevation of Privilege]_
- Superficie: S5 (`hub-ms-base` `:8081`).
- Vector: `SecurityConfiguration` de ms-base tiene `anyRequest().permitAll()`,
  `oauth2ResourceServer` JWT comentado y `@EnableMethodSecurity`/`@PreAuthorize`
  deshabilitados (verificado en código). Cualquiera que alcance `:8081`
  directamente en la red interna (insider, lateral movement, o exposición
  accidental) invoca `/api/qr/decode`, `/api/certificates/**` (incluido
  revoke/replace de certificados) y `/api/qr/audits` **sin autenticación ni
  autorización**. El `KeycloakRealmRoleConverter` está escrito pero no cableado.
- Impacto: C/I/EoP — descifrado de QR no autorizado, manipulación del ciclo de
  vida de certificados, lectura de logs con PII.
- Mitigación compensatoria (vigente): ms-base **no se expone directo en prod**;
  el único ingreso es el gateway, que sí autentica (JWT realm `hub-partner` +
  binding tras Fase 3) — ADR-0002 restricción 5. Aislamiento de red entre el
  gateway y ms-base (Consul/LB en red privada).
- Mitigación pendiente (control real): habilitar JWT +
  `KeycloakRealmRoleConverter` + `@PreAuthorize` con roles `API_CLIENT`/`ADMIN`/
  `AUDITOR` en ms-base, retirando la dependencia exclusiva del gateway
  (descomentar `@EnableMethodSecurity` y `oauth2ResourceServer` en
  `SecurityConfiguration`).
- Estado: **Mitigación pendiente (Fase 6)**; protección actual = aislamiento de
  red (mitigación compensatoria), **no por código**. Es la vulnerabilidad
  conocida de mayor impacto del estado actual.

**T9.6 — CORS permisivo en ms-base.** _[Information Disclosure (defensa en
profundidad)]_
- Superficie: S5.
- Vector: `corsConfigurationSource` de ms-base permite `http://localhost:*` y
  `https://*.sintesis.com.bo` con `allowCredentials=true` y `allowedHeaders=*`.
  Combinado con el MODO DESARROLLO, un origen `*.sintesis.com.bo` controlado por
  un atacante (subdominio tomado) podría hacer requests con credenciales.
- Impacto: C (bajo, ms-base no expuesto directo; defensa en profundidad).
- Mitigación: en prod los navegadores no acceden a ms-base directo (solo el
  gateway, server-to-server, donde CORS no aplica); restringir orígenes a la
  lista exacta de FE en prod; no usar wildcard de subdominio con
  `allowCredentials`. El CORS efectivo de cara al navegador es el del gateway.
- Estado: **Riesgo aceptado** (ms-base no expuesto a navegadores en prod) +
  **Mitigación pendiente** de endurecer orígenes en Fase 6.

---

## Matriz de riesgos residuales

Probabilidad/Impacto: Baja/Media/Alta. Riesgo residual asumiendo el estado
**actual** (pre-Fases 1–6).

| Amenaza | Prob. | Impacto | Riesgo residual | Estado |
|---------|-------|---------|-----------------|--------|
| T9.5 ms-base MODO DESARROLLO (bypass authz directo) | Media | Alto | **Alto** | Pendiente (Fase 6) — compensado por red |
| T1.1 Inbound sin mTLS | Alta | Alto | **Alto** | Pendiente (Fase 3) |
| T2.1 Robo/reuso de token (sin binding) | Media | Alto | **Alto** | Pendiente (Fase 3) |
| T4.1 Manipulación de auditoría (sin hash-chain) | Media | Alto | **Alto** | Pendiente (Fase 1) |
| T2.2 Downgrade a bearer sin `cnf` | Media | Alto | **Alto** | Pendiente (Fase 3) |
| T6.3/T6.2 Omisión/duplicación facturable (sin outbox) | Media | Alto | **Alto** | Pendiente (Fase 4) |
| T4.3 Race del hash-chain | Media | Medio | **Medio** | Pendiente (Fase 1) |
| T8.1 SSRF desde adaptadores outbound | Baja | Alto | **Medio** | Pendiente (Fase 5) |
| T8.4 DoS indirecto por proveedor (sin CB/bulkhead) | Media | Medio | **Medio** | Pendiente (Fase 5) |
| T3.6 Spoofing IP vía X-Forwarded-For | Media | Medio | **Medio** | Pendiente (Fase 3) |
| T3.5 Fail-open rate/IP por caída de Redis | Baja | Medio | **Medio** | Aceptado (rate) / Pendiente (IP) |
| T3.8 Abuso de `/oauth2/token` (sin rate por IP) | Media | Medio | **Medio** | Pendiente |
| T5.4/T5.5 Material de clave/token en logs | Media | Alto | **Medio** | Pendiente (redaction) |
| T1.2/T9.2 Compromiso PKI root CA | Baja | Alto | **Medio** | Pendiente (Fase 2/3) |
| T4.5/T9.1 Compromiso clave Vault Transit | Baja | Alto | **Medio** | Pendiente (Fase 1) |
| T8.2 Credenciales de proveedor en Vault | Baja | Alto | **Medio** | Pendiente (Fase 5) |
| T7.1/T7.2/T7.3 Replay / idempotencia | Media | Medio | **Medio** | Pendiente (Fases 3/4/5) |
| T9.4 Imagen/dependencia vulnerable | Media | Alto | **Medio** | Pendiente (CI scanning) |
| T9.3 Secretos hardcodeados (local) | Alta | Bajo* | **Bajo** | Aceptado en local / Pendiente CI |
| T4.4 Pérdida de eventos (publisher buffer) | Baja | Medio | **Bajo** | Diseño (outbox TX) / Aceptado (no facturable) |
| T3.2 Confusión de realms | Baja | Alto | **Bajo** | Mitigado por diseño |
| T3.3/T3.4 Issuer/`alg=none` | Baja | Alto | **Bajo** | Mitigado por diseño |
| T3.1 Bypass de cadena (`denyAll`) | Baja | Alto | **Bajo** | Mitigado por diseño |
| T9.6 CORS permisivo ms-base | Baja | Bajo | **Bajo** | Aceptado / Pendiente (Fase 6) |
| T8.3 Respuesta maliciosa de proveedor | Baja | Medio | **Bajo** | Pendiente (Fase 5), Jackson tipado |

\* Bajo asumiendo que los secretos `local` jamás se promueven a otro entorno
(supuesto a garantizar por CI).

---

## Controles obligatorios antes de ir a producción

Priorizados por riesgo residual. Ningún flujo inbound/outbound debe servir
tráfico real de partners sin completar al menos los items 1–6.

1. **Habilitar seguridad real en ms-base (Fase 6) — T9.5.** Descomentar
   `oauth2ResourceServer` JWT, `@EnableMethodSecurity` y `@PreAuthorize`
   (`API_CLIENT`/`ADMIN`/`AUDITOR`) cableando `KeycloakRealmRoleConverter` en
   `hub-ms-base/.../config/SecurityConfiguration.java`. Mientras no esté:
   garantizar por configuración de red/despliegue que `:8081` **nunca** sea
   alcanzable fuera del gateway.

2. **Terminación mTLS + binding RFC 8705 en el edge (Fase 3) — T1.1, T2.1,
   T2.2.** Proxy front (Envoy/NGINX) con CA de Vault PKI; `CertBindingFilter` en
   `partnerSecurityChain` que valide `cnf.x5t#S256` contra
   `X-Client-Cert-Thumbprint` y **rechace** tokens sin `cnf` (fail-closed);
   holder-of-key obligatorio en el client `unilink-api` de Keycloak.

3. **Hash-chain + firma Vault Transit en auditoría (Fases 0/1) — T4.1, T4.2,
   T4.3.** Canonicalización RFC 8785, `requestHash`/`responseHash`/`prevHash`/
   `recordHash`/`signature`, particionado por partner con escritura serializada
   por partición en el sink que persiste, firma Transit no exportable. Migración
   Liquibase aditiva de `audit_log` y propagación en `InProcessSink.toRequest`.

4. **Outbox transaccional + idempotencia (Fase 4) — T6.1, T6.2, T6.3, T6.4,
   T7.3.** `outbox_event` escrito en la misma TX de negocio; relay con dedup por
   `idempotency_key` (UNIQUE, derivada server-side); conciliación
   auditoría↔outbox.

5. **PKI Vault operativa con rotación/CRL (Fase 2/3) — T1.2, T1.3, T9.2.**
   Emisión por ms-auth con rol PKI de mínimo privilegio, certs de vida corta,
   estado `revoked` propagado a CRL y a Redis (suscripción activa), root CA
   offline.

6. **Endurecer filtros del edge — T3.6, T3.8, T3.5.** Confiar en
   `X-Forwarded-For` solo tras proxy controlado (trusted proxies); rate limit
   por IP en `/oauth2/token`; evaluar fail-closed en `IpWhitelistFilter` para
   partners críticos.

7. **Redaction de secretos en logs — T5.4, T5.5.** Redactar
   `Authorization`/`Cookie` en `AccessLogFilter`; no loguear payloads de negocio
   ni PEM/claves ni `responseBody` de Tuxedo con material sensible; nivel de log
   no DEBUG en prod.

8. **Resiliencia y anti-SSRF en adaptadores outbound (Fase 5) — T8.1, T8.3,
   T8.4, T7.2.** resilience4j (timeout/retry/CB/bulkhead) por adaptador;
   destinos solo desde catálogo `provider` (no input); allowlist de egress;
   validación de respuesta contra contrato canónico.

9. **Cadena de suministro y secretos (CI) — T9.3, T9.4.** Escaneo de
   dependencias e imágenes en CI, pin de digests, escaneo de secretos, garantizar
   que los secretos `*-secret`/token `root` de `local` no se promuevan; Tuxedo
   API key y secrets de Keycloak desde Vault en prod.

---

## Veredicto

**NO APTO para release a producción en el estado actual.**

El hub porta un diseño de seguridad sólido y coherente (doble capa de identidad,
auditoría tamper-evident, outbox transaccional, ACL outbound), pero la mayoría de
esos controles están en estado **Mitigación pendiente** (Fases 1–6 aún no
implementadas). Tres condiciones bloqueantes:

- ms-base sirve negocio sensible con `anyRequest().permitAll()` (T9.5) —
  protegido hoy solo por aislamiento de red, no por código.
- No hay terminación mTLS ni binding RFC 8705 (T1.1/T2.1) — la identidad de
  transporte y la protección anti-robo de token, requisitos centrales del
  ADR-0001, no existen todavía.
- No hay hash-chain ni outbox (T4.1/T6.x) — la auditoría con valor legal y la
  facturación confiable, también requisitos centrales, están sin implementar.

Para entornos **no productivos cerrados** (dev/qa en red privada, sin partners
reales ni datos reales), el estado actual es **APTO CON RESERVAS**, condicionado
a: (a) ms-base y ms-auth nunca expuestos fuera del gateway, (b) datos sintéticos,
(c) secretos de juguete confinados a `local`.

Reevaluar el veredicto al cierre de las Fases 1, 3, 4 y 6 (controles obligatorios
1–6). Al completarse, el riesgo residual de los items Alto/Medio cae a Bajo y el
hub pasaría a **APTO** sujeto a una reauditoría de implementación (verificar que
el código realmente materialice los controles diseñados, en especial fail-closed
del binding, serialización del hash-chain y allowlist de egress outbound).
