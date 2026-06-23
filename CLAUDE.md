# CLAUDE.md — MDQR (Middleware Decode QR)

Guía para agentes de Claude Code que trabajen en este monorepo. Documenta cada
módulo, la infraestructura y los gotchas conocidos para tomar decisiones de
implementación sin tener que leer todo el código.

> **Idioma**: responder siempre en español.
> **Comandos**: nunca ejecutar comandos automáticamente — entregar los comandos
> para que el usuario los corra.

---

## Descripción del proyecto

`middleware-decode-qr` (group `bo.com.sintesis.mdqr`) es un middleware que
**desencripta y decodifica códigos QR cifrados** (RSA + certificados por entidad
financiera) y expone un panel de administración. Está compuesto por un API
Gateway, dos microservicios Spring Boot, una librería de auditoría compartida y
un frontend Angular multi-app.

- **Monorepo Gradle multi-módulo** (Groovy DSL, `settings.gradle`).
- **Java 25** (toolchain), **Spring Boot 4.0.5**, **Spring Cloud 2025.1.1**.
- Versionado: versión central `projectVersion=1.0` + patch independiente por
  módulo en `gradle.properties` (resultando `1.0.x`).
- Build de imágenes con **Jib** (base `eclipse-temurin:25-jre-alpine`,
  registry `cr.sintesis.com.bo/mdqr-dev`) y soporte **GraalVM native**.
- Repos Maven: Maven Central + Nexus de Síntesis (`nexus.sintesis.com.bo`,
  requiere `NEXUS_USERNAME`/`NEXUS_PASSWORD`).

---

## Hub de interoperabilidad

El proyecto evolucionó a un **hub de interoperabilidad con terceros**
(instituciones, negocios, partners, clientes), documentado en
`docs/adr/ADR-0001-interop-hub.md`. El hub es el **único punto de entrada y de
salida** de información entre la empresa y los terceros, en dos direcciones:

- **Inbound**: los terceros consumen las APIs de negocio internas de la empresa
  a través del hub.
- **Outbound**: las aplicaciones internas consumen APIs de terceros **siempre a
  través del hub, nunca directamente**.

### Reglas no negociables

1. **mTLS + RFC 8705 (token enlazado al certificado)**: todo partner debe
   presentar un **certificado de cliente** emitido por la **PKI de Vault** (motor
   `pki`). El access token (OAuth2 client_credentials, Keycloak) queda
   **criptográficamente ligado al certificado** (OAuth 2.0 mTLS, RFC 8705): un
   token robado es inútil sin la clave privada del partner. Sin certificado
   válido **mapeado a una suscripción activa** → rechazo en el edge.

2. **Auditoría + hash de toda transacción**: cada transacción (inbound y
   outbound) genera un registro de auditoría con **hash SHA-256 del request y del
   response**, previa **canonicalización (JSON Canonicalization Scheme, RFC 8785)**
   para que los hashes sean reproducibles en conciliaciones. Los registros forman
   una **cadena de hashes** (`prev_hash`, tamper-evident) como evidencia de
   integridad, se **firman** con clave gestionada por Vault y, si se requiere
   almacenar el payload, se **cifran con Vault Transit** (nunca en claro).

3. **Outbox para facturación**: el registro de auditoría y el evento de
   facturación se escriben en la **misma transacción** de negocio sobre una tabla
   `outbox`. Un relay consume el outbox y alimenta la medición/facturación, con
   garantía **at-least-once + idempotencia por `idempotency_key`**. **Nunca
   perder un evento facturable** ni desalinear lo auditado de lo facturado.

4. **Apps internas nunca llaman APIs externas directo**: toda llamada a un
   tercero pasa por el hub (**Anti-Corruption Layer / Facade**). El hub mantiene
   un **adaptador por proveedor externo** que traduce el contrato canónico
   interno al del proveedor, gestiona **credenciales en Vault**, aplica
   **resiliencia (resilience4j: timeout, retry, circuit breaker, bulkhead)**,
   **caché (Redis cuando aplica)**, auditoría y medición. Agregar un proveedor =
   nuevo adaptador, cero cambios en las apps internas.

