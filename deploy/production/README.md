# Stack de Production

Stack de aplicacion: `mdqr-gateway`, `mdqr-decode-service`. Diseñado para deploy **multi-host** + **multi-tenant**.

A diferencia del `deploy/development/`:

- **Sin postgres / sin tools embebidos** — apuntan a infra externa (Consul, Redis, Vault, Keycloak, Postgres en otros hosts).
- **NODE_INDEX** sufija containers para correr varios nodos del mismo servicio en distintos hosts sin chocar nombres.
- **Profiles `*-extra`** para escalar verticalmente en un mismo host.
- **Imagen + version explicitas** por release (no `latest`).
- **Vault con AppRole** (no TOKEN) por default.

---

## Pre-requisitos

- Docker + Docker Compose v2 en cada host del cluster.
- Red overlay/bridge `mdqr-prod-net` (se crea sola).
- **Tools externos accesibles**:
  - Consul cluster (3+ nodos) — endpoint via `MDQR_CONSUL_HOST`
  - Redis (single o cluster) — endpoint via `MDQR_REDIS_HOST`
  - Vault con AppRole habilitado por servicio — endpoint via `MDQR_VAULT_HOST`
  - Keycloak con realms `mdqr[-<tenant>]` listos — endpoint via `MDQR_KEYCLOAK_URL`
- **PostgreSQL externo** con DBs `mdqr[-<tenant>]` creadas.
- **Balanceador externo** (nginx, HAProxy, ALB) frente a los gateways de cada host.

---

## Flujo de deploy

### 1. Build + push de imagenes (CI o local)

Desde el root del repo:

```bash
# Push a registry prod (cr.sintesis.com.bo/mdqr/...:<ver>)
./build.sh -p -j -as

# O dev registry (cr.sintesis.com.bo/mdqr-dev/...) si se usa para staging
./build.sh -d -j -as
```

### 2. En cada host del cluster — preparar `.env`

```bash
cd deploy/production
deploy/scripts/env-sync.sh production
# o por tenant:
deploy/scripts/env-sync.sh production -f .env.tenant1
```

Mínimo a setear en cada `.env`:

```env
TENANT_ID=tenant1                 # o vacio para single-tenant
NODE_INDEX=1                      # diferente por host (1, 2, 3...)
MDQR_GATEWAY_IMAGE=cr.sintesis.com.bo/mdqr/mdqr-gateway
MDQR_GATEWAY_VERSION=1.0.5
MDQR_CART_IMAGE=cr.sintesis.com.bo/mdqr/mdqr-decode-service
MDQR_CART_VERSION=1.0.5

MDQR_CONSUL_HOST=consul.prod.local
MDQR_REDIS_HOST=redis.prod.local
MDQR_VAULT_HOST=vault.prod.local
MDQR_VAULT_AUTHENTICATION=APPROLE
MDQR_VAULT_ROLE_ID=<approle-role-id>
MDQR_VAULT_SECRET_ID=<approle-secret-id>

MDQR_KEYCLOAK_URL=https://auth.midominio.com
MDQR_GATEWAY_CORS_ALLOWED_ORIGINS=https://app.midominio.com

MDQR_CART_DB_URL=jdbc:postgresql://db.prod.local:5432/mdqr_tenant1
MDQR_CART_DB_USER=mdqr_app
MDQR_CART_DB_PASSWORD=<rotar-en-vault>
```

### 3. Crear dirs de logs

```bash
deploy/scripts/init-logs.sh production --env-file .env
# o por tenant:
deploy/scripts/init-logs.sh production --env-file .env.tenant1
```

### 4. Seedear Vault KV (una vez por tenant)

El **bootstrap** del Vault (montar KV v2, habilitar AppRole, policies + roles por
servicio) lo hace infra una sola vez — no es tarea de un script de deploy.

Para escribir/actualizar los **KV** que leen las apps (`system/redis`,
`system/database`, `keycloak/service-client`, `keycloak/admin-client`) usa
`vault-seed.sh` en **modo externo**: levanta un container vault efimero
(`docker run --rm`) contra el Vault de prod, sin instalar el CLI en el host.

Necesitas un `VAULT_TOKEN` con permiso de **write** (no el AppRole read-only de las apps):

```bash
VAULT_TOKEN=<write-token> \
  TOOLS_HOST=redis.prod.local \
  DB_HOST=db.prod.local DB_NAME=mdqr_tenant1 DB_USER=mdqr_app DB_PASSWORD=*** \
  KC_URL=https://auth.midominio.com KC_SERVICE_SECRET=*** KC_ADMIN_SECRET=*** \
  deploy/scripts/vault-seed.sh --tenant tenant1 \
    --external https://vault.prod.local:8200
```

Escribe bajo `secret/mdqr[-<tenant>]/`. Si el Vault solo es alcanzable
desde una red docker concreta, agrega `VAULT_RUN_NETWORK=<red>`. Los partners
(genesis + client secret) se crean aparte con `create-partner.sh`.

### 5. Levantar

