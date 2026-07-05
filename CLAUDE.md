# CLAUDE.md — Hub de Interoperabilidad (hub-interop)

Guía para agentes de Claude Code que trabajen en este monorepo. Documenta cada
módulo, la infraestructura y los gotchas conocidos para tomar decisiones de
implementación sin tener que leer todo el código.

> **Idioma**: responder siempre en español.
> **Comandos**: nunca ejecutar comandos automáticamente — entregar los comandos
> para que el usuario los corra.

---

## Descripción del proyecto

`hub-interop` (group `bo.com.sintesis.hub`) es un **hub de interoperabilidad
con terceros**: único punto de entrada y salida de información entre la empresa
y las instituciones partner (Ministerio Público, Policía/FELCN). El primer
dominio de negocio es el **intercambio de casos penales** (POL/FELCN ↔ MP).
Está compuesto por un API Gateway, dos microservicios Spring Boot, una librería
de auditoría compartida y un frontend Angular multi-app.

> **Historia**: el proyecto nació de un middleware de decodificación de QR.
> El negocio QR fue **eliminado** (ADR-0004) y el decoupling de nombres del
> proyecto base se **ejecutó el 2026-07-03**: módulos, paquetes
> (`bo.com.sintesis.hub.*`), realms (`hub-admin`/`hub-partner`), DBs
> (`hub_auth`/`hub_base`), namespaces de Vault (`hub-auth`/`hub-base`) y
> nombres de Consul (`hubadminservice`/`hubbaseservice`).

- **Monorepo Gradle multi-módulo** (Groovy DSL, `settings.gradle`,
  rootProject `hub-interop`).
- **Java 25** (toolchain), **Spring Boot 4.0.5**, **Spring Cloud 2025.1.1**.
- Versionado: versión central `projectVersion=1.0` + patch independiente por
  módulo en `gradle.properties` (resultando `1.0.x`).
- Build de imágenes con **Jib** (base `eclipse-temurin:25-jre-alpine`,
  registry `cr.sintesis.com.bo/hub-dev`) y soporte **GraalVM native**.
- Repos Maven: Maven Central + Nexus de Síntesis (`nexus.sintesis.com.bo`,
  requiere `NEXUS_USERNAME`/`NEXUS_PASSWORD`).

### ADRs (en `docs/adr/`)

| ADR | Tema | Estado |
|---|---|---|
| ADR-0001 | Hub de interoperabilidad: mTLS + RFC 8705, auditoría hash-chain, outbox, ACL outbound | Base vigente |
| ADR-0004 | Eliminación del negocio QR | Implementado |
| ADR-0005 | Contrato de respuesta universal `ApiResponse<T>` + catálogo `error.code` | Vigente |
| ADR-0006 | Motor genérico inbound (DispatcherController + contratos + InboundPort) | Vigente |
| ADR-0007 | Registro declarativo de APIs (YAML) + auditoría de payloads | Propuesto |

### Reglas no negociables

1. **mTLS + RFC 8705 (token enlazado al certificado)**: todo partner presenta
   un certificado de cliente emitido por la **PKI de Vault**. El access token
   (OAuth2 client_credentials, Keycloak realm `hub-partner`) queda ligado al
   certificado (`cnf.x5t#S256`): un token robado es inútil sin la clave privada.
   Sin certificado válido mapeado a una suscripción activa → rechazo en el edge.

2. **Auditoría + hash de toda transacción**: cada transacción (inbound y
   outbound) genera un registro con **hash SHA-256 del request y del response**
   (canonicalización RFC 8785), en **cadena de hashes** por partner
   (`prev_hash` → `chain_hash`, tamper-evident), **firmada** con Vault Transit.
   Payloads: inbound puede guardarse en claro, outbound **cifrado con Transit**
   y visible solo bajo control de acceso auditado (ADR-0007).

