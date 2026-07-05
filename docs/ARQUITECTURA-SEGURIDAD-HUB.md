# Hub de Interoperabilidad — Arquitectura de Seguridad y Flujo de Petición

> ⚠️ **Documento parcialmente desactualizado** (contiene contenido legacy pre-ADR-0004/rename 2026-07-03).
> Fuente de verdad actual: `CLAUDE.md` y `docs/adr/` (ADR-0005/0006/0007).

**Documento técnico-ejecutivo** · Versión 1.0 · 2026-06-30

> Referencia para presentación técnica. Describe cómo el Hub protege cada
> petición de un tercero (partner) mediante **doble capa criptográfica**:
> **mTLS** (certificado de cliente) + **OAuth2 con token ligado al certificado
> (RFC 8705)**, y el orden exacto de validaciones.

---

## 1. Resumen ejecutivo

El Hub es el **único punto de entrada y salida** entre la institución y los
terceros. Ninguna petición llega a los sistemas internos sin superar, **en este
orden**, tres controles:

1. **mTLS** — el partner debe presentar un **certificado de cliente** emitido por
   la PKI del Hub. Se valida en **un único punto de terminación TLS** (el edge).
2. **OAuth2 / JWT** — el partner debe presentar un **token válido**, emitido por
   Keycloak y **criptográficamente ligado a su certificado** (RFC 8705).
3. **Autorización de negocio** — suscripción/scope del partner y validación del
   contrato del producto solicitado.

**Garantía central:** un token robado es **inútil** sin la clave privada del
certificado del partner. La posesión del certificado (*holder-of-key*) es
condición necesaria en **cada** llamada.

---

## 2. Componentes

```
   PARTNER (sistema externo)
   │  presenta SIEMPRE: certificado de cliente (mTLS) + Bearer JWT
   ▼
┌──────────────────────────────────────────────────────────────────────┐
│  EDGE — Terminación mTLS (ÚNICO PUNTO)                                  │
│  nginx (producción)  ó  gateway :8443 (directo)                        │
│  · Valida el certificado de cliente contra la CA del Hub               │
│  · Inyecta el cert como header X-SSL-Client-Cert hacia adentro         │
└───────────────┬────────────────────────────────────────────────────────┘
                ▼
┌──────────────────────────────────────────────────────────────────────┐
│  API GATEWAY (Spring Cloud Gateway, WebFlux)                           │
│  · Valida el JWT (firma/issuer/expiración) — realm hub-partner        │
│  · Valida el binding RFC 8705: cnf.x5t#S256 (JWT) == thumbprint(cert)  │
│  · Valida suscripción/scope del partner                                │
│  · Propaga X-Partner-Id y enruta al microservicio                      │
└───────────────┬────────────────────────────────────────────────────────┘
                ├───────────────► KEYCLOAK (IdP)  — emite el token ligado al cert
                ▼
┌──────────────────────────────────────────────────────────────────────┐
│  MICROSERVICIO DE NEGOCIO (motor inbound)                              │
│  · Registra la petición (auditoría + outbox de facturación)            │
│  · Valida el payload contra el contrato canónico del producto          │
│  · Reenvía al sistema interno destino (adaptador por proveedor)        │
└──────────────────────────────────────────────────────────────────────┘
```

- **PKI del Hub**: CA propia que emite los certificados de cliente de cada
  partner y el certificado del servidor. El *truststore* del edge solo confía en
  esa CA.
- **Keycloak**: emite tokens OAuth2 `client_credentials` **con el certificado
  enlazado** (X.509 Certificate Lookup en modo proxy: lee `X-SSL-Client-Cert`).

---

## 3. Flujo 1 — Obtención del token

> El partner obtiene un JWT de corta duración. **También** requiere el
> certificado de cliente: el token nace ya ligado a ese certificado.

```
Partner ──(1) POST /oauth2/token  [mTLS: cert cliente]──► EDGE
                                                           │ valida cert vs CA (ÚNICO PUNTO mTLS)
                                                           │ reenvía cert como X-SSL-Client-Cert
                                                           ▼
                                                        GATEWAY ──► KEYCLOAK
                                                                     │ (2) valida client_id + client_secret
                                                                     │ (3) liga el token al cert → cnf.x5t#S256
                                                                     ▼
Partner ◄──────────── access_token (JWT, ~5 min, cnf.x5t#S256) ─────┘
```