5. **Secretos en Vault, idempotencia**: **todas** las credenciales de terceros se
   almacenan en **Vault** (nunca en config ni código). Las operaciones expuestas
   deben ser **idempotentes** (usar `idempotency_key`).

> **Nombres heredados — NO renombrar**: todos los identificadores del proyecto
> base (`mdqr-*`, `bo.com.sintesis.mdqr.*`, `middleware_*`, realm `middleware-core`,
> paths de Vault `mdqr-auth`/`mdqr-decode`) son **estables**. No renombrar
> módulos, paquetes, bases de datos, nombres de Consul ni paths de Vault. El
> decoupling de nombres es trabajo separado y documentado aparte; hasta que
> ocurra, tratar estos nombres como inamovibles.

---

## Quick Start (arranque diario, perfil `local`)

```bash
# 1. Levantar el stack Docker de tools (Keycloak, Consul, Vault, Redis, Postgres)
deploy/scripts/tools.sh --up

# 2. Arrancar servicios (cada uno en su terminal), perfil local
./gradlew :mdqr-ms-auth:bootRun  --args='--spring.profiles.active=local'   # Terminal A → 8083
./gradlew :mdqr-ms-base:bootRun  --args='--spring.profiles.active=local'   # Terminal B → 8081
./gradlew :mdqr-gateway:bootRun  --args='--spring.profiles.active=local'   # Terminal C → 8080

# 3. Frontend (Angular, bun)
cd mdqr-frontend && bun run start:admin   # o start:public
```

Vault seed (solo en primer setup o tras limpiar volúmenes — ver sección Scripts):

```bash
TOOLS_HOST=127.0.0.1 DB_NAME=mdqr_auth   deploy/scripts/vault-seed.sh --ns mdqr-auth   --kc-realm mdqr-admin
TOOLS_HOST=127.0.0.1 DB_NAME=mdqr_decode deploy/scripts/vault-seed.sh --ns mdqr-decode --kc-realm mdqr-admin
```

### Obtener tokens (local)

```bash
# Token admin (realm mdqr-admin — APIs internas /services/**)
ADMIN_TOKEN=$(curl -s http://127.0.0.1:8180/realms/mdqr-admin/protocol/openid-connect/token \
  -d grant_type=client_credentials -d client_id=mdqradminservice \
  -d client_secret=mdqradminservice-secret | jq -r .access_token)

# Token partner (vía gateway proxy — realm mdqr-partner — APIs externas /partner/**)
PARTNER_TOKEN=$(curl -s -X POST http://127.0.0.1:8080/oauth2/token \
  -d grant_type=client_credentials -d client_id=unilink-api \
  -d client_secret=unilink-api-secret \
  -d scope=https://api.sintesis.com.bo/qr.decode | jq -r .access_token)
```

---

## Arquitectura

```
                        ┌──────────────────────────────────────────────┐
   Partners (M2M) ──────┤                                               │
   POST /oauth2/token   │           mdqr-gateway  (WebFlux)             │
   /partner/v1/**       │              puerto 8080                      │
                        │  Chain Order=1: partner  (realm mdqr-partner) │
   SPA Admin / Public ──┤  Chain Order=2: admin    (realm mdqr-admin)   │
   /services/**         │  Discovery locator (Consul) + LoadBalancer    │
                        └───────┬──────────────────────┬────────────────┘
                                │ lb://mdqrbaseservice  │ lb://mdqradminservice
                                ▼                       ▼
              ┌─────────────────────────┐   ┌──────────────────────────┐
              │  mdqr-ms-base  (MVC)    │   │  mdqr-ms-auth  (MVC)      │
              │  puerto 8081            │   │  puerto 8083              │
              │  Consul: mdqrbaseservice│   │  Consul: mdqradminservice │
              │  QR decode + certs      │   │  Users/roles/menus/audit  │
              │  DB mdqr_decode/public  │   │  DB mdqr_auth/admin        │
              │  → Tuxedo API (Go,5050) │   │  → Keycloak Admin API     │
              └────────────┬────────────┘   └───────────┬──────────────┘
                           │  usa (in-process)          │ usa (in-process)
                           │                            ▼
                           │                 ┌──────────────────────────┐
                           └────────────────►│  mdqr-audit-commons (lib) │
                                             └──────────────────────────┘

   Infra (deploy/tools/, stack docker mdqr-tools):
   Keycloak 8180 │ Consul 8500 │ Vault 8200 │ Redis 6379 │ PostgreSQL 5432 (host)
```