3. **Sin facturación como lógica de negocio** (decisión 2026-07-03): no se
   construye relay/medición/facturación. Los **reportes por cliente** (qué se
   intercambió, cuánto, cuándo) salen de `hub_audit_log` (+ `connector_call_log`
   cuando exista). El patrón outbox (`outbox_event`) se conserva como mecanismo
   de propagación de eventos, no como pipeline de facturación. Las tablas
   `hub_measurement` y `provider`/`provider_credential_ref` fueron **eliminadas**
   de los changelogs (DB desde cero, 2026-07-03).

4. **Apps internas nunca llaman APIs externas directo**: toda llamada a un
   tercero pasa por el hub (Anti-Corruption Layer). Un **adaptador por
   proveedor** traduce el contrato canónico, gestiona credenciales en Vault y
   aplica resiliencia (resilience4j: timeout, retry, circuit breaker, bulkhead)
   y caché (Redis cuando aplica). Ejemplo vigente: `EfxRateAdapter`.

5. **Secretos en Vault, idempotencia**: todas las credenciales de terceros se
   almacenan en Vault (nunca en config ni código). Las operaciones de escritura
   expuestas son idempotentes (`X-Idempotency-Key`).

6. **Agregar una API expuesta NO es programar**: es declarar su contrato y su
   destino por configuración (ADR-0006/0007). No se crean controllers ni DTOs
   por producto.

---

## Quick Start (arranque diario, perfil `local`)

```bash
# 1. Levantar el stack Docker de tools (Keycloak, Consul, Vault, Redis)
deploy/scripts/tools.sh --up

# 2. Arrancar servicios (cada uno en su terminal), perfil local
./gradlew :hub-ms-auth:bootRun  --args='--spring.profiles.active=local'   # Terminal A → 8083
./gradlew :hub-ms-base:bootRun  --args='--spring.profiles.active=local'   # Terminal B → 8091 (local)
./gradlew :hub-gateway:bootRun  --args='--spring.profiles.active=local'   # Terminal C → 8080

# 3. Frontend (Angular, bun)
cd hub-frontend && bun run start:admin   # o start:public
```

Vault seed (solo en primer setup o tras limpiar volúmenes — ver sección Scripts):

```bash
TOOLS_HOST=127.0.0.1 DB_NAME=hub_auth deploy/scripts/vault-seed.sh --ns hub-auth --kc-realm hub-admin
TOOLS_HOST=127.0.0.1 DB_NAME=hub_base deploy/scripts/vault-seed.sh --ns hub-base --kc-realm hub-admin
```

### Obtener tokens (local)

```bash
# Token admin (realm hub-admin — APIs internas /services/**)
ADMIN_TOKEN=$(curl -s http://127.0.0.1:8180/realms/hub-admin/protocol/openid-connect/token \
  -d grant_type=client_credentials -d client_id=hubadminservice \
  -d client_secret=hubadminservice-secret | jq -r .access_token)

# Token partner (vía gateway proxy — realm hub-partner — APIs externas /partner/**)
PARTNER_TOKEN=$(curl -s -X POST http://127.0.0.1:8080/oauth2/token \
  -d grant_type=client_credentials -d client_id=unilink-api \
  -d client_secret=unilink-api-secret \
  -d scope=https://api.sintesis.com.bo/caso.penal | jq -r .access_token)
```

---

## Arquitectura

