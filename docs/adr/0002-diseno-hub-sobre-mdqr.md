# ADR-0002 — Diseño del Hub de Interoperabilidad sobre la Base MDQR

Estado: Propuesto · Fecha: 2026-06-22

## Contexto

El ADR-0001 fija las **decisiones de arquitectura** del hub de interoperabilidad
(doble capa de identidad mTLS + OAuth2 con RFC 8705, auditoría inmutable con
hash-chain y Vault Transit, outbox para facturación, ACL outbound, contratos
OpenAPI versionados) pero deja abierto **cómo se materializan esas decisiones
sobre el código heredado** del middleware `middleware-decode-qr` (group
`bo.com.sintesis.mdqr`).

Hoy existe un monorepo Gradle con cuatro módulos funcionando (gateway WebFlux,
ms-auth MVC/IAM, ms-base MVC/QR-decode, audit-commons librería) más un frontend
Angular. El hub **no es un proyecto nuevo en verde**: es una evolución aditiva de
esa base. Este ADR responde tres preguntas que el ADR-0001 no resuelve:

1. ¿Qué rol del hub asume cada módulo existente, y qué se crea nuevo?
2. ¿Qué de lo que ya hay se reutiliza tal cual, qué se amplía y qué se reemplaza?
3. ¿En qué orden se implementa para que cada fase sea desplegable sin romper la
   funcionalidad QR actual ni los contratos `mdqr-*` ya en uso?

La restricción transversal es de **decoupling diferido** (ADR-0001, nota final):
nombres heredados (`mdqr-*`, paquetes `bo.com.sintesis.mdqr.*`, tablas
`middleware_*` cuando existan, paths de Vault `mdqr-auth`/`mdqr-decode`, nombres
de Consul `mdqradminservice`/`mdqrbaseservice`, realms `mdqr-admin`/`mdqr-partner`)
son **inamovibles** en este ADR. Renombrarlos es trabajo posterior documentado
aparte.

### Estado verificado de la base (lo que existe hoy, leído del código)

- **mdqr-gateway**: dos cadenas de seguridad por `@Order` (partner Order=1 sobre
  `/partner/**` + `/oauth2/token`; admin Order=2 sobre el resto), decoders JWT
  programáticos por realm, discovery locator con expresión multi-tenant
  (`TENANT_ID`), filtros globales `IpWhitelistFilter` (Order=1), `RateLimitFilter`
  (Order=3, sliding window Redis por `azp`), `DomainWhitelistFilter`,
  `RequestIdFilter`, `AccessLogFilter`. Proxy del token endpoint partner. **No
  termina mTLS hoy** (no hay configuración de client-auth ni filtro de cert).
- **mdqr-ms-auth**: fachada sobre Keycloak Admin API (`keycloak-admin-client`),
  RBAC propio (users/roles/menus/actions/permissions), tabla `audit_log` (entidad
  `AuditLog`) y `InProcessSink` que persiste auditoría sin hop HTTP. Schema
  `admin`. Polling de eventos LOGIN/LOGOUT de Keycloak.
- **mdqr-ms-base**: núcleo QR (decode ZXing + decrypt RSA/BouncyCastle), ciclo de
  vida de certificados (entidad `Certificate`/`CertificateVersion`,
  `FinancialEntity`), cliente `TuxedoApiClient` (RestClient síncrono a la Go API,
  con timeouts pero **sin resilience4j**), tabla `decode_log` (entidad
  `DecryptionLog`). **Seguridad en MODO DESARROLLO**: `anyRequest().permitAll()`,
  JWT comentado, `@PreAuthorize` deshabilitado; `KeycloakRealmRoleConverter` ya
  escrito y listo. El `DecryptionLog` ya tiene columnas `keycloak_client_id`,
  `mtls_cert_cn`, `qr_string_hash`, `external_reference`, `metadata` (jsonb): la
  base ya está parcialmente preparada para auditoría tipo hub.
- **mdqr-audit-commons**: publisher fire-and-forget (buffer en memoria + retry en
  worker dedicado) detrás de una costura `AuditEventSink` (`RemoteHttpSink` /
  `InProcessSink` / futuro). `AuditEventDto` con metadatos de transacción
  (módulo, endpoint, status, duración, roles, reqId, tenant) pero **sin
  `request_hash`/`response_hash`, sin `prev_hash`, sin firma/cifrado**. El
  `AuditAspect` solo se activa si hay servlet API en classpath (consumers
  reactivos como el gateway usan el publisher directamente).

