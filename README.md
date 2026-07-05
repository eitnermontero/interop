# HUB — Desencriptación de QR Bancarios

Sistema de desencriptación de códigos QR generados por entidades financieras bolivianas.

---

## Arquitectura

```
                           ┌─────────────────────────────────────┐
                           │          Keycloak :8180              │
                           │  realm: hub-admin  hub-partner     │
                           └──────────┬──────────────┬───────────┘
                                      │              │
                           ┌──────────▼──────────────▼───────────┐
                           │       hub-gateway :8080             │
                           │  Spring Cloud Gateway + JWT OAuth2   │
                           └──────────┬──────────────┬───────────┘
                                      │              │
                     ┌────────────────▼───┐   ┌──────▼─────────────────┐
                     │  hub-ms-auth :8083│   │  hub-ms-base :8081    │
                     │  RBAC + Usuarios   │   │  QR Decrypt + Certs    │
                     └────────────────────┘   └────────────────────────┘
```

### Servicios

| Servicio        | Puerto | Descripción                          | Consul Name        |
|-----------------|--------|--------------------------------------|--------------------|
| hub-gateway    | 8080   | Punto de entrada único, JWT + routing | —                 |
| hub-ms-auth    | 8083   | Gestión de usuarios, roles, permisos | `hubadminservice` |
| hub-ms-base    | 8081   | Desencriptación QR + certificados    | `hubbaseservice`  |
| Keycloak        | 8180   | IdP: realms `hub-admin` + `hub-partner` | —             |
| Consul          | 8500   | Service discovery                    | —                  |
| Vault           | 8200   | Secrets management                   | —                  |
| Redis           | 6379   | Cache + rate limit + sesiones        | —                  |
| PostgreSQL      | 5432   | Base de datos (instalado en el host) | —                  |

### Realms Keycloak

| Realm           | Client             | Secret                    | Uso                               |
|-----------------|--------------------|---------------------------|-----------------------------------|
| `hub-admin`    | `hubadminservice` | `hubadminservice-secret` | Panel admin, ms-auth, ms-base admin |
| `hub-partner`  | `unilink-api`      | `unilink-api-secret`      | APIs externas, partner M2M        |

### Bases de Datos

| Módulo        | DB                      | Schema  | Vault NS          |
|---------------|-------------------------|---------|-------------------|
| hub-ms-auth  | `hub_auth`             | `admin` | `hub-auth`       |
| hub-ms-base  | `hub_base`          | `public`| `hub-base`    |

### Flujo de Rutas Gateway

| Método | Ruta Gateway                          | Destino                                | Auth requerida      |
|--------|---------------------------------------|----------------------------------------|---------------------|
| POST   | `/oauth2/token`                       | Keycloak `hub-partner` (token proxy)  | Sin auth            |
| POST   | `/partner/v1/qr/decode`               | `ms-base /api/qr/decode`               | JWT `hub-partner`  |
| POST   | `/partner/v1/qr/decode/file`          | `ms-base /api/qr/decode/file`          | JWT `hub-partner`  |
| *      | `/services/hubadminservice/**`       | `ms-auth` (vía Consul discovery)       | JWT `hub-admin`    |
| *      | `/services/hubbaseservice/**`        | `ms-base` (vía Consul discovery)       | JWT `hub-admin`    |

---

## Inicio Rápido (día a día)

```bash
# 1. Levantar herramientas Docker
deploy/scripts/tools.sh --up

# 2. Levantar los tres servicios (terminales separadas)
./gradlew :hub-ms-auth:bootRun --args='--spring.profiles.active=local'   # Terminal A
./gradlew :hub-ms-base:bootRun --args='--spring.profiles.active=local'   # Terminal B
./gradlew :hub-gateway:bootRun --args='--spring.profiles.active=local'   # Terminal C
```

> Levantar el gateway **después** de que ms-auth y ms-base estén registrados en Consul.

---

## Primer Setup (desde cero)

### Prerrequisitos

```bash
java -version          # Java 21+
./gradlew --version    # Gradle 9.x (el wrapper lo descarga)
docker --version       # Docker 20.10+
docker compose version # Docker Compose 2.0+
jq --version           # jq para procesar JSON
```

```bash
# Instalar jq si falta
sudo apt-get install -y jq curl

# Dar permisos al wrapper de Gradle (solo primera vez)
chmod +x gradlew
```

> **PostgreSQL** debe estar instalado en el host. Las apps se conectan a `127.0.0.1:5432`.

### Paso 1 — Levantar el stack de herramientas

```bash
# Primera vez: crear la red Docker compartida
docker network create --driver bridge --opt com.docker.network.driver.mtu=1500 hub-shared

# Primera vez: copiar el .env de ejemplo
cp deploy/tools/.env.example deploy/tools/.env

# Levantar Keycloak, Consul, Vault y Redis
deploy/scripts/tools.sh --up
```

El script espera hasta que todos los contenedores estén `healthy`. Verificar:

