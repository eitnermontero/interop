# MDQR Development Deployment

Manual paso a paso para levantar el stack de **aplicaciones** de MDQR en una sola maquina.

Las **tools** (Consul, Redis, Vault, Keycloak) son un stack APARTE con su propio manual
-> ver [`deploy/tools/README.md`](../tools/README.md). Hay que levantarlas **antes** que las apps.

---

## 1. Arquitectura

| Stack | Carpeta | Contiene |
|---|---|---|
| **Tools** | `deploy/tools/` | Consul, Redis, Vault, Keycloak. Single (dev) o cluster (prod-like). |
| **Apps** | `deploy/development/` | `gateway`, `cart-service`, `admin-service`, `report-service` + frontends `adminfe`, `publicfe`. |

- **PostgreSQL es externo** (no lo levanta ningun stack). Las apps lo alcanzan en `host.docker.internal:5432`.
- Apps y tools se enlazan por la red Docker compartida `mdqr_shared` + `host.docker.internal`.
- Los scripts de apoyo viven en `deploy/scripts/` y se invocan **desde la raiz del repo**.

---

## 2. Pre-requisitos

- Docker + Docker Compose v2.
- PostgreSQL accesible (container `postgres` o similar) en `host.docker.internal:5432`.
- `NEXUS_USERNAME` / `NEXUS_PASSWORD` disponibles para `build.sh` (en el `.env` de la raiz del repo o en `~/.gradle/gradle.properties`) — para bajar deps del Nexus.
- `jq` y `curl` (los usan `vault-seed.sh`, `keycloak-sync.sh`, `create-partner.sh`).
  ```bash
  sudo apt-get install -y jq curl
  ```

---

## 3. Mapa de puertos

| App | Host | Container |
|---|---|---|
| gateway | 8000 | 8080 |
| admin-service | 8001 | 8083 |
| cart-service | 8002 | 8081 |
| report-service | 8003 | 8082 |
| adminfe | 3000 | 80 |
| publicfe | 3001 | 80 |

Puertos de tools (Keycloak 8080, Consul 8500, Vault 8200, Redis 6379, ...): ver el README de tools.

---

## 4. PASO 0 - Levantar las tools (obligatorio, primero)

Las tools son un stack independiente. **Segui su manual** y elegi el modo:

-> [`deploy/tools/README.md`](../tools/README.md)

| Modo | Cuando | Resultado |
|---|---|---|
| **single** | dev rapido | 1 redis + 1 consul + 1 vault (dev, unsealed) + keycloak |
| **cluster** | probar prod-like (HA, failover) | 3 consul + 6 redis + 3 vault (raft, requiere unseal) + keycloak |

Cuando termines ese manual deberias tener Consul/Redis/Vault/Keycloak arriba y sanos
(en cluster: el cluster Redis formado y Vault unsealed). Recien ahi segui con las apps.

---

## 5. Despliegue de las apps (paso a paso)

> Todos los comandos `deploy/scripts/...` se corren **desde la raiz del repo**.

### 5.0 Desde cero (limpieza, opcional)

```bash
cd deploy/development && docker compose --env-file .env down -v --remove-orphans
# Recrear la DB (postgres externo). OJO: borra los datos de mdqr.
docker exec postgres psql -U postgres -c \
  "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='mdqr' AND pid <> pg_backend_pid();"
docker exec postgres psql -U postgres -c "DROP DATABASE IF EXISTS mdqr;"
docker exec postgres psql -U postgres -c "CREATE DATABASE mdqr;"
```

### 5.1 Red compartida (una sola vez)

La misma red que usan las tools. Crear si no existe:

```bash
docker network create --driver bridge --opt com.docker.network.driver.mtu=1500 mdqr_shared
```

### 5.2 Crear la DB

```bash
docker exec postgres psql -U postgres -c "CREATE DATABASE mdqr;"   # si no existe
```

No hace falta crear schemas: cart/admin/report corren Liquibase al arrancar.

### 5.3 Seedear Vault

`vault-seed.sh` escribe los secretos que leen las apps (redis, database, keycloak, recaudacore).
Idempotente. En **single** (Vault dev) corre directo:

```bash
deploy/scripts/vault-seed.sh
```

En **cluster** (Vault raft) hay que pasar el token real y apuntar al nodo:

```bash
VAULT_TOKEN=<root-token-del-.vault-init.json> deploy/scripts/vault-seed.sh --external http://<MDQR_HOST_IP>:8200
```

Defaults utiles (overrideables por env inline, sin editar el script): credenciales RecaudaCore
de prueba `Test`/`T3st123*`, DB `mdqr`, client KC `mdqr-api`. Ver
`deploy/scripts/vault-seed.sh --help`.