### Mapa de puertos

| Componente      | Puerto | Notas                                            |
|-----------------|--------|--------------------------------------------------|
| mdqr-gateway    | 8080   | Único punto de entrada público                   |
| mdqr-ms-base    | 8081   | NO expuesto directo en prod (vía gateway)        |
| mdqr-ms-auth    | 8083   | NO expuesto directo en prod (vía gateway)        |
| Keycloak        | 8180   | **NO 8080** — ése es el gateway                  |
| Consul HTTP     | 8500   |                                                  |
| Vault           | 8200   | dev mode, token `root`                           |
| Redis           | 6379   |                                                  |
| PostgreSQL      | 5432   | corre en el host, no en el stack tools           |
| Tuxedo API (Go) | 5050   | servicio externo de certificados (no en repo)    |

> En el servidor QA compartido (ssaqa001) los puertos difieren: Consul 8595,
> Vault 8295, Redis 6480, Keycloak 8180. Ver memoria del proyecto / `qa_server.md`.

---

## Realms Keycloak

| Realm          | Uso                                     | Client (M2M)        | Chain gateway |
|----------------|-----------------------------------------|---------------------|---------------|
| `mdqr-admin`   | APIs internas + SPA admin               | `mdqradminservice`  | Order=2       |
| `mdqr-partner` | APIs externas M2M (partners)            | `unilink-api`       | Order=1       |

- El **issuer del JWT** debe ser consistente: usar `127.0.0.1:8180` tanto al
  emitir tokens como en la config de los servicios (`external-url`). Si el
  issuer no coincide, la validación falla con 401.
- `mdqradminservice` tiene roles `realm-management` (manage-users, view-users,
  query-users, query-clients, view-realm, manage-realm) y se usa también como
  cliente de la Keycloak Admin API desde ms-auth.

---

## Módulos

### 1. `mdqr-gateway/` — API Gateway

- **Responsabilidad**: punto de entrada único. Enrutamiento vía Consul discovery
  locator + LoadBalancer, dos cadenas de seguridad (partner/admin), proxy del
  token endpoint partner, agregación de Swagger, CORS, rate limit, IP/domain
  whitelist, OIDC browser login.
- **Stack**: Spring Boot 4 **WebFlux** (reactivo), Spring Cloud Gateway
  (`spring-cloud-starter-gateway-server-webflux`), `oauth2-resource-server` +
  `oauth2-client`, Spring Session Redis (reactive), Caffeine cache, Consul
  discovery+config, Vault config, springdoc webflux UI.
- **Puerto**: 8080.
- **DB**: ninguna. Usa **Redis** (sesión + rate limit). Vault namespace
  `mdqr-decode`.
- **Dependencias**: enruta a `lb://mdqradminservice` y `lb://mdqrbaseservice`;
  proxya al token endpoint de Keycloak realm `mdqr-partner`.
- **Config**: `application.yml`, `application-local.yml`, `-dev.yml`, `-prod.yml`.
- **Clases clave**:
  - `SecurityConfiguration.java` — define las dos chains y los decoders JWT
    programáticos (`adminJwtDecoder` issuer mdqr-admin, `partnerJwtDecoder`
    issuer mdqr-partner). No hay `spring.security.oauth2.resourceserver.jwt`
    declarativo: los decoders se arman desde `ApplicationProperties`.
  - `ConsulConfiguration.java` — declara `@Primary ConsulClient` manualmente.
  - filtros: `IpWhitelistFilter`, `RateLimitFilter`, `DomainWhitelistFilter`,
    `RequestIdFilter`, `AccessLogFilter`.
- **Rutas relevantes** (definidas en `application.yml`):
  - `POST /oauth2/token` → reescribe a `/realms/mdqr-partner/protocol/openid-connect/token` (token proxy partner). **Público.**
  - `/partner/v1/**` → `lb://mdqrbaseservice` reescrito a `/api/**` (chain partner).
  - `/services/<svc>/**` → `lb://<svc>` con `StripPrefix=2` (discovery locator).
  - `/v3/api-docs/admin-service` y `/base-service` → agregación Swagger.