```bash
deploy/scripts/tools.sh --info
```

| Servicio  | URL                         |
|-----------|-----------------------------|
| Keycloak  | http://127.0.0.1:8180       |
| Consul    | http://127.0.0.1:8500       |
| Vault     | http://127.0.0.1:8200       |
| Redis     | 127.0.0.1:6379              |

### Paso 2 — Crear las bases de datos

```bash
psql -U postgres -h 127.0.0.1 -c "CREATE DATABASE hub_auth;"
psql -U postgres -h 127.0.0.1 -c "CREATE DATABASE hub_base;"
psql -U postgres -h 127.0.0.1 -d hub_auth -c "CREATE SCHEMA IF NOT EXISTS admin;"
```

### Paso 3 — Seedear Vault

```bash
# Secretos para hub-ms-auth (realm: hub-admin)
DB_NAME=hub_auth DB_HOST=localhost \
  deploy/scripts/vault-seed.sh --ns hub-auth --kc-realm hub-admin

# Secretos para hub-ms-base (realm: hub-admin)
DB_NAME=hub_base DB_HOST=localhost \
  deploy/scripts/vault-seed.sh --ns hub-base --kc-realm hub-admin
```

Verificar:

```bash
docker exec -e VAULT_TOKEN=root -e VAULT_ADDR=http://127.0.0.1:8200 hub-vault \
  vault kv list secret/hub-auth

docker exec -e VAULT_TOKEN=root -e VAULT_ADDR=http://127.0.0.1:8200 hub-vault \
  vault kv list secret/hub-base
```

> **Importante:** el flag `--kc-realm hub-admin` es obligatorio. Sin él, Vault almacena
> el realm incorrecto y los servicios fallan al validar JWTs.

### Paso 4 — Sincronizar Keycloak

Crea realms, clients, roles y usuarios de prueba. Idempotente:

```bash
deploy/scripts/keycloak-sync-admin.sh     # realm hub-admin
deploy/scripts/keycloak-sync-partner.sh   # realm hub-partner
```

Usuarios de prueba creados: `admin` / `admin`.

### Paso 5 — Levantar hub-ms-auth

```bash
./gradlew :hub-ms-auth:bootRun --args='--spring.profiles.active=local'
```

Primera ejecución: Liquibase crea las tablas en el schema `admin` de `hub_auth`.

Verificar:

```bash
psql -U postgres -h 127.0.0.1 -d hub_auth -c "\dt admin.*"
curl -s http://localhost:8083/management/health | jq .status   # → "UP"
```

### Paso 6 — Levantar hub-ms-base

```bash
./gradlew :hub-ms-base:bootRun --args='--spring.profiles.active=local'
```

Primera ejecución: Liquibase crea las tablas en `hub_base`.

Verificar:

```bash
psql -U postgres -h 127.0.0.1 -d hub_base -c "\dt"
curl -s http://localhost:8081/management/health | jq .status   # → "UP"
```

### Paso 7 — Levantar hub-gateway

```bash
./gradlew :hub-gateway:bootRun --args='--spring.profiles.active=local'
```

Verificar que ambos servicios están registrados en Consul antes:

```bash
curl -s http://localhost:8500/v1/catalog/services | jq
```

Deben aparecer `hubadminservice` y `hubbaseservice`.

---

## Tokens JWT

> **Importante:** siempre usar `127.0.0.1` (no `localhost`) para Keycloak.
> El issuer del token debe coincidir exactamente con el configurado en el resource server.

### Token Admin — realm `hub-admin`

Acceso al panel de administración (ms-auth + ms-base admin):

```bash
ADMIN_TOKEN=$(curl -s -X POST \
  http://127.0.0.1:8180/realms/hub-admin/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials&client_id=hubadminservice&client_secret=hubadminservice-secret" \
  | jq -r '.access_token')

echo "Admin token: ${ADMIN_TOKEN:0:60}..."
```

### Token Partner — realm `hub-partner` (vía Gateway)

Clientes externos M2M que consumen la API de desencriptación:

```bash
PARTNER_TOKEN=$(curl -s -X POST \
  http://127.0.0.1:8080/oauth2/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials&client_id=unilink-api&client_secret=unilink-api-secret" \
  | jq -r '.access_token')

echo "Partner token: ${PARTNER_TOKEN:0:60}..."
```

### Token Partner — directo a Keycloak (debug)

```bash
PARTNER_TOKEN=$(curl -s -X POST \
  http://127.0.0.1:8180/realms/hub-partner/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials&client_id=unilink-api&client_secret=unilink-api-secret" \
  | jq -r '.access_token')
```

---

## Verificación

### Health checks directos

```bash
curl -s http://localhost:8083/management/health | jq .status   # ms-auth → "UP"
curl -s http://localhost:8081/management/health | jq .status   # ms-base → "UP"
curl -s http://localhost:8080/management/health | jq .status   # gateway → "UP"
```