```
                        ┌──────────────────────────────────────────────┐
   Partners (M2M) ──────┤                                               │
   POST /oauth2/token   │           hub-gateway  (WebFlux)              │
   /partner/v1/**       │              puerto 8080                      │
                        │  Chain Order=1: partner  (realm hub-partner)  │
   SPA Admin / Public ──┤  Chain Order=2: admin    (realm hub-admin)    │
   /services/**         │  mTLS + RFC 8705 + suscripción (filtros)      │
                        └───────┬──────────────────────┬────────────────┘
                                │ lb://hubbaseservice   │ lb://hubadminservice
                                ▼                       ▼
              ┌─────────────────────────┐   ┌──────────────────────────┐
              │  hub-ms-base  (MVC)     │   │  hub-ms-auth  (MVC)       │
              │  8081 (local: 8091)     │   │  puerto 8083              │
              │  Consul: hubbaseservice │   │  Consul: hubadminservice  │
              │  Motor inbound genérico │   │  Users/roles/menus/audit  │
              │  + adaptadores outbound │   │  → Keycloak Admin API     │
              │  DB hub_base/public     │   │  DB hub_auth/admin        │
              └────────────┬────────────┘   └───────────┬──────────────┘
                           │  usa (in-process)          │ usa (in-process)
                           └──────────────►┌──────────────────────────┐
                                           │  hub-audit-commons (lib)  │
                                           └──────────────────────────┘

   Infra (deploy/tools/, stack docker hub-tools):
   Keycloak 8180 │ Consul 8500 │ Vault 8200 │ Redis 6379 │ PostgreSQL 5432 (host)
```

### Mapa de puertos

| Componente      | Puerto | Notas                                            |
|-----------------|--------|--------------------------------------------------|
| hub-gateway     | 8080 (perfil local: **8082**) | Único punto de entrada público    |
| hub-ms-base     | 8081 (perfil local: **8091**) | NO expuesto directo en prod (vía gateway) |
| hub-ms-auth     | 8083   | NO expuesto directo en prod (vía gateway)        |
| Keycloak        | 8180   | **NO 8080** — ése es el gateway                  |
| Consul HTTP     | 8500   |                                                  |
| Vault           | 8200   | dev mode, token `root`                           |
| Redis           | 6379   |                                                  |
| PostgreSQL      | 5432   | corre en el host, no en el stack tools           |

> En el servidor QA compartido (ssaqa001) los puertos difieren: Consul 8595,
> Vault 8295, Redis 6480, Keycloak 8180. Ver memoria del proyecto / `qa_server.md`.

---

## Realms Keycloak

| Realm         | Uso                          | Client (M2M)      | Chain gateway |
|---------------|------------------------------|-------------------|---------------|
| `hub-admin`   | APIs internas + SPA admin    | `hubadminservice` | Order=2       |
| `hub-partner` | APIs externas M2M (partners) | `unilink-api`     | Order=1       |

- El **issuer del JWT** debe ser consistente: usar `127.0.0.1:8180` tanto al
  emitir tokens como en la config de los servicios (`external-url`). Si el
  issuer no coincide, la validación falla con 401.
- Scope de producto vigente: `https://api.sintesis.com.bo/caso.penal`.
- `hubadminservice` tiene roles `realm-management` y se usa también como
  cliente de la Keycloak Admin API desde ms-auth.

---

## Módulos

### 1. `hub-gateway/` — API Gateway

- **Responsabilidad**: punto de entrada único. Enrutamiento vía Consul discovery
  locator + LoadBalancer, dos cadenas de seguridad (partner/admin), proxy del
  token endpoint partner, mTLS + binding RFC 8705, agregación de Swagger, CORS,
  rate limit, IP/domain whitelist, OIDC browser login.
- **Stack**: Spring Boot 4 **WebFlux**, Spring Cloud Gateway, oauth2-resource-server
  + oauth2-client, Spring Session Redis (reactive), Caffeine, Consul, Vault config.
- **Puerto**: 8080. **DB**: ninguna (Redis para sesión/rate limit). Vault ns `hub-base`.
- **Clases clave**:
  - `SecurityConfiguration.java` — dos chains con decoders JWT programáticos
    (`adminJwtDecoder` issuer hub-admin, `partnerJwtDecoder` issuer hub-partner).
  - `security/filter/MtlsCertBindingFilter.java` (orden 10) — RFC 8705: thumbprint
    del cert vs `cnf.x5t#S256`; propaga `X-Partner-Id`. En local/test acepta
    thumbprint simulado por header (`hub.mtls.test-mode`).
  - `security/filter/PartnerSubscriptionFilter.java` (orden 11) — scope del
    producto = suscripción (PoC; TODO consulta real de suscripciones).
  - `IpWhitelistFilter`, `RateLimitFilter`, `DomainWhitelistFilter`,
    `RequestIdFilter`, `AccessLogFilter`.