## Mapeo de roles del hub a módulos existentes

| Rol del hub (ADR-0001)   | Módulo que lo asume                     | Estrategia |
|--------------------------|-----------------------------------------|------------|
| Edge / Gateway           | `mdqr-gateway`                          | **Ampliar**: añadir terminación mTLS, validación RFC 8705, rutas outbound. Conservar cadenas/filtros/rutas actuales. |
| Servicio de identidad    | `mdqr-ms-auth`                          | **Ampliar**: registro de partners, orquestación PKI Vault, gestión de suscripciones/scopes, sincronización a Redis (whitelist/rate-limit). |
| Núcleo del hub (inbound) | `mdqr-ms-base`                          | **Ampliar**: QR pasa a ser *un* producto inbound; añadir más rutas de negocio inbound bajo el mismo patrón. |
| Núcleo del hub (outbound)| `mdqr-ms-base` (fase 1) → módulo propio | **Ampliar primero, extraer después**: framework de adaptadores ACL dentro de ms-base; extraer a `mdqr-ms-outbound` si crece. |
| Librería de auditoría    | `mdqr-audit-commons`                    | **Ampliar**: canonicalización RFC 8785, hash SHA-256 de payloads, hash-chain, firma/cifrado Vault Transit. Aditivo sobre el `AuditEventDto` actual. |
| Servicio de facturación  | (nuevo) `mdqr-ms-billing`               | **Nuevo**: relay del outbox + medición + facturación. Inicia como relay dentro de ms-base, se extrae cuando haya señal de escalado independiente. |
| Frontend / portal        | `mdqr-frontend` (`apps/admin` + nueva `apps/portal`) | **Ampliar**: nueva app `portal` para partners; reutiliza el workspace y keycloak-angular. |

Principio rector: **cada rol nuevo aterriza primero en el módulo existente más
cercano por ownership de datos**, y solo se extrae a un módulo nuevo cuando hay
una necesidad real (escalado diferencial, equipo independiente, frontera de datos
distinta). Esto respeta el principio "monolito modular antes que microservicios"
y "los datos definen las fronteras".

## Por módulo: qué se reutiliza y qué es nuevo

### mdqr-gateway — Edge / mTLS / Enrutamiento

**Qué existe hoy.** Punto de entrada único en `:8080`. Dos `SecurityWebFilterChain`
por `@Order`: `partnerSecurityChain` (Order=1, matchea `/partner/**` y
`/oauth2/token`, valida JWT del realm `mdqr-partner` con `partnerJwtDecoder`) y
`adminSecurityChain` (Order=2, resto, realm `mdqr-admin`, JWT + OIDC browser).
Discovery locator de Consul con aislamiento por `TENANT_ID`. Filtros globales
ordenados: `IpWhitelistFilter` (1) y `RateLimitFilter` (3) ambos resuelven el
partner por el claim `azp` del JWT y consultan Redis (`whitelist:ip:{azp}`,
`ratelimit:{azp}`), con `.onErrorResume` para no caer si Redis no responde.
Proxy del token endpoint partner reescribiendo `/oauth2/token` →
`/realms/mdqr-partner/protocol/openid-connect/token`. Retry solo en GET.

**Qué se agrega para el hub.**

1. **Terminación mTLS en el edge.** El gateway (o el reverse-proxy/ingress que lo
   antecede en prod) negocia TLS mutuo. El certificado de cliente se valida contra
   la CA emisora de la PKI de Vault. El partner se identifica por CN/SAN del cert.
   Decisión de despliegue: dado que el gateway es WebFlux sobre Netty, el mTLS
   puede terminarse (a) en el propio Netty del gateway, o (b) en un proxy delante
   (Envoy/NGINX) que reenvía el cert en `X-Client-Cert`/headers estándar. **Se
   recomienda (b) para producción** (rotación de CA y CRL sin reiniciar la JVM) y
   (a) solo para entornos de desarrollo. El edge debe pasar el cert (o su huella)
   aguas abajo en un header firmado/confiable para que ms-base lo registre
   (`mtls_cert_cn` ya existe en `decode_log`).