Verificar (single):

```bash
docker exec -e VAULT_TOKEN=root -e VAULT_ADDR=http://127.0.0.1:8200 mwc-vault \
  vault kv list secret/mdqr
```

### 5.4 Sincronizar Keycloak

`keycloak-sync.sh` crea el realm `mdqr`, el service client `mdqr-api`,
roles/scopes/policies/permissions y los clients del seed (partner demo, admin-service, SPAs).
Los SPA clients ya traen los redirect URIs de los frontends (`localhost:3001`/`3000` + dev server `4200`/`4300`). Idempotente:

```bash
KC_URL=http://localhost:8080 deploy/scripts/keycloak-sync.sh --yes-to-all
```

Usuarios de prueba que crea: `admin`/`admin` y `soboce-test`/`soboce123`. Seed en `deploy/scripts/keycloak-seed/`.

### 5.5 Crear partner demo

El client `cartcore_stage_demo01` ya quedo en Keycloak (paso 5.4). Falta su credencial Genesis en Vault + opcional la fila en DB:

```bash
KC_URL=http://localhost:8080 deploy/scripts/create-partner.sh \
  --name demo \
  --client-id cartcore_stage_demo01 --client-secret cartcore_stage_demo01 \
  --db
```

Pide las credenciales Genesis por prompt. Para otro partner, repetir cambiando `--name`/`--client-id`.

### 5.6 Crear el `.env` de las apps

```bash
deploy/scripts/env-sync.sh development     # crea/actualiza .env desde .env.example (preserva overrides)
```

Revisar en `deploy/development/.env`:

```env
MDQR_KEYCLOAK_URL=http://host.docker.internal:8080
MDQR_KEYCLOAK_EXTERNAL_URL=http://localhost:8080
MDQR_CART_DB_URL=jdbc:postgresql://host.docker.internal:5432/mdqr
# Vault single (dev): TOKEN + root (default). En cluster: token real del .vault-init.json.
MDQR_VAULT_AUTHENTICATION=TOKEN
MDQR_VAULT_TOKEN=root
```

**Redis single vs cluster** (segun como levantaste las tools en el PASO 0):

```env
# --- Single (default): nada que setear, las apps usan host/port standalone ---
#MDQR_REDIS_CLUSTER_ENABLED=false

# --- Cluster: prender la flag + listar los nodos ---
MDQR_REDIS_CLUSTER_ENABLED=true
# Spring Data (cart/report/gateway), formato host:port:
MDQR_REDIS_CLUSTER_NODES=<HOST>:6379,<HOST>:6380,<HOST>:6381,<HOST>:6382,<HOST>:6383,<HOST>:6384
# Redisson L2 (cart/report), formato redis://host:port:
MDQR_REDIS_NODES=redis://<HOST>:6379,redis://<HOST>:6380,redis://<HOST>:6381,redis://<HOST>:6382,redis://<HOST>:6383,redis://<HOST>:6384
MDQR_REDIS_HOST=<HOST>
```

> El cluster de Spring Data lo activa la flag `MDQR_REDIS_CLUSTER_ENABLED` (la lee el bean
> `LettuceConnectionFactory` custom via `application.redis.cluster.enabled`). En single, sin la flag, las apps usan standalone host/port.

### 5.7 Crear dirs de logs

```bash
deploy/scripts/init-logs.sh development
```

### 5.8 Build de imagenes

Siempre `build.sh` desde la raiz. `-as` buildea los 6 servicios:

```bash
./build.sh -d -j --no-push -as            # 4 java + 2 frontends, tags dev :latest, sin push

# Per-servicio:
./build.sh -d -j --no-push -s gateway cart admin report
./build.sh -d -j --no-push -s adminfe publicfe
```

### 5.9 Levantar las apps

```bash
cd deploy/development
docker compose --env-file .env up -d
```

### 5.10 Validar

Health (cada uno debe responder 200 UP):

```bash
curl -fsS http://localhost:8000/management/health   # gateway
curl -fsS http://localhost:8002/management/health   # cart
curl -fsS http://localhost:8001/management/health   # admin
curl -fsS http://localhost:8003/management/health   # report
```

Token de partner end-to-end (via gateway):

```bash
curl -s -X POST http://localhost:8000/services/mdqrcartservice/api/v1/auth/token \
  -H 'X-Request-Id: test-001' -H 'Content-Type: application/json' \
  -d '{"clientId":"cartcore_stage_demo01","clientSecret":"cartcore_stage_demo01"}'
```

El JWT debe tener `iss: .../realms/mdqr` y la response el header `X-Request-Id`.