- **Rutas relevantes** (`application.yml`):
  - `POST /oauth2/token` → reescribe al token endpoint del realm `hub-partner`. **Público.**
  - `/partner/v1/**` → `lb://hubbaseservice` reescrito a `/api/**` (chain partner).
  - `/services/<svc>/**` → `lb://<svc>` con `StripPrefix=2` (discovery locator).
- **mTLS en prod**: listener HTTPS con `client-auth: want` (`application-prod.yml`);
  truststore = CA de la PKI de Vault.

### 2. `hub-ms-auth/` — Microservicio de administración / IAM

- **Responsabilidad**: usuarios, roles, menús, acciones, permisos (RBAC) y
  auditoría admin. Fachada sobre la Keycloak Admin API + polling de eventos
  LOGIN/LOGOUT. Persiste audit logs directamente (sink in-process).
- **Stack**: Spring Boot 4 **MVC**, Data JPA + JDBC, Data Redis (+ commons-pool2),
  Liquibase, `keycloak-admin-client:26.0.6`, audit-commons.
- **Puerto**: 8083. **DB**: PostgreSQL `hub_auth`, schema `admin`. Vault ns `hub-auth`.
- **Consul**: se registra como **`hubadminservice`**.
- **Tablas v2** (identidad de partners): `partner`, `partner_certificate`,
  `partner_subscription` — base para la verificación real de suscripciones que
  hoy el gateway aproxima por scope (`PartnerSubscriptionFilter`, TODO).
- **API** (prefijo `/admin`, vía gateway `/services/hubadminservice/admin/**`):
  users, roles, menus, actions, permissions, audit, auth/{login,refresh,logout}.

### 3. `hub-ms-base/` — Núcleo del hub (motor inbound + adaptadores outbound)

- **Responsabilidad**: motor genérico del hub (ADR-0006). Recibe transacciones
  de partners, valida contra contrato declarado, hashea (RFC 8785 + SHA-256),
  reenvía al backend interno (`InboundPort`), audita (cadena + firma) y escribe
  outbox — todo transversal, sin lógica de negocio por producto. Además aloja
  los adaptadores **outbound** a proveedores externos (ACL).
- **Stack**: Spring Boot 4 **MVC**, Data JPA + JDBC, Data Redis, Liquibase,
  resilience4j programático. Tests con Testcontainers + WireMock.
- **Puerto**: 8081 (perfil `local`: **8091**). **DB**: PostgreSQL `hub_base`,
  schema `public`. Vault ns `hub-base`. `ddl-auto=none` (Liquibase manda).
- **Consul**: se registra como **`hubbaseservice`**.
- **Motor inbound** (`hub.inbound.*`):
  - `DispatcherController` — único controller: `POST /api/inbound/{product}/{version}`
    y `PATCH /api/inbound/{product}/{version}/{id}` (inyecta el id del path en
    el campo `resourceIdField` del contrato).
  - `ContractRegistry` / `ContractDefinition` / `FieldRule` / `ContractValidator` —
    validación por contrato declarado (no DTOs `@Valid`).
  - `ForwardingGateway` → `InboundPort` (adaptador por producto;
    `StubInboundAdapter` con `hub.inbound.stub-mode=true`).
  - Productos vigentes: `CASO_PENAL/v1` (POST) y `CASO_PENAL_EDITAR/v1` (PATCH).
    Hoy declarados en `InboundAutoConfiguration`; el ADR-0007 los mueve a YAML.