2. **Validación RFC 8705 (token cert-bound).** Nuevo `WebFilter` o validador
   adicional en `partnerSecurityChain`: comparar el `cnf.x5t#S256` (thumbprint
   SHA-256 del cert de cliente) del JWT contra el thumbprint del cert mTLS
   presentado. Si no coinciden → 401. Keycloak emite el claim `cnf` nativamente al
   habilitar mTLS holder-of-key en el client `unilink-api`. Este filtro se inserta
   **solo en la cadena partner**, no toca la cadena admin.

3. **Nuevas rutas outbound.** Las apps internas llaman al hub para consumir
   terceros. Se añade un prefijo de ruta nuevo (p. ej. `/outbound/v1/**`) que el
   gateway enruta a `lb://mdqrbaseservice` (fase 1) reescribiendo a la API outbound
   interna. Esta ruta vive bajo una cadena de seguridad **interna** (realm
   `mdqr-admin` o un realm/scope de servicio): las apps internas se autentican como
   clientes de servicio, no como partners. Se reutiliza el patrón de
   `partner-qr-api` (RewritePath) para definirla.

4. **Resolución de partner enriquecida.** Hoy `IpWhitelistFilter`/`RateLimitFilter`
   usan `azp`. Para el hub, el partner lógico se resuelve combinando `azp` +
   suscripción activa (sincronizada por ms-auth a Redis). No cambia la firma de los
   filtros; cambia qué claves de Redis lee y quién las escribe (ms-auth).

**Restricciones (lo que se conserva).**
- Los nombres de ruta `/partner/v1/**`, `/oauth2/token`, `/services/**`,
  `/v3/api-docs/*` **no cambian**: hay partners y SPA que dependen de ellos.
- El orden de las cadenas (partner Order=1, admin Order=2) **no se altera**; las
  nuevas rutas outbound o se incluyen en una cadena nueva con `@Order` intermedio
  (p. ej. Order=2 desplazando admin a Order=3) o se añaden a la admin existente.
  La regla `anyExchange().denyAll()` de cada cadena debe mantenerse.
- Los filtros globales existentes y su orden (1 y 3) se conservan; el filtro RFC
  8705 se inserta como filtro de cadena (no global) para afectar solo a partner.
- El decoder JWT sigue siendo programático desde `ApplicationProperties` (no
  declarativo).

### mdqr-ms-auth — Identidad / Partners / PKI

**Qué existe hoy.** IAM y RBAC propios sobre Keycloak Admin API
(`keycloakAdminClient` con client_credentials del client `mdqradminservice`).
Entidades en schema `admin`: usuarios/roles/menus/actions/permissions y
`audit_log`. `InProcessSink` que persiste auditoría directo. Polling de eventos
de Keycloak. Se registra en Consul como `mdqradminservice`.

**Qué se agrega para el hub.**

1. **Registro de partners.** Nuevo agregado de dominio "partner" y su ciclo de
   vida (alta, suspensión, baja). Reutiliza la fachada Keycloak existente para
   crear/gestionar el client M2M del partner en el realm `mdqr-partner`
   (hoy esto se hace por script `create-partner.sh`; se promueve a API
   gobernada). El script existente sigue válido para bootstrap.

2. **Orquestación de PKI Vault.** Nuevo servicio que, al activar un partner, pide
   a Vault (motor `pki`) la emisión de un certificado de cliente de vida corta,
   ligado al partner, y registra su serial/thumbprint. Gestiona rotación y
   revocación (CRL). ms-auth ya habla con infra de seguridad (Keycloak); añadir
   Vault PKI es coherente con su rol de "servicio de identidad". El thumbprint
   emitido es el que Keycloak usará para el binding RFC 8705 del client.

3. **Gestión de suscripciones y scopes.** Qué productos puede invocar cada partner
   (mapeado a scopes OAuth2 y a rutas inbound). Nuevo agregado "suscripción".

4. **Sincronización a Redis para el edge.** ms-auth escribe a Redis las claves que
   los filtros del gateway ya leen: `whitelist:ip:{partnerId}`,
   `ratelimit:config:{partnerId}`. Así el panel administra políticas y el edge las
   aplica sin acoplamiento de código (dependencia en una sola dirección:
   ms-auth → Redis ← gateway).