```bash
docker compose --env-file .env up -d

# Multi-tenant — un compose por archivo:
docker compose --env-file .env.tenant1 up -d
docker compose --env-file .env.tenant2 up -d
```

### 6. Validar

```bash
# Cada host con su NODE_INDEX
curl -fsS http://<host>:8080/management/health

# Auth end-to-end via load balancer
curl -s -X POST https://<lb-host>/services/mdqrcartservice/api/v1/auth/token \
  -H 'Content-Type: application/json' \
  -d '{"clientId":"<client>","clientSecret":"<secret>"}'
```

---

## Multi-host

Cada host ejecuta su propio `docker compose` con un `NODE_INDEX` distinto. Los containers se llaman:

| Host | NODE_INDEX | Container gateway | Container cart-service |
|---|---|---|---|
| server1 | 1 | `mdqr-gateway-1` | `mdqr-decode-service-1` |
| server2 | 2 | `mdqr-gateway-2` | `mdqr-decode-service-2` |
| server3 | 3 | `mdqr-gateway-3` | `mdqr-decode-service-3` |

Con tenant:
| server1 + tenant1 | 1 | `mdqr-gateway-tenant1-1` | `mdqr-decode-service-tenant1-1` |

En Consul, todos comparten **el mismo service-name** (`mdqrgateway[-tenant]`, `mdqrcartservice[-tenant]`) — el LB interno del gateway descubre todas las replicas y rota entre ellas.

---

## Escala vertical (nodos extra en un mismo host)

Para correr un segundo nodo del mismo servicio en un host (sin agregar hardware):

```env
# .env del host
NODE_INDEX=1
NODE_INDEX_GATEWAY_EXTRA=11      # gateway extra usa este indice
NODE_INDEX_CART_EXTRA=11
COMPOSE_PROFILES=gateway-extra,cart-extra
MDQR_GATEWAY_PORT=8080
MDQR_GATEWAY_PORT_EXTRA=8086      # puerto distinto al principal
```

Resultado: 2 containers de gateway (`mdqr-gateway-1` + `mdqr-gateway-11`) + 2 de cart-service en el mismo host, ambos registrados en Consul.

---

## Multi-tenant

`TENANT_ID` se propaga a todo el stack (igual que en `deploy/development/`):

| Recurso | Patron |
|---|---|
| Container names | `mdqr-gateway-<tenant>-<NODE_INDEX>` |
| Consul service-name | `mdqrgateway-<tenant>`, `mdqrcartservice-<tenant>` |
| Postgres DB | `mdqr_<tenant>` |
| Keycloak realm | `mdqr-<tenant>` |
| Vault namespace | `secret/mdqr-<tenant>/...` |
| Log dir | `~/logs/mdqr-<tenant>/{gateway,cart-service}/` |

Un compose por tenant — corriendo en paralelo:

```bash
docker compose --env-file .env.tenant1 up -d
docker compose --env-file .env.tenant2 up -d
```

---

## Logging

Mismo pattern que `development/` (Logstash JSON file + console con `[traceId=... spanId=... reqId=... clientIp=...]`).

Archivos en host: `${LOG_DIR}${TENANT_ID:+-}${TENANT_ID}/{gateway,cart-service}/<service>-<NODE_INDEX>.log`.

Recomendación prod: que un agente externo (Filebeat, Vector, Promtail) lea esos JSON y los ingieste a Elastic/Loki.

---

## Releases

Cambio de version sin downtime (rolling):

1. Build de la nueva imagen — `./build.sh -p -j -as`
2. Subir un host a la vez:
   ```bash
   # Host 1
   sed -i 's/^MDQR_GATEWAY_VERSION=.*/MDQR_GATEWAY_VERSION=1.0.6/' .env
   sed -i 's/^MDQR_CART_VERSION=.*/MDQR_CART_VERSION=1.0.6/' .env
   docker compose --env-file .env up -d --force-recreate
   # esperar health UP, validar trafico, repetir en host 2, etc.
   ```

---

## Troubleshooting

### Consul no acepta el cluster (split-brain)

Cada `NODE_INDEX` debe ser unico globalmente — el `instance-id` en Consul lo lleva. Si dos hosts mandan el mismo `NODE_INDEX`, el segundo sobrescribe el primero en Consul. Revisar `.env` de cada host.

### Vault AppRole devuelve 403

El AppRole de prod tiene `secret_id_ttl` finito (lo define infra al crear el role). Rotar:

```bash
vault write -f auth/approle/role/mdqr-decode-service/secret-id
# distribuir el nuevo secret-id a los .env
```

### Gateway 404 luego de scaling

Tras agregar replicas, dar tiempo al discovery locator del gateway (~30s) o restart de los gateways existentes. Mejorable habilitando `spring.cloud.consul.discovery.catalog-services-watch.enabled=true` si el delay es crítico.

### Down con cuidado

`docker compose down` mata el container — el LB externo debe deregistrarlo primero. Recomendado: `docker compose stop` + esperar deregistro de Consul + `docker compose rm -f`.