- **Outbound** (`interop.outbound.*`): `EfxRateAdapter`/`EfxRateClient` — patrón
  de referencia ACL: RestClient + Bulkhead→CircuitBreaker→Retry programático,
  credencial desde Vault, caché Redis, auditoría vía `HubAuditService`.
- **Auditoría** (`hub.*` + audit-commons): `HubAuditInterceptor` sobre
  `/api/inbound/**` → `HubAuditService.record()` escribe en transacción única
  `hub_audit_log` (particionada por mes, cadena de hashes por partner con
  advisory lock) + `hub_audit_idempotency` + `outbox_event`. Firma Vault
  Transit fuera de la tx.
- **Tablas** (Liquibase `db/changelog/v2/`, DB desde cero 2026-07-03):
  `hub_audit_log` (+particiones), `hub_audit_idempotency`, `outbox_event`.
  Eliminadas: `hub_measurement` (sin facturación), `provider`/
  `provider_credential_ref` (catálogo en YAML, ADR-0007), `caso` (Modelo A,
  ADR-0006 §9). Próximas (ADR-0007 fases 3-5): `hub_audit_payload`,
  `connector_call_log`, `payload_access_log`.
- **Contrato de respuesta**: `web/rest/ApiResponse.java` (ADR-0005) — sobre
  único para éxito y error, `correlation_id` obligatorio.
- **Gotcha de seguridad**: `SecurityConfiguration` está en **MODO DESARROLLO**
  (`permitAll`, JWT comentado). La protección real la aplica el gateway.
  **Antes de prod**: habilitar JWT + roles (`API_CLIENT`, `ADMIN`, `AUDITOR`).

### 4. `hub-audit-commons/` — Librería de auditoría compartida

- Librería plana (bootJar off). Define `@Auditable` + AOP, publisher con buffer,
  sinks remote/in-process, y el **`HubAuditService`** (hash-chain + outbox
  transaccional) usado por ms-base. Consumida por ms-auth (in-process) y ms-base.

### 5. `hub-frontend/` — Frontend Angular

- **Angular 21**, **bun**, Tailwind 4, keycloak-angular/keycloak-js 26 (realm
  `hub-admin`), dos apps: `apps/admin` y `apps/public`. Scripts:
  `start:admin`/`start:public`, `build:*`. Llama al gateway en `:8080`.

### 6. `deploy/` — Infraestructura y despliegue

Ver secciones **Infraestructura** y **Scripts**.

---

## Infraestructura (`deploy/tools/`, stack docker `hub-tools`)

Stack Docker gestionado por `deploy/scripts/tools.sh` (config `deploy/tools/.env`).

| Servicio | Imagen                           | Puerto host (default) |
|----------|----------------------------------|-----------------------|
| Keycloak | `quay.io/keycloak/keycloak:26.6` | 8180 / 8443           |
| Consul   | `hashicorp/consul:1.22`          | 8500 / 8300 / 8600udp |
| Redis    | `redis:8.8`                      | 6379                  |
| Vault    | `hashicorp/vault:1.21`           | 8200 (dev, token `root`) |

- Keycloak siempre on; resto vía `COMPOSE_PROFILES` (`single` default, `cluster`).
- **PostgreSQL corre en el host**. DBs: `hub_auth` (schema `admin`) y `hub_base`
  (schema `public`).
- Red externa `hub-shared` debe existir antes (`docker network create hub-shared`).
- Keycloak 26 usa `KC_BOOTSTRAP_ADMIN_*` (no los legacy `KEYCLOAK_ADMIN*`).

---

## Convenciones y gotchas (verificados en código)

1. **Spring Boot 4 + Liquibase 5 — sin auto-config**: cada microservicio declara
   el bean `SpringLiquibase` a mano (`LiquibaseConfiguration.java`); el bean crea
   el schema antes de adquirir el lock (relevante en ms-auth, schema `admin`).
2. **Perfil `local`**: `config.import=""`, `vault.enabled=false`,
   `consul.config.enabled=false` pero `discovery.enabled=true`; secretos
   hardcodeados `*-secret`; logging DEBUG.