**Nuevas tablas (schema `admin`, nombrado coherente con lo existente).**
Descripción de forma, no DDL:
- `partner` — identidad lógica del tercero: id, nombre, estado, `keycloak_client_id`
  (azp), datos de contacto, fechas de auditoría.
- `partner_certificate` — certs PKI emitidos a un partner: id, partner_id, serial,
  thumbprint SHA-256 (`x5t#S256`), not_before/not_after, estado (active/revoked),
  vault_pki_path.
- `partner_subscription` — suscripción a productos: id, partner_id, product_code,
  scope, estado, límites (rate limit, IPs permitidas que luego se sincronizan a
  Redis), vigencia.

Estas tablas son aditivas; no tocan `audit_log` ni el RBAC existente.

**Nuevas APIs internas (prefijo `/admin`, accesibles vía
`/services/mdqradminservice/admin/**`).**
- `/admin/partners` — CRUD del ciclo de vida de partners.
- `/admin/partners/{id}/certificates` — emisión/rotación/revocación PKI.
- `/admin/partners/{id}/subscriptions` — alta/baja de productos y políticas.
- `/admin/products` — catálogo de productos inbound (para el portal y la consola).

### mdqr-ms-base — Núcleo inbound/outbound

**Qué existe hoy.** Núcleo de negocio QR: `QrResource` (`POST /api/qr/decode`,
`/decode/file`, `GET /api/qr/audits`), `CertificateResource` (CRUD + lifecycle de
certs), `QrDecryptionService`/`CryptoService` (RSA/BouncyCastle),
`TuxedoApiClient` (RestClient síncrono a la Go API en `:5050`, con timeouts pero
sin resilience4j ni caché). Tablas en schema `public`: `certificate`,
`certificate_version`, `financial_entity`, `decode_log`, `certificate_audit_log`,
`admin_audit_log`. Seguridad en MODO DESARROLLO (`permitAll`), `KeycloakRealmRoleConverter`
ya escrito. El `decode_log` ya captura `keycloak_client_id`, `mtls_cert_cn`,
`qr_string_hash` y `external_reference`: hay diseño previo orientado a hub.

**Qué se agrega para el hub.**

1. **QR como primer producto inbound; framework para más.** El decode QR pasa a
   ser un producto inbound más. Las nuevas APIs de negocio inbound se modelan bajo
   `/api/<producto>/**` y se exponen vía gateway como `/partner/v1/<producto>/**`
   reutilizando el patrón de RewritePath existente. No se rompe `/api/qr/**`.

2. **Framework de adaptadores outbound (ACL / Facade).** Nuevo paquete
   `bo.com.sintesis.mdqr.base.outbound` con:
   - Un **contrato canónico interno** por capability (request/response neutrales,
     independientes del proveedor).
   - Una interfaz `OutboundAdapter` (o `ProviderAdapter`) que cada proveedor
     externo implementa: traduce canónico ↔ formato del proveedor, gestiona
     credenciales desde Vault, aplica resiliencia.
   - **resilience4j** (nueva dependencia en `build.gradle` de ms-base) con
     timeout/retry/circuit-breaker/bulkhead por adaptador. El `TuxedoApiClient`
     actual se reencuadra como el primer adaptador de referencia (Tuxedo es de
     hecho un proveedor externo) y se le añade resiliencia.
   - **Caché Redis** opcional por capability (ya hay Redis configurado en ms-base).

3. **Tabla outbox + idempotencia.** En la misma transacción de negocio (inbound u
   outbound) se escribe el registro de auditoría/medición y un evento `outbox`. El
   relay de facturación lo consume. Garantía at-least-once; el consumidor
   deduplica por `idempotency_key`.

4. **Habilitar seguridad real (pendiente del CLAUDE.md).** Activar JWT +
   `KeycloakRealmRoleConverter` + `@PreAuthorize` (`API_CLIENT`, `ADMIN`,
   `AUDITOR`). Hasta entonces la protección la sigue dando el gateway (ver
   Restricciones).

**Nuevas tablas (schema `public`).**
- `outbox_event` — id, aggregate_type, aggregate_id, event_type, payload (jsonb),
  `idempotency_key` (único), status (PENDING/SENT/FAILED), attempts, created_date,
  processed_date. Particionable por partner si la cadena de auditoría se particiona.