| Paso | Control | Falla si… |
|------|---------|-----------|
| 1 | **mTLS** en el edge | no hay cert o no lo firma la CA → rechazo TLS |
| 2 | Credenciales OAuth2 en Keycloak | `client_id`/`client_secret` inválidos → 401 |
| 3 | Enlace token↔cert (RFC 8705) | falta el cert reenviado → error `Client Certification missing` |

---

## 4. Flujo 2 — Llamada a la API de negocio (caso penal)

> Cada llamada de negocio repite mTLS en el edge y añade la validación del JWT y
> del binding **antes** de tocar el sistema interno.

```
Partner ──POST /partner/v1/inbound/CASO_PENAL/v1  [mTLS cert + Bearer JWT]──► EDGE
                                                     │ (1) valida cert vs CA (ÚNICO PUNTO mTLS)
                                                     ▼
                                                  GATEWAY
                                                     │ (2) valida JWT (firma/issuer/exp)
                                                     │ (3) valida binding RFC 8705: cnf == thumbprint(cert)
                                                     │ (4) valida suscripción/scope → propaga X-Partner-Id
                                                     ▼
                                                  MICROSERVICIO (motor inbound)
                                                     │ (5) REGISTRA la petición (auditoría + outbox)
                                                     │ (6) valida payload vs contrato CASO_PENAL/v1
                                                     │ (7) reenvía al sistema interno destino
                                                     ▼
Partner ◄──────────────── 201 { success, data.id_pol_caso, correlationId } ───┘
```

| Paso | Control | Falla si… | Respuesta |
|------|---------|-----------|-----------|
| 1 | **mTLS** en el edge | sin cert válido | rechazo TLS |
| 2 | JWT válido | token ausente/expirado/mal firmado | `401` |
| 3 | **Binding RFC 8705** | el cert de la conexión ≠ el ligado al token | `401 missing_client_certificate` / `certificate_binding_mismatch` |
| 4 | Suscripción/scope | partner sin suscripción para el producto | `403` |
| 5–7 | Registro + contrato + forward | payload no cumple contrato | `400` con `violations` |

**Orden garantizado:** primero mTLS (edge), después JWT + binding (gateway), y
**solo entonces** se registra y procesa la petición. Ninguna petición llega al
paso de registro sin haber superado los controles criptográficos.

---

## 5. Por qué esta arquitectura (mensajes clave)

- **Defensa en profundidad**: dos factores independientes (algo que el partner
  *tiene* — la clave privada del cert — y algo que *presenta* — el token). Ambos
  se exigen en **cada** petición.
- **Token robado = inútil** (RFC 8705, *holder-of-key*): sin la clave privada del
  certificado no se puede usar el token en el edge mTLS ni pasar el binding.
- **Un único punto de entrada auditable**: toda transacción queda registrada con
  su `correlationId` y alimenta la auditoría y la facturación (outbox).
- **Trazabilidad extremo a extremo**: `X-Correlation-ID` / `X-Idempotency-Key`
  atraviesan todo el flujo y se devuelven al partner.
- **Desacople de sistemas internos**: el partner nunca ve el sistema destino; el
  Hub traduce del contrato canónico al del proveedor interno (adaptador).

---

## 6. Implicaciones para pruebas y documentación

- **Swagger UI NO puede ejecutar peticiones**: el navegador no adjunta el
  certificado de cliente a las llamadas XHR de "Try it out", por lo que toda
  petición desde Swagger fallará el mTLS en el edge. Swagger queda como
  **referencia de contratos** (esquemas, campos, respuestas). Para ejecutar,
  usar **cURL** o **Postman** con el certificado configurado.
- **Postman**: requiere cargar el certificado del partner en
  *Settings → Certificates* para el host del Hub (no es un header, es TLS).
- **Entrega al partner**: certificado (`.crt`), clave privada (`.key`), CA del
  Hub (`hub-ca.crt`), guía de integración y colección Postman.