3. **`commons-pool2`** requerido por el pool Lettuce de Redis (ms-auth y ms-base).
4. **Token endpoint partner en el gateway**: `POST :8080/oauth2/token` (público),
   reescrito al realm `hub-partner`. Los partners nunca hablan con Keycloak directo.
5. **Keycloak en 8180** (8080 es el gateway).
6. **Dos chains en el gateway**: Order=1 partner (`/partner/**`+`/oauth2/token`),
   Order=2 admin (resto). Decoders JWT programáticos.
7. **Vault namespaces**: `hub-auth` (ms-auth), `hub-base` (ms-base y gateway).
   `vault-seed.sh` requiere `--kc-realm hub-admin` explícito.
8. **Nombres Consul ≠ nombre de módulo**: `hubadminservice` / `hubbaseservice`.
9. **Issuer JWT consistente**: emitir y validar con el mismo host (`127.0.0.1:8180`).
10. **management base-path** en ms-base/ms-auth: `/management` (no `/actuator`);
    health de Consul → `/management/health/readiness`.
11. **LoadBalancer cache TTL 1h + Caffeine** en el gateway (tolera caídas de Consul).
12. **Seguridad de ms-base en MODO DESARROLLO** (`permitAll`) — habilitar JWT+roles
    antes de prod (prerequisito de la fase de payloads del ADR-0007).
13. **Filtros del gateway con `.onErrorResume`** — no provocan 500 si Redis cae.
14. **AOT/native**: `ProcessAot` deshabilita vault/consul/config-import por jvmArgs;
    audit-commons deshabilita bootJar/jib/native/aot.
15. **`hub_audit_log` particionada por mes** (particiones pre-creadas hasta 2027-12);
    PK compuesta `(id, ts)`; cadena de hashes serializada por partner con
    `pg_advisory_xact_lock`.
16. **Renombramiento 2026-07-03**: no queda ninguna referencia al nombre del
    proyecto base ni a QR en el código. Entornos locales previos requieren
    recrear DBs, realms y seeds de Vault con los nombres nuevos.

---

## Scripts de despliegue (`deploy/scripts/`)

| Script                     | Propósito |
|----------------------------|-----------|
| `tools.sh`                 | Stack Docker de tools: `--up`/`-u`, `--down`/`-d` (`-v` borra volúmenes), `--info`/`-i`, `--logs`/`-l [svc]`, `--restart`/`-r [svc]`. |
| `vault-seed.sh`            | Seedea Vault KV (`system/redis`, `system/database`, `keycloak/*-client`). Flags: `--ns <namespace>`, `--kc-realm <realm>` (¡pasar `hub-admin`!), `--external`. Vars: `TOOLS_HOST`, `DB_NAME`, `TENANT_ID`. Idempotente. |
| `keycloak-sync-admin.sh`   | Crea/sincroniza el realm `hub-admin` desde CSVs en `keycloak-seed/admin/`. |
| `keycloak-sync-partner.sh` | Crea/sincroniza el realm `hub-partner` desde `keycloak-seed/partner/`. |
| `create-partner.sh`        | Alta de un partner (client M2M) en `hub-partner`. |
| `create-pki.sh`            | PKI de Vault para certificados de partners (mTLS). |
| `keycloak-seed/`           | CSVs de seed (roles, clients, scopes) en `admin/` y `partner/`. |

Otros: `deploy/tools/` (compose del stack), `deploy/services/` (compose por
servicio), `deploy/development/` y `deploy/production/` (compose por ambiente).

---

## Perfil `local` (patrón común, env vars con default)

```yaml
KEYCLOAK_HOST: 127.0.0.1   KEYCLOAK_PORT: 8180
DB_HOST: 127.0.0.1         DB_PORT: 5432
REDIS_HOST: 127.0.0.1      REDIS_PORT: 6379
```