- `outbound_call_log` — auditoría específica de llamadas outbound: id, partner/app
  origen, provider_code, capability, request_hash, response_hash, status,
  latencia, unidades facturables. (Puede unificarse con la auditoría canónica de
  audit-commons; ver más abajo).
- `provider` / `provider_credential_ref` — catálogo de proveedores externos y
  referencia (no el secreto) a su credencial en Vault.

**Contratos canónicos (forma, no código).** Cada capability inbound y outbound
define un par request/response canónico estable y versionado, desacoplado del
formato del proveedor o del QR concreto. Ver sección "Contratos canónicos".

### mdqr-audit-commons — Librería de auditoría extendida

**Qué existe hoy.** `AuditEventDto` (record con metadatos de transacción: módulo,
optionCode, usuario, roles, ip, userAgent, serviceName, httpMethod, endpoint,
responseStatus, durationMs, details jsonb, tenantId, reqId). `AuditEventPublisher`
fire-and-forget (buffer `LinkedBlockingQueue` + worker + retry con backoff) detrás
de la costura `AuditEventSink`. Sinks: `RemoteHttpSink` (POST a admin-service con
token de service account) e `InProcessSink` (en ms-auth). `AuditAspect` AOP por
`@Auditable`, activado solo con servlet API en classpath. **No hay hash de
payloads, ni prev_hash, ni firma/cifrado.**

**Qué se agrega para el hub.**

1. **Canonicalización RFC 8785 (JCS).** Nuevo componente `JsonCanonicalizer` que
   produce la forma canónica determinista del request y del response antes de
   hashear. Reproducibilidad para conciliación.

2. **Hash SHA-256 de payloads.** Nuevos campos en `AuditEventDto`: `requestHash`,
   `responseHash` (hex SHA-256 sobre la forma canónica). Campos **opcionales**
   (`@JsonInclude(NON_NULL)` ya está): los consumidores actuales que no los pueblan
   siguen funcionando — cambio aditivo y retrocompatible.

3. **Hash-chain tamper-evident.** Nuevo campo `prevHash` + `recordHash`. El
   `recordHash` se calcula sobre los campos canónicos del registro **incluyendo**
   `prevHash`. La cadena se construye en el **sink que persiste** (no en el
   publisher fire-and-forget), porque requiere lectura del último hash y escritura
   serializada. Decisión de concurrencia (ADR-0001): **particionar la cadena por
   partner** (cada partner tiene su propia secuencia `prev_hash`) para evitar un
   único punto de serialización global; alternativamente serializar por lock/tabla
   si el volumen lo permite.

4. **Firma y cifrado con Vault Transit.** Nuevo componente que firma el
   `recordHash` con una clave Transit y, cuando el requisito legal exige reproducir
   contenido, cifra el payload canónico (envelope encryption) en lugar de
   guardarlo en claro. Vault Transit es `compileOnly`/opcional: se activa por
   propiedad (`audit.integrity.enabled`, `audit.transit.*`) para no forzar Vault en
   consumers que no lo necesiten — mismo patrón que ya usa la librería con
   security/micrometer `compileOnly`.

**Impacto en consumidores.**
- **ms-auth (`InProcessSink`)**: la entidad `audit_log` gana columnas
  `request_hash`, `response_hash`, `prev_hash`, `record_hash`, `signature`
  (nullable, vía Liquibase). El sink calcula la cadena al persistir. Cambio
  aditivo; los registros legacy quedan con esos campos en null.
- **ms-base**: pasa a usar audit-commons (hoy tiene su propia `AuditService` y
  `decode_log`). La integración del hash-chain de transacciones de negocio se hace
  vía la librería; `decode_log` puede conservarse como log específico de QR y la
  auditoría canónica con hash vivir en la tabla común de auditoría.
- **gateway (reactivo)**: sigue usando solo el publisher; puede emitir el evento
  de transacción con los hashes ya calculados en el edge si se decide hashear allí
  (decisión abierta: hashear en el edge da el payload tal cual viajó; hashear en el
  núcleo da el payload de negocio. Recomendación: hashear en el **núcleo** ms-base,
  que es donde se conoce el contrato canónico).

### Nuevo: servicio/módulo de facturación (relay de outbox)

