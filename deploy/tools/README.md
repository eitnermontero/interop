# Stack de Tools (Consul, Redis, Vault, Keycloak)

Tools aisladas del stack de aplicacion. Las apps (`mdqr-gateway`, `mdqr-cart-service`, `mdqr-report-service`) conectan aca via IP del host (`MDQR_HOST_IP`).

## Modos

Se selecciona via `COMPOSE_PROFILES` en `.env`:

| Modo | COMPOSE_PROFILES | Componentes |
|---|---|---|
| **single (default)** | `single` | 1 redis + 1 consul + 1 vault (dev) + keycloak |
| **cluster (prod-like)** | `cluster` | 3 consul + 6 redis (3M+3R) + 3 vault (raft HA) + keycloak |

Keycloak es **always-on** (sin profile).

## Setup inicial

```bash
cd deploy/tools

# Crear red compartida una sola vez
docker network create --driver bridge --opt com.docker.network.driver.mtu=1500 mdqr-shared

# Copiar y editar .env
cp .env.example .env
# Editar: setear MDQR_TOOLS_BIND_IP=0.0.0.0
#
# MDQR_HOST_IP: SOLO requerido en cluster mode (Redis cluster-announce-ip y Vault
# api_addr). En single mode no se usa — dejalo sin setear. Lo validan
# redis-cluster-init.sh / vault-cluster-init.sh al formar el cluster.
```

## Levantar

```bash
docker compose up -d
```

Compose lee `COMPOSE_PROFILES` del `.env` y arranca el set correspondiente.

En **single mode** no necesitas `MDQR_HOST_IP`: los servicios cluster no se crean y el
compose usa `${MDQR_HOST_IP:-}` para no romper el parse.

## Cluster: init + validacion

### Redis cluster

Tras levantar los 6 nodos, formar el cluster (UNA vez, idempotente):

```bash
./scripts/redis-cluster-init.sh
```

Valida estado:

```bash
./scripts/redis-cluster-check.sh
```

### Vault cluster (raft HA)

Tras levantar los 3 nodos, init + unseal (UNA vez, idempotente):

```bash
./scripts/vault-cluster-init.sh
```

Guarda las unseal keys + root token en `.vault-init.json` (gitignored). **Para prod**: mover a un sitio seguro y borrarlas del disco.

Re-unseal tras reinicio:

```bash
./scripts/vault-cluster-init.sh   # idempotente: solo unseal de nodos sealed
```

## Validar

```bash
# Single Redis:
docker run --rm redis:8.8 redis-cli -h $MDQR_HOST_IP -p 6379 ping

# Cluster Redis:
docker run --rm redis:8.8 redis-cli -h $MDQR_HOST_IP -p 6379 -c set hello world
docker run --rm redis:8.8 redis-cli -h $MDQR_HOST_IP -p 6379 cluster info | grep cluster_state

# Consul:
curl -fsS http://$MDQR_HOST_IP:8500/v1/status/leader
curl -fsS http://$MDQR_HOST_IP:8500/v1/catalog/nodes | jq

# Vault single:
curl -fsS http://$MDQR_HOST_IP:8200/v1/sys/health | jq

# Vault cluster (cualquier nodo):
curl -fsS http://$MDQR_HOST_IP:8200/v1/sys/health | jq
curl -fsS http://$MDQR_HOST_IP:8210/v1/sys/health | jq
curl -fsS http://$MDQR_HOST_IP:8220/v1/sys/health | jq

# Keycloak:
curl -fsS http://$MDQR_HOST_IP:8080/realms/master/.well-known/openid-configuration | head -c 200
```

## Apps conectandose

En el `.env` de los stacks de aplicacion:

```env
# Consul
SPRING_CLOUD_CONSUL_HOST=192.168.0.12
SPRING_CLOUD_CONSUL_PORT=8500

# Single redis:
SPRING_DATA_REDIS_URL=redis://192.168.0.12:6379

# Cluster redis (multi-node URL):
SPRING_DATA_REDIS_CLUSTER_NODES=192.168.0.12:6379,192.168.0.12:6380,192.168.0.12:6381,192.168.0.12:6382,192.168.0.12:6383,192.168.0.12:6384

# Vault single (dev token):
SPRING_CLOUD_VAULT_URI=http://192.168.0.12:8200
SPRING_CLOUD_VAULT_TOKEN=root

# Vault cluster (apuntar a un nodo; clientes que sigan redirects pueden usar cualquiera):
SPRING_CLOUD_VAULT_URI=http://192.168.0.12:8200

# Keycloak:
KEYCLOAK_URL=http://192.168.0.12:8080
```

## Migrar entre modos

Single ↔ cluster requiere downtime + data loss (storage diferente entre los dos):

```bash
docker compose down -v   # WARNING: borra los volumes
# Editar .env: COMPOSE_PROFILES=cluster (o single)
docker compose up -d
./scripts/redis-cluster-init.sh   # solo si vas a cluster
./scripts/vault-cluster-init.sh   # solo si vas a cluster
```

## Puertos por default

| Servicio | Single | Cluster |
|---|---|---|
| Consul HTTP | 8500 | 8500/8501/8502 |
| Redis | 6379 | 6379-6384 (+ bus 16379-16384) |
| Vault API | 8200 | 8200/8210/8220 |
| Vault Cluster (raft) | — | 8201/8211/8221 |
| Keycloak | 8080/8443 | 8080/8443 |