---

## 7. Justificación: buenas prácticas y lineamientos de interoperabilidad (Bolivia / AGETIC)

La arquitectura **no es una elección arbitraria**: responde a principios de
seguridad de la información reconocidos internacionalmente y a los **lineamientos
de interoperabilidad y de gobierno electrónico del Estado boliviano**, cuya
rectoría corresponde a **AGETIC** (Agencia de Gobierno Electrónico y Tecnologías
de Información y Comunicación).

### 7.1 Marco normativo boliviano de referencia

| Instrumento | Aporte relevante |
|-------------|------------------|
| **Ley N° 164 (2011)** — Ley General de Telecomunicaciones y TIC | Reconoce la **firma digital** y los **certificados digitales** como mecanismo de identidad, autenticidad y **no repudio**. |
| **D.S. N° 1793 (2013)** — Reglamento para el desarrollo de TIC | Regula firma digital, certificados y entidades certificadoras (infraestructura de clave pública nacional; **ADSIB** como raíz). |
| **D.S. N° 2514 (2015)** | Crea **AGETIC** y el **CGII** (Centro de Gestión de Incidentes Informáticos). |
| **D.S. N° 3251 (2017)** | Aprueba el **Plan de Implementación de Gobierno Electrónico (PIGE)** y el de Software Libre y Estándares Abiertos (PISLEA); define la **interoperabilidad** como eje. |
| **Lineamientos técnicos de interoperabilidad y de seguridad de la información de AGETIC** | Principios y estándares técnicos para el intercambio seguro entre entidades del Estado. |

> **Nota:** las referencias se citan a nivel de instrumento. Los números de
> artículo y las versiones vigentes deben validarse contra los lineamientos
> publicados por AGETIC al momento de la puesta en producción.

### 7.2 Dimensiones de interoperabilidad cubiertas

Los marcos de interoperabilidad (el boliviano se alinea al modelo de capas tipo
EIF) distinguen capas **legal, organizativa, semántica y técnica**, con la
**seguridad y privacidad como eje transversal**. El Hub las aborda:

- **Legal/organizativa:** convenio de interoperabilidad Fiscalía↔FELCN, suscripción del partner y gobernanza de credenciales (§8).
- **Semántica:** contrato canónico por producto (`CASO_PENAL/v1`) con validación de esquema.
- **Técnica:** API REST sobre HTTPS, OAuth2, OpenAPI — estándares abiertos.
- **Seguridad (transversal):** mTLS + token ligado al certificado + auditoría encadenada.

### 7.3 Por qué cada mecanismo (mecanismo → principio → fundamento)

| Mecanismo | Principio de seguridad | Fundamento / alineación |
|-----------|------------------------|-------------------------|
| **mTLS** con certificados X.509 de PKI propia | Autenticación **mutua** fuerte, confidencialidad e integridad en tránsito | Certificados digitales (Ley 164 / D.S. 1793); confidencialidad e integridad exigidas por los lineamientos de seguridad |
| **Token OAuth2 ligado al certificado (RFC 8705)** | Autorización + prevención de robo de credenciales (*holder-of-key*) | Defensa en profundidad y mínimo privilegio |
| **Auditoría con hash encadenado + firma** | **No repudio**, integridad y trazabilidad de la evidencia | No repudio (firma digital, Ley 164); trazabilidad y registro exigidos por los lineamientos |
| **Punto único de entrada/salida (ACL)** | Superficie de ataque controlada, gobernanza y registro central | Interoperabilidad **gobernada y auditable** entre entidades |
| **Idempotencia + outbox** | Confiabilidad; no pérdida de eventos | Disponibilidad e integridad transaccional |
| **Estándares abiertos** (HTTP, OAuth2, OpenAPI, JSON, RFC 8705/8785/7519) | Neutralidad tecnológica e interoperabilidad | PIGE/PISLEA: estándares abiertos |

### 7.4 Estándares internacionales aplicados