**Por qué evaluar nuevo vs. existente.** El relay del outbox + medición +
facturación es un rol claramente distinto (consume eventos, agrega, factura), pero
**no justifica un microservicio nuevo desde el día uno**: comparte la base de datos
del outbox (`mdqr_decode`/schema `public`) y su volumen inicial es bajo. Aplicando
YAGNI y "monolito modular primero":

- **Fase inicial**: el relay vive **dentro de ms-base** como un componente
  `@Scheduled` (paquete `bo.com.sintesis.mdqr.base.billing`) que lee `outbox_event`
  PENDING, los proyecta a una tabla de medición y los marca SENT. Misma TX de
  negocio escribe el outbox; el relay corre desacoplado. Esto evita un módulo,
  un deployment y una conexión DB extra mientras el dominio se estabiliza.
- **Extracción a `mdqr-ms-billing`** (módulo nuevo, patrón `mdqr-*`, Consul
  `mdqrbillingservice`): se hace cuando aparezca una señal real — facturación con
  ciclo/SLA propio, equipo separado, o necesidad de escalar el relay
  independientemente del núcleo. Hereda el patrón de los otros ms (Liquibase
  manual, Consul, Vault namespace propio o `mdqr-decode`).

**Responsabilidad mínima viable.** Leer outbox at-least-once, deduplicar por
`idempotency_key`, acumular unidades facturables por partner/producto/periodo,
exponer consulta de consumo. Sin generación de facturas legales en MVP (eso es un
producto aparte).

**Nombre sugerido.** `mdqr-ms-billing` (cuando se extraiga). Mientras tanto,
paquete `…base.billing` dentro de ms-base.

## Contratos canónicos

Descripción de forma (campo · tipo · descripción). No es código ni esquema final;
fija la intención para que otros agentes lo implementen.

### Request inbound canónico
- `requestId` · string · id único de la transacción (correlación, viene del edge).
- `partnerId` · string · identidad lógica del partner (resuelta desde `azp` + suscripción).
- `productCode` · string · producto/capability invocado (p. ej. `qr.decode`).
- `payload` · objeto · cuerpo de negocio neutral del producto.
- `receivedAt` · timestamp · instante de recepción en el hub.
- `clientCertThumbprint` · string · `x5t#S256` del cert mTLS (no-repudio).

### Response canónico
- `requestId` · string · eco del request para correlación.
- `status` · enum · OK / BUSINESS_ERROR / TECHNICAL_ERROR.
- `payload` · objeto · resultado de negocio neutral (o `null` si error).
- `error` · objeto · `{ code, message }` cuando aplica (ProblemDetail-compatible).
- `processedAt` · timestamp · instante de respuesta.

### Evento de auditoría canónico (extensión de `AuditEventDto`)
Campos actuales (se conservan): `eventTime`, `eventType`, `module`, `optionCode`,
`userId`, `username`, `roles`, `ipAddress`, `userAgent`, `serviceName`,
`httpMethod`, `endpoint`, `responseStatus`, `durationMs`, `details`, `tenantId`,
`reqId`. Campos nuevos (opcionales, retrocompatibles):
- `direction` · enum · INBOUND / OUTBOUND.
- `partnerId` · string · partner lógico.
- `productCode` · string · producto/capability.
- `requestHash` · string · SHA-256 hex de la forma canónica del request.
- `responseHash` · string · SHA-256 hex de la forma canónica del response.
- `prevHash` · string · hash del registro anterior de la cadena (por partner).
- `recordHash` · string · SHA-256 del registro actual incluyendo `prevHash`.
- `signature` · string · firma Vault Transit del `recordHash`.
- `billableUnits` · number · unidades facturables de la transacción.

### Evento outbox (tabla `outbox_event`)
- `id` · long · PK.
- `aggregateType` · string · tipo de agregado origen (p. ej. `transaction`).
- `aggregateId` · string · id del agregado.
- `eventType` · string · tipo de evento (p. ej. `transaction.billable`).
- `idempotencyKey` · string · clave única para deduplicar en el relay.
- `payload` · jsonb · datos de medición (partnerId, productCode, billableUnits, occurredAt).
- `status` · enum · PENDING / SENT / FAILED.
- `attempts` · int · reintentos del relay.
- `createdDate` · timestamp · escrito en la TX de negocio.
- `processedDate` · timestamp · cuando el relay lo marcó SENT.