#### Cadenas de seguridad (gateway)
- **Order=1 `partnerSecurityChain`**: matchea `/partner/**` y `/oauth2/token`.
  Solo JWT Bearer (resource server), valida realm `mdqr-partner`. `/oauth2/token`
  es público; `/partner/**` requiere autenticación.
- **Order=2 `adminSecurityChain`**: todo lo demás. JWT Bearer (SPA keycloak-js)
  **y** OIDC browser flow, valida realm `mdqr-admin`. Públicos: login/refresh/
  logout de auth, health, swagger, callbacks OIDC. Resto de `/services/**`
  requiere auth. Browsers (Accept text/html) → redirect a Keycloak login; clientes
  API → 401 JSON.

### 2. `mdqr-ms-auth/` — Microservicio de administración / IAM

- **Responsabilidad**: gestión de usuarios, roles, menús, acciones, permisos
  (RBAC) y **auditoría**. Fachada sobre la **Keycloak Admin API** (clientes,
  usuarios, roles) + polling de eventos LOGIN/LOGOUT de Keycloak. Persiste audit
  logs directamente (sink in-process).
- **Stack**: Spring Boot 4 **MVC** (`spring-boot-starter-web`), Security +
  oauth2-resource-server, Data JPA + JDBC, Data Redis (+ `commons-pool2`),
  Liquibase, `keycloak-admin-client:26.0.6`, springdoc webmvc UI, audit-commons.
- **Puerto**: 8083.
- **DB**: PostgreSQL `mdqr_auth`, **schema `admin`** (Hibernate
  `default_schema=admin`, Liquibase `default-schema`/`liquibase-schema=admin`).
  Vault namespace `mdqr-auth`.
- **Service discovery**: se registra en Consul como **`mdqradminservice`**
  (no como `mdqr-ms-auth`) — el gateway y el frontend esperan ese nombre.
- **Config**: `application.yml`, `application-local.yml`, `-dev.yml`, `-prod.yml`.
- **Clases clave**: `LiquibaseConfiguration.java` (+ `CustomLiquibaseDependsOnPostProcessor`),
  `KeycloakAdminConfiguration.java`, `KeycloakEventPoller.java`, `InProcessSink.java`,
  `ConsulConfiguration.java`.
- **API** (prefijo `/admin`, accesible vía gateway en `/services/mdqradminservice/admin/**`):
  `/admin/users`, `/admin/roles`, `/admin/menus`, `/admin/actions`,
  `/admin/roles/{name}/permissions`, `/admin/audit`, `/admin/auth/{login,refresh,logout}`,
  `/admin/audit/keycloak` (webhook legacy).
- **Auditoría**: `audit.sink-mode: in-process` → persiste directo, sin hop HTTP.
  El polling de Keycloak (`application.audit.keycloak-poll`) está habilitado en
  prod, **deshabilitado en local** (requiere admin client configurado).

### 3. `mdqr-ms-base/` — Microservicio base de desencriptación QR

- **Responsabilidad**: núcleo del negocio. Decodifica imágenes QR (ZXing),
  desencripta el payload con RSA/BouncyCastle usando el certificado de la entidad
  financiera, gestiona el ciclo de vida de certificados (upload/import/validate/
  activate/revoke/replace, versiones) sincronizándolos desde la **Tuxedo API** (Go),
  y registra logs de desencriptación + auditoría de certificados.
- **Stack**: Spring Boot 4 **MVC**, Security + oauth2-resource-server, Data JPA +
  JDBC, Data Redis (+ `commons-pool2`), Liquibase, **BouncyCastle**
  (`bcprov`/`bcpkix-jdk18on:1.78`), **ZXing** (`core`+`javase:3.5.3`),
  `mongodb:bson` (generación de IDs), springdoc webmvc UI. Tests con
  Testcontainers (postgres, redis) + WireMock.
- **Puerto**: 8081.
- **DB**: PostgreSQL `mdqr_decode`, **schema `public`**. Vault namespace
  `mdqr-decode`. Hibernate `ddl-auto=none` (Liquibase manda).