### Health checks vía Gateway (requiere token admin)

```bash
# ms-auth vía gateway
curl -s http://localhost:8080/services/hubadminservice/management/health \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq .status

# ms-base vía gateway
curl -s http://localhost:8080/services/hubbaseservice/management/health \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq .status
```

### API principal — desencriptar QR

```bash
curl -s -X POST http://localhost:8080/partner/v1/qr/decode \
  -H "Authorization: Bearer $PARTNER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "inputType": "DECODED_DATA",
    "content": "<contenido_base64_del_qr>"
  }' | jq .
```

---

## Comandos Útiles

```bash
# Ver estado del stack Docker
deploy/scripts/tools.sh --info

# Logs en tiempo real de un servicio
deploy/scripts/tools.sh --logs keycloak
deploy/scripts/tools.sh --logs consul
deploy/scripts/tools.sh --logs vault

# Reiniciar un servicio Docker
deploy/scripts/tools.sh --restart keycloak

# Limpiar todo (borra volúmenes y datos)
deploy/scripts/tools.sh --down -v

# Ver servicios en Consul
curl http://localhost:8500/v1/catalog/services | jq

# Verificar salud de un servicio en Consul
curl http://localhost:8500/v1/health/service/hubadminservice | jq
curl http://localhost:8500/v1/health/service/hubbaseservice | jq

# Swagger UI (solo en perfil local)
# ms-auth: http://localhost:8083/swagger-ui.html
# ms-base: http://localhost:8081/swagger-ui.html
```

---

## Reiniciar desde cero

```bash
# 1. Bajar tools y borrar volúmenes
deploy/scripts/tools.sh --down -v

# 2. Eliminar la red Docker
docker network rm hub-shared

# 3. Borrar bases de datos
psql -U postgres -h 127.0.0.1 -c "DROP DATABASE IF EXISTS hub_auth;"
psql -U postgres -h 127.0.0.1 -c "DROP DATABASE IF EXISTS hub_base;"

# 4. Repetir desde el Paso 1 del setup
```

---

## Notas de Arquitectura

- **Spring Boot 4 + Liquibase 5**: no hay `LiquibaseAutoConfiguration`. Cada módulo
  declara su bean `SpringLiquibase` manualmente en `LiquibaseConfiguration.java`.
- **Perfil `local`**: desactiva Vault config e importación; Consul discovery habilitado
  para que el gateway resuelva `lb://` URIs.
- **Redis fallback**: si Redis no está disponible, `IpWhitelistFilter` y `RateLimitFilter`
  permiten el tráfico (fail-open). El sistema continúa operando sin cache.
- **Issuer JWT**: debe ser consistente. Usar `127.0.0.1:8180` (no `localhost:8180`).
- **commons-pool2**: requerido para Lettuce connection pool con Redis.
- **ConsulConfiguration**: requiere `@ConditionalOnProperty(value = "spring.cloud.consul.enabled")`
  para no fallar cuando Consul está deshabilitado.
- **Health path ms-base**: `/management/health` (no `/actuator/health`).

---

## Troubleshooting

### Gateway devuelve 502 al acceder a `/services/...`

Los servicios no están registrados en Consul.

```bash
curl http://localhost:8500/v1/catalog/services | jq
```

Si `hubadminservice` o `hubbaseservice` no aparecen: el servicio no arrancó
o Consul discovery está deshabilitado en el perfil local.

### Error 401 "JWT issuer mismatch"

El issuer del token debe coincidir exactamente con el configurado en el resource server.

- ✓ Correcto: token generado con `127.0.0.1:8180`, validado con `127.0.0.1:8180`
- ✗ Error: token generado con `localhost:8180`, validado con `127.0.0.1:8180`

### ms-base `/management/health` devuelve 404

Verificar que `application.yml` de ms-base tenga:

```yaml
management:
  endpoints:
    web:
      base-path: /management
```

### Vault con realm incorrecto

Si el realm almacenado en Vault es incorrecto (ej: `hub-base` en vez de `hub-admin`),
los servicios fallan al arrancar. Re-seedear Vault incluyendo `--kc-realm`:

```bash
DB_NAME=hub_base DB_HOST=localhost \
  deploy/scripts/vault-seed.sh --ns hub-base --kc-realm hub-admin
```

### Redis: `Unable to connect`

Verificar que `commons-pool2` esté en `build.gradle`:

```groovy
implementation 'org.apache.commons:commons-pool2'
```

```bash
bash -c 'echo > /dev/tcp/127.0.0.1/6379' && echo "Redis: OK" || echo "Redis: NO ALCANZABLE"
```

### KeycloakEventPoller: WARN 401 periódico

No es fatal. En perfil local está desactivado (`keycloak-poll.enabled: false`).
Para activarlo en otro entorno, configurar el admin client en Keycloak con roles de admin.

---

## Documentación

Ver `docs/` para documentación detallada. Índice en [docs/00-INDEX.md](docs/00-INDEX.md).