## Plan de implementación por fases

Cada fase es desplegable de forma independiente y **no rompe** lo anterior.

**Fase 0 — Cimientos de auditoría (audit-commons).**
Implementa `JsonCanonicalizer` (RFC 8785) y los campos `requestHash`/`responseHash`
opcionales en `AuditEventDto`. Sin hash-chain todavía. Módulo: `mdqr-audit-commons`.
Dependencias: ninguna. Riesgo cero (campos opcionales, consumers no afectados).

**Fase 1 — Hash-chain + integridad.**
Añade `prevHash`/`recordHash`/`signature`, particionado por partner, e integración
Vault Transit (activable por propiedad). Migración Liquibase aditiva de `audit_log`
en ms-auth. Módulos: `mdqr-audit-commons`, `mdqr-ms-auth`. Depende de Fase 0.

**Fase 2 — Identidad de partners y PKI (ms-auth).**
Tablas `partner`/`partner_certificate`/`partner_subscription`, APIs
`/admin/partners/**`, orquestación PKI Vault, sincronización de políticas a Redis
(`whitelist:ip:*`, `ratelimit:config:*`). Módulo: `mdqr-ms-auth`. Depende de Fase 1
(para auditar las operaciones de identidad con integridad). El gateway ya consume
esas claves Redis sin cambios.

**Fase 3 — mTLS + RFC 8705 en el edge (gateway).**
Terminación mTLS (proxy delante en prod), filtro de binding `cnf.x5t#S256` en
`partnerSecurityChain`, propagación del cert aguas abajo. Habilitar
holder-of-key en el client `unilink-api` de Keycloak. Módulo: `mdqr-gateway` +
config Keycloak. Depende de Fase 2 (los certs los emite ms-auth/PKI).

**Fase 4 — Outbox + relay de medición (ms-base).**
Tabla `outbox_event`, escritura en la TX de negocio del decode QR (primer
productor), relay `@Scheduled` en `…base.billing`, deduplicación por
`idempotency_key`, tabla de medición. Módulo: `mdqr-ms-base`. Depende de Fase 1
(misma TX escribe auditoría con hash + outbox).

**Fase 5 — Framework outbound (ACL) en ms-base.**
Paquete `…base.outbound`, contrato canónico, `OutboundAdapter`, resilience4j,
caché Redis. Reencuadrar `TuxedoApiClient` como primer adaptador con resiliencia.
Nueva ruta `/outbound/v1/**` en el gateway. Módulos: `mdqr-ms-base`,
`mdqr-gateway`. Depende de Fases 1 y 4 (auditoría + outbox para outbound).

**Fase 6 — Endurecer ms-base + portal de partners.**
Habilitar JWT/RBAC real en ms-base (`KeycloakRealmRoleConverter` + `@PreAuthorize`),
retirando la dependencia exclusiva del gateway para autorización. Nueva app
`apps/portal` en `mdqr-frontend`. Contratos OpenAPI versionados por producto.
Módulos: `mdqr-ms-base`, `mdqr-frontend`. Depende de Fase 3.

**Fase 7 (condicional) — Extraer `mdqr-ms-billing`.**
Solo si hay señal de escalado/equipo independiente. Mueve el relay de `…base.billing`
a un módulo `mdqr-*` propio con su Consul/Vault. Depende de Fase 4.

## Restricciones arquitectónicas

1. **No renombrar nada heredado.** Módulos `mdqr-*`, paquetes
   `bo.com.sintesis.mdqr.*`, realms `mdqr-admin`/`mdqr-partner`, nombres de Consul
   `mdqradminservice`/`mdqrbaseservice`, namespaces de Vault `mdqr-auth`/`mdqr-decode`,
   tablas `middleware_*`/`decode_log`/`audit_log`/`certificate*`. Los módulos nuevos
   siguen el patrón `mdqr-*`. El renombrado es decoupling posterior (ADR-0001).
2. **No romper contratos en uso.** Rutas `/partner/v1/**`, `/oauth2/token`,
   `/services/**`, `/api/qr/**`, `/v3/api-docs/*` se conservan. Las APIs nuevas son
   aditivas (nuevos prefijos / nuevos paths).