Frontends (login con `soboce-test`/`soboce123`):

```
adminfe  -> http://localhost:3000
publicfe -> http://localhost:3001
```

---

## 6. Multi-tenant (resumen)

Repetir el flujo con un `.env.<tenant>` y `TENANT_ID` seteado. Cada tenant aisla todo:

| Recurso | Patron |
|---|---|
| Container names | `mwc-<svc>-<tenant>` |
| Consul service-name | `mdqr<svc>-<tenant>` |
| Postgres DB | `mdqr_<tenant>` |
| Keycloak realm | `mdqr-<tenant>` |
| Vault namespace | `secret/mdqr-<tenant>/...` |
| Log dir | `${LOG_DIR}-<tenant>/...` |

```bash
docker exec postgres psql -U postgres -c "CREATE DATABASE mdqr_alpha;"
deploy/scripts/vault-seed.sh --tenant alpha
KC_URL=http://localhost:8080 deploy/scripts/keycloak-sync.sh --tenant alpha
KC_URL=http://localhost:8080 deploy/scripts/create-partner.sh --tenant alpha --name demo --db
deploy/scripts/env-sync.sh development -f .env.alpha   # editar TENANT_ID, puertos, MDQR_CART_DB_URL=.../mdqr_alpha
deploy/scripts/init-logs.sh development --env-file .env.alpha
docker compose --env-file .env.alpha up -d
```

Sin `TENANT_ID` -> single-tenant (sin suffix).

---

## 7. Scripts de apoyo (`deploy/scripts/`)

| Script | Que hace |
|---|---|
| `tools.sh` | Wrapper del stack tools: `up`, `down`, `info`, `logs`. |
| `env-sync.sh <env> [-f .env.<v>]` | Crea/actualiza `.env` desde `.env.example` preservando overrides. |
| `vault-seed.sh [--tenant <id>] [--external <addr>]` | Seedea Vault KV (redis, database, keycloak, recaudacore). |
| `keycloak-sync.sh [--tenant <id>] [--yes-to-all]` | Crea realm + clients + roles + authz desde `keycloak-seed/`. Idempotente. |
| `create-partner.sh --name <n> [--db]` | Crea partner (Keycloak client + Vault genesis + DB rows). |
| `init-logs.sh <env> [--env-file ...]` | Crea dirs de logs con permisos. |

Detalle de Keycloak: `deploy/scripts/KEYCLOAK_DEPLOYMENT.md`.

---

## 8. Logging

- Pattern: `[traceId=... spanId=... reqId=... clientIp=...]`. `reqId` viene del header `X-Request-Id` (si falta lo genera el gateway) y se propaga end-to-end.
- Cada servicio escribe JSON (Logstash encoder) en `${LOG_DIR}/<svc>/`. Niveles via env: `MDQR_<SVC>_LOG_LEVEL` (default INFO).
- Gateway access log: `/management/**` y `/actuator/**` -> TRACE; 4xx/5xx -> WARN; resto -> INFO.

---

## 9. Troubleshooting

### Gateway devuelve 404 a `/services/mdqrcartservice/...`
El discovery locator levanta las rutas con delay. Reiniciar el gateway cuando el upstream este healthy:
```bash
docker restart mdqr-gateway
```

### JWT rechazado por `iss` mismatch
El `iss` del JWT toma el host con que el container llama a Keycloak (`MDQR_KEYCLOAK_URL`); el resource server lo valida contra `MDQR_KEYCLOAK_EXTERNAL_URL`. Ambos deben resolver al mismo issuer.

### Login del frontend: "Invalid parameter: redirect_uri"
El SPA client en Keycloak no tiene registrado el puerto del frontend. Los redirect URIs viven en `deploy/scripts/keycloak-seed/spa-clients.csv` (deben incluir `localhost:3001`/`3000`); re-correr `keycloak-sync.sh` tras editarlos (borra/recrea el client si ya existia).

### cart/report no arrancan: "RedisURIs must not be empty"
La flag de cluster quedo a medias. En single, `MDQR_REDIS_CLUSTER_ENABLED` debe estar `false`/ausente. En cluster, `true` + `MDQR_REDIS_CLUSTER_NODES` con los 6 nodos.

### Apps no arrancan: "Vault is sealed"
Single (dev) es auto-unsealed; si se sello tras reinicio del host, recrea el container y re-seedea. Cluster: re-unseal con `deploy/tools/scripts/vault-cluster-init.sh`.

### Bajar todo
```bash
cd deploy/development && docker compose --env-file .env down   # -v para borrar volumes
# tools: ver deploy/tools/README.md
```