- **Service discovery**: se registra en Consul como **`mdqrbaseservice`**.
- **Config**: `application.yml`, `application-local.yml`, `-dev.yml`, `-prod.yml`,
  test `application-test.yml`.
- **Clases clave**: `QrDecryptionService`, `QrImageDecoderService`, `CryptoService`,
  `CertificateService` / `CertificateVersionService` / `CertificateValidationService`,
  `TuxedoApiClient`, `LiquibaseConfiguration.java`, `SecurityConfiguration.java`.
- **API**:
  - `POST /api/qr/decode` — JSON, `inputType` = `DECODED_DATA` (`ENCRYPTED_DATA|CERT_CODE`) o `BASE64_IMAGE`.
  - `POST /api/qr/decode/file` — multipart (imagen JPG/PNG/GIF con QR).
  - `GET /api/qr/audits` — logs de desencriptación con filtros + paginación (max size 100).
  - `/api/certificates/**` — CRUD + upload-file/validate/{id}/pem/entity/{id}/expiring/{days}/activate/deactivate/revoke/replace/audits.
  - Vía gateway partner: `/partner/v1/qr/decode` → `/api/qr/decode`.
- **Gotcha de seguridad**: `SecurityConfiguration` está en **MODO DESARROLLO** —
  `anyRequest().permitAll()`, OAuth2 JWT comentado, `@PreAuthorize` y
  `@EnableMethodSecurity` deshabilitados. La protección real hoy la aplica el
  gateway. **Antes de prod hay que habilitar la validación JWT y los roles**
  (`API_CLIENT`, `ADMIN`, `AUDITOR`). El `KeycloakRealmRoleConverter` ya está
  escrito (extrae `realm_access.roles` → `ROLE_*`).
- **QR decryption config** (`application.qr.decryption`): cache en Redis habilitado,
  TTL 1440 min (24h), auditoría habilitada.
- **Certificate sync** (`application.certificate.sync`): cron horario en prod,
  deshabilitado en local.

### 4. `mdqr-audit-commons/` — Librería de auditoría compartida

- **Responsabilidad**: instrumentación de auditoría reutilizable. Define
  `@Auditable`, un aspecto AOP (`AuditAspect`), publisher con buffer + retry, y
  dos sinks: `RemoteHttpSink` (POST a admin-service con token de service account)
  o sink in-process (el host implementa `AuditEventSink`).
- **Tipo**: **librería plana** (`bootJar` deshabilitado, `jar` habilitado; Jib y
  native deshabilitados). Consumida vía `implementation project(':mdqr-audit-commons')`.
- **Stack**: Spring Boot starter + `spring-aop`/`spring-aspects`/`aspectjweaver`
  (Spring Boot 4 ya no trae `spring-boot-starter-aop`). Security/servlet/micrometer
  son `compileOnly` (los provee el host) para no forzar stack servlet en consumers
  reactivos como el gateway.
- **Auto-config**: `AuditAutoConfiguration` (`@AutoConfiguration`), activa con
  `audit.enabled=true` (default). Modo por `audit.sink-mode`: `remote` (default,
  requiere `audit.oauth.token-uri`) o `in-process`. El `AuditAspect` solo se crea
  si hay `HttpServletRequest` en classpath (consumers reactivos solo usan el publisher).
- **Consumido por**: `mdqr-ms-auth` (modo in-process).

### 5. `mdqr-frontend/` — Frontend Angular (monorepo de apps)

- **Responsabilidad**: SPA. Dos aplicaciones Angular en un workspace:
  `apps/admin` (panel administrativo) y `apps/public` (cara pública).
- **Stack**: **Angular 21**, **bun** como package manager (`bun@1.3.12`),
  Tailwind 4, ngx-translate, keycloak-angular + keycloak-js 26, ApexCharts,
  jsPDF, xlsx. Tests con Playwright + Vitest.
- **Build Gradle**: no-op (`build.gradle` vacío) — se construye con bun/Angular CLI.
- **Scripts** (`package.json`): `start:admin`/`start:public`, `build:admin`/
  `build:public`/`build:all`, `watch:*`, `test`.
- **Auth**: keycloak-js contra realm `mdqr-admin`; llama al gateway en `:8080`.