3. **No reemplazar las cadenas de seguridad del gateway.** Se amplían (filtro RFC
   8705 en la cadena partner, eventual cadena/orden para outbound). Se mantiene
   `anyExchange().denyAll()` por cadena y los decoders programáticos.
4. **Cambios en `AuditEventDto` solo aditivos y opcionales.** Ningún consumer
   existente debe romperse por nuevos campos. Migraciones Liquibase de `audit_log`
   nullable.
5. **Seguridad de ms-base sigue siendo responsabilidad del gateway** hasta
   completar la Fase 6 (habilitar JWT/RBAC en ms-base). No exponer ms-base
   (`:8081`) directo en prod mientras tanto.
6. **Hash-chain se construye en el sink que persiste, no en el publisher**
   fire-and-forget. La escritura de la cadena se serializa por partición (partner)
   para no degradar throughput global.
7. **Dependencias en una sola dirección.** gateway → (Redis ← ms-auth);
   ms-base/ms-auth → audit-commons; apps internas → gateway → ms-base (nunca app
   interna → tercero directo, ni gateway → DB).
8. **El relay de outbox no genera facturas legales en MVP** ni asume entrega
   exactly-once: at-least-once + idempotencia.
9. **Vault Transit y PKI activables por propiedad.** No forzar Vault en consumers
   o entornos (p. ej. perfil `local`) que no lo usen, igual que hoy
   `vault.enabled=false` en local.

## Consecuencias

**Positivas.**
- Reutilización máxima de la base: el `decode_log` ya prevé `mtls_cert_cn`/
  `qr_string_hash`, los filtros del gateway ya resuelven partner por `azp` y leen
  Redis, la librería de auditoría ya tiene la costura `AuditEventSink` extensible y
  el publisher con retry. El hub es en gran medida *cableado y ampliación*, no
  reescritura.
- Cada decisión es aditiva y desplegable por fases: bajo riesgo de regresión sobre
  la funcionalidad QR en producción.
- El QR queda reencuadrado como el primer producto inbound, validando el
  framework con un caso real antes de generalizar.
- Diferir `mdqr-ms-billing` y el módulo outbound evita complejidad operativa
  prematura (microservicios, deployments, conexiones DB) sin cerrar la puerta a
  extraerlos.

**Negativas / costos.**
- ms-base acumula responsabilidades (inbound + outbound + relay de facturación)
  durante varias fases: deuda deliberada y documentada, con plan de extracción
  (Fases 5 y 7). Debe vigilarse el acoplamiento entre paquetes.
- El hash-chain particionado por partner añade complejidad (gestión de la cabeza de
  cada cadena, verificación) y obliga a serializar escrituras por partición.
- La terminación mTLS recomendada en un proxy delante del gateway añade una pieza
  de infra (Envoy/NGINX) y la operación de su CA/CRL — coste no presente hoy.
- Habilitar JWT/RBAC en ms-base (Fase 6) es trabajo pendiente real: hasta entonces
  persiste el riesgo de que un acceso directo a `:8081` saltee la autorización del
  gateway (mitigado por red, no por código).
- audit-commons gana dependencias opcionales (Vault Transit) y lógica de
  canonicalización/firma: más superficie a mantener en una librería compartida por
  todos los módulos.

## Alternativas descartadas

- **Crear módulos nuevos para identidad-de-partners, outbound y facturación desde
  el inicio.** Descartado por YAGNI y coste operativo: el ownership de datos
  (identidad → schema `admin`; outbound/outbox → schema `public`) encaja en los ms
  existentes; se extrae solo ante señal real de escalado/equipo.
- **Terminar mTLS dentro del Netty del gateway en producción.** Descartado como
  default por la rigidez en rotación de CA/CRL (requiere reinicio); se reserva para
  desarrollo. En prod se prefiere proxy delante.
- **Reescribir la auditoría como servicio/stream nuevo (p. ej. Kafka) para el
  hash-chain.** Descartado: la costura `AuditEventSink` + publisher con retry ya
  resuelve el desacople; introducir un broker es complejidad no justificada por el
  volumen actual y rompería el modo `in-process` de ms-auth.
- **Hashear payloads en el edge (gateway).** Descartado como default: el edge no
  conoce el contrato canónico de negocio; hashear en el núcleo (ms-base) da hashes
  estables y conciliables. Se reconsiderará si se requiere hash del byte-stream tal
  cual viajó.