- **RFC 8705** (OAuth 2.0 mTLS y tokens ligados al certificado), **RFC 6749/6750** (OAuth2), **RFC 7519** (JWT), **RFC 8785** (JSON Canonicalization, para hashes reproducibles).
- **ISO/IEC 27001** (Sistema de Gestión de Seguridad de la Información) — controles de acceso, criptografía, registro y monitoreo.
- **OWASP ASVS** y **API Security Top 10** — buenas prácticas de seguridad en APIs.

---

## 8. Gobernanza y ciclo de vida de credenciales

Cada partner tiene **dos credenciales independientes** con ciclo de vida propio: el
**certificado de cliente** (mTLS) y el **`client_secret`** (OAuth2). La FELCN, como
proveedor del Hub, es la autoridad que las emite, rota y revoca.

### 8.1 Compromiso de un certificado o del client_secret

**Se considera compromiso:** exposición o pérdida de la clave privada
(`.key`/`.p12`), filtración del `client_secret`, o sospecha de uso no autorizado.

- **El partner debe:** notificar de inmediato a la FELCN (contacto técnico / CGII)
  por canal seguro y **dejar de usar** la credencial comprometida.
- **La FELCN debe:** revocar el certificado (retirarlo de la confianza del edge /
  publicarlo en la lista de revocación) y/o **rotar el `client_secret`**; registrar
  el incidente (CGII).
- **Efecto:** con el certificado revocado, **ninguna nueva conexión mTLS** del
  partner es aceptada (falla en el borde). Con el `client_secret` rotado, **no se
  emiten nuevos tokens** con el secreto viejo. Los tokens ya emitidos expiran solos
  (≤ 5 min) y, además, dejan de servir si su certificado fue revocado (el binding
  RFC 8705 falla).

### 8.2 Rotación del client_secret

- **Cadencia recomendada:** cada **90 días** (o según política de la FELCN) y
  **siempre** tras un incidente o cambio de personal con acceso a la credencial.
- **Procedimiento:** la FELCN regenera el `client_secret` en Keycloak y lo entrega
  por canal seguro; el partner lo actualiza en su configuración; el secreto anterior
  queda invalidado. Si se necesita continuidad sin interrupción, se coordina una
  **ventana de transición** antes de invalidar el anterior.
- **Certificado:** tiene su propia vigencia (**825 días**); su renovación se coordina
  con antelación para evitar cortes por expiración.

### 8.3 Suspensión o terminación de la interoperabilidad (offboarding)

La FELCN **puede suspender o dar de baja** la interoperabilidad de la Fiscalía —o de
cualquier partner— cuando se presente alguno de estos supuestos:

- Fin del **convenio/acuerdo** o del propósito de la integración.
- **Incumplimiento** de las condiciones de uso (uso indebido, exceso de límites,
  actividad anómala o abusiva).
- **Incidente de seguridad** no remediado o compromiso reiterado.
- **Requerimiento legal** o instrucción de autoridad competente.
- **Certificado expirado** sin renovación, o credenciales inactivas.

**Mecanismos de corte** (en capas; cualquiera corta el acceso, se aplican en conjunto):

1. **Revocar el certificado** del partner en el edge (mTLS) → no pasa ni la conexión TLS.
2. **Deshabilitar el client** en Keycloak → no se emiten nuevos tokens.
3. **Suspender la suscripción** del partner en el gateway → `403` aunque presentara un token válido.

Toda suspensión/revocación queda **auditada** (quién, cuándo, motivo), consistente
con la trazabilidad exigida a las plataformas de interoperabilidad del Estado.

---

## 9. Referencias

- `docs/adr/ADR-0001-interop-hub.md` — reglas no negociables del hub.
- `docs/adr/0003-threat-model.md` — modelo de amenazas.
- `deploy/partner-delivery/GUIA_INTEGRACION.md` — guía técnica para el partner (Fiscalía).
- **RFC 8705** — OAuth 2.0 Mutual-TLS Client Authentication and Certificate-Bound Access Tokens.
- **Ley N° 164**, **D.S. 1793**, **D.S. 2514**, **D.S. 3251** — marco normativo boliviano de TIC / gobierno electrónico / interoperabilidad.
- Lineamientos de interoperabilidad y de seguridad de la información — **AGETIC**.