### 6. `deploy/` — Infraestructura y despliegue

Ver secciones **Infraestructura** y **Scripts de despliegue**.

---

## Infraestructura (`deploy/tools/`, stack docker `mdqr-tools`)

Stack Docker aislado de las apps, gestionado por `deploy/scripts/tools.sh`
(wrapper sobre `deploy/tools/docker-compose.yml`). Config en `deploy/tools/.env`
(copiar desde `.env.example`).

| Servicio   | Imagen                          | Puerto host (default) |
|------------|---------------------------------|-----------------------|
| Keycloak   | `quay.io/keycloak/keycloak:26.6`| 8180 (HTTP), 8443 (HTTPS) |
| Consul     | `hashicorp/consul:1.22`         | 8500 (HTTP), 8300 (RPC), 8600/udp (DNS) |
| Redis      | `redis:8.8`                     | 6379                  |
| Vault      | `hashicorp/vault:1.21`          | 8200 (dev mode, token `root`) |

- **Keycloak siempre on** (sin profile). Resto vía `COMPOSE_PROFILES` en `.env`:
  - `single` (default): 1 redis + 1 consul + 1 vault (dev rápido).
  - `cluster`: 3 consul (raft) + 3 masters/3 replicas redis + 3 vault (raft HA).
    Tras `up` correr una vez `redis-cluster-init.sh` y `vault-cluster-init.sh`.
- **PostgreSQL** corre en el **host**, no en el stack tools. DBs: `mdqr_auth`
  (schema `admin`) y `mdqr_decode` (schema `public`).
- Bind a `MDQR_TOOLS_BIND_IP` (default `127.0.0.1`). Red externa `mdqr-shared`
  debe existir antes (`docker network create ... mdqr-shared`).
- Keycloak 26 usa `KC_BOOTSTRAP_ADMIN_*` (los legacy `KEYCLOAK_ADMIN*` no
  funcionan). Storage dev = `dev-file` (pierde datos al reiniciar) vs `postgres`
  (prod).

---

## Convenciones y gotchas (verificados en código)

1. **Spring Boot 4 + Liquibase 5 — sin `LiquibaseAutoConfiguration`**: no existe
   el módulo `spring-boot-liquibase`. Cada microservicio con DB declara el bean
   `SpringLiquibase` a mano en `LiquibaseConfiguration.java`. Además ese bean
   crea el schema (`CREATE SCHEMA IF NOT EXISTS`) **antes** de adquirir el lock,
   para romper el catch-22 en BD limpia (relevante en ms-auth con schema `admin`).

2. **`ConsulConfiguration` y perfil `local`**: el perfil local **deshabilita
   Consul config** (`consul.config.enabled=false`) pero **habilita Consul
   discovery** (`discovery.enabled=true`) para que el gateway resuelva `lb://`.
   También `vault.enabled=false` y `config.import=""`. El gateway declara un
   `@Primary ConsulClient` manualmente (`ConsulConfiguration.java`).

3. **`commons-pool2`**: requerido por el connection pool de Redis Lettuce
   (`spring.data.redis.lettuce.pool`). Está en `build.gradle` de ms-auth y
   ms-base. Sin él, el pool de Lettuce no arranca.

4. **Token endpoint partner expuesto en el gateway**: los partners hacen
   `POST :8080/oauth2/token` (no directo a Keycloak). El gateway reescribe a
   `/realms/mdqr-partner/protocol/openid-connect/token`. Endpoint público.

5. **Keycloak en 8180** (no 8080 — ése es el gateway).

6. **Dos cadenas de seguridad en el gateway**: Order=1 partner (externo, realm
   `mdqr-partner`, `/partner/**`+`/oauth2/token`) y Order=2 admin (interno, realm
   `mdqr-admin`, todo lo demás). Decoders JWT programáticos, no declarativos.

7. **Vault namespaces**: `mdqr-auth` para ms-auth; `mdqr-decode` para ms-base y
   gateway. Los `application.yml` leen `${VAULT_NS:...}` directamente. El bug de
   `vault-seed.sh` (usa `KC_REALM=${NS}` por default) obliga a pasar
   `--kc-realm mdqr-admin` explícitamente.

8. **Nombres de servicio en Consul ≠ nombre del módulo**: ms-auth se registra
   como `mdqradminservice`, ms-base como `mdqrbaseservice`. El gateway discovery
   locator y el frontend dependen de esos nombres.

9. **Issuer JWT consistente**: emitir y validar tokens con el mismo host
   (`127.0.0.1:8180`). `external-url` es el issuer; `auth-server-url` es para JWKS.

10. **management base-path**: ms-base y ms-auth usan `management.endpoints.web.base-path:
    /management` (no el default `/actuator`). Health check de Consul apunta a
    `/management/health/readiness`.

11. **LoadBalancer cache TTL alto (1h) + Caffeine** en el gateway: tolera caídas
    intermitentes de Consul (el default de 35s causa 503 "no instances available").

12. **Seguridad de ms-base en MODO DESARROLLO**: `permitAll` total, JWT comentado.
    La protección la da el gateway hoy. Habilitar antes de prod.

13. **Filtros del gateway con `.onErrorResume`**: `IpWhitelistFilter` y
    `RateLimitFilter` no provocan 500 si Redis cae.

14. **AOT/native build**: la tarea `ProcessAot` deshabilita vault/consul/config
    import vía jvmArgs (ver `build.gradle` raíz). audit-commons deshabilita
    bootJar/jib/native/aot.

---

## Scripts de despliegue (`deploy/scripts/`)

| Script                       | Propósito |
|------------------------------|-----------|
| `tools.sh`                   | Gestiona el stack Docker de tools. Comandos: `--up`/`-u`, `--down`/`-d` (`-v` borra volúmenes), `--info`/`-i` (default, muestra URLs/puertos), `--logs`/`-l [svc]`, `--restart`/`-r [svc]`. |
| `vault-seed.sh`              | Seedea Vault KV con secretos (`system/redis`, `system/database`, `keycloak/service-client`, `keycloak/admin-client`, `keycloak/partner-client`). Flags: `--ns <namespace>`, `--kc-realm <realm>` (¡pasar `mdqr-admin`!), `--external <VAULT_ADDR>`. Vars: `TOOLS_HOST`, `DB_NAME`, `TENANT_ID`. Idempotente. |
| `keycloak-sync-admin.sh`     | Crea/sincroniza el realm `mdqr-admin` (clients, roles, scopes, service accounts) desde CSVs en `keycloak-seed/admin/`. |
| `keycloak-sync-partner.sh`   | Crea/sincroniza el realm `mdqr-partner` (clients M2M) desde `keycloak-seed/partner/`. |
| `keycloak-sync.sh`           | Sync genérico de Keycloak (base de los anteriores). |
| `create-partner.sh`          | Da de alta un partner (client M2M) en `mdqr-partner`. |
| `env-sync.sh`                | Sincroniza variables de entorno entre configs. |
| `init-logs.sh`               | Inicializa directorios/archivos de logs. |
| `keycloak-seed/`             | CSVs de seed (roles, clients, scopes, policies, permissions) divididos en `admin/` y `partner/`. Ver `KEYCLOAK_DEPLOYMENT.md`. |

Otros artefactos de deploy:
- `deploy/tools/` — docker-compose del stack tools + `vault/*.hcl`, scripts de
  init de cluster (`redis-cluster-init.sh`, `vault-cluster-init.sh`).
- `deploy/services/` — compose por servicio (`001-gateway.yml`,
  `002-decrypt-service.yml`, `003-admin-service.yml`, `100-admin-fe.yml`,
  `101-public-fe.yml`).
- `deploy/development/` y `deploy/production/` — docker-compose por ambiente +
  `.env.example`.

---

## Perfil `local` (patrón común, env vars con default)

```yaml
KEYCLOAK_HOST: 127.0.0.1   KEYCLOAK_PORT: 8180
DB_HOST: 127.0.0.1         DB_PORT: 5432
REDIS_HOST: 127.0.0.1      REDIS_PORT: 6379
```

El perfil local en todos los servicios: `config.import=""`, `vault.enabled=false`,
`consul.config.enabled=false`, `consul.discovery.enabled=true`, secretos de
Keycloak hardcodeados (clients/secrets `*-secret`), logging DEBUG.
