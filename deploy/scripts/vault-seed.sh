#!/usr/bin/env bash
set -euo pipefail

# Seed Vault KV (single/dev mode) con los secretos que leen las apps al arrancar.
#
# En single mode (deploy/tools/ COMPOSE_PROFILES=single) Vault corre en dev mode:
# unsealed, root token preseteado. No requiere operator init/unseal ni AppRole.
# Este script solo escribe los KV. Idempotente (kv put sobreescribe).
#
# Secretos seedeados (secret/hub-auth[-<tenant>]/ o secret/hub-base[-<tenant>]/...):
#   system/redis                host, port, password
#   system/database             host, port, name, username, password
#   keycloak/service-client     auth-server-url, realm, client-id, client-secret (admin realm)
#   keycloak/admin-client       client-id, client-secret, grant-type (Keycloak Admin API)
#   keycloak/partner-client     auth-server-url, realm, client-id (partner realm M2M)
#
# La DB la consumen las apps por env (SPRING_DATASOURCE_URL); system/database se
# seedea igual por compatibilidad y para herramientas que lo lean de Vault.

# --- Defaults --------------------------------------------------------
TENANT_ID="${TENANT_ID:-}"
NAMESPACE_BASE="${NAMESPACE_BASE:-hub-base}"
VAULT_CONTAINER="${VAULT_CONTAINER:-hub-vault}"
VAULT_TOKEN="${VAULT_TOKEN:-root}"
VAULT_ADDR="${VAULT_ADDR:-http://127.0.0.1:8200}"

# Modo de acceso a Vault:
#   exec (default): docker exec al container local (dev/single, deploy/tools).
#   run:            docker run --rm efimero contra un Vault EXTERNO (prod). No
#                   requiere vault CLI en el host; el container se autoremueve.
# Activar modo run con --external <VAULT_ADDR> o VAULT_MODE=run + VAULT_ADDR.
VAULT_MODE="${VAULT_MODE:-exec}"
VAULT_IMAGE="${VAULT_IMAGE:-hashicorp/vault:1.18}"
# Red docker para el container efimero (si el Vault externo solo es alcanzable
# desde una red docker concreta). Vacio = red default bridge.
VAULT_RUN_NETWORK="${VAULT_RUN_NETWORK:-}"

# Host por el que las apps alcanzan tools + postgres (Linux: host-gateway).
TOOLS_HOST="${TOOLS_HOST:-host.docker.internal}"

REDIS_PORT="${REDIS_PORT:-6379}"
REDIS_PASSWORD="${REDIS_PASSWORD:-}"

DB_HOST="${DB_HOST:-$TOOLS_HOST}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-hub}"
DB_USER="${DB_USER:-postgres}"
DB_PASSWORD="${DB_PASSWORD:-postgres}"

# Keycloak URL desde las apps dentro del Docker network (host.docker.internal:8180)
KC_URL="${KC_URL:-http://${TOOLS_HOST}:8180}"
# Admin realm (hub-admin): cliente confidencial para ms-auth y ms-base
KC_SERVICE_CLIENT="${KC_SERVICE_CLIENT:-hubadminservice}"
KC_SERVICE_SECRET="${KC_SERVICE_SECRET:-hubadminservice-secret}"
# Admin API client: mismo cliente (hubadminservice tiene realm-management roles)
KC_ADMIN_CLIENT="${KC_ADMIN_CLIENT:-hubadminservice}"
KC_ADMIN_SECRET="${KC_ADMIN_SECRET:-hubadminservice-secret}"
# Partner realm (hub-partner): para gateway → keycloak token proxy (partner-chain)
KC_PARTNER_CLIENT="${KC_PARTNER_CLIENT:-unilink-api}"
KC_PARTNER_SECRET="${KC_PARTNER_SECRET:-unilink-api-secret}"
KC_PARTNER_REALM="${KC_PARTNER_REALM:-hub-partner}"

# Shared secret del webhook Keycloak -> admin-service (X-Keycloak-Secret header).
AUDIT_KC_SECRET="${AUDIT_KC_SECRET:-dev-keycloak-secret}"

# --- Colors ----------------------------------------------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

info()   { echo -e "${GREEN}[+]${NC} $1"; }
warn()   { echo -e "${YELLOW}[!]${NC} $1"; }
error()  { echo -e "${RED}[x]${NC} $1" >&2; }
header() { echo -e "\n${BOLD}${CYAN}=== $1 ===${NC}"; }
item()   { echo -e "    ${CYAN}->${NC} $1"; }

show_help() {
  cat <<EOF
Usage: $(basename "${BASH_SOURCE[0]}") [OPTIONS]

Seed Vault KV (single/dev mode) with the secrets apps read at startup.

Options:
  --ns <namespace>  Override completo del namespace (ignora NAMESPACE_BASE y --tenant).
                    Ej: --ns hub-auth  --ns hub-base
  --kc-realm <realm> Realm de Keycloak en keycloak/service-client (default: mismo que --ns).
                    Usar cuando el servicio valida JWTs de un realm distinto a su namespace.
                    Ej: --ns hub-base --kc-realm hub-admin
  --tenant <id>     Tenant ID. Namespace = ${NAMESPACE_BASE}-<tenant>. Vacio = single.
  --host <host>     Host por el que las apps alcanzan tools/postgres
                    (default: host.docker.internal)
  --external <addr> Vault EXTERNO (prod). Usa un container vault efimero
                    (docker run --rm) contra <addr>, sin CLI en el host.
                    Requiere VAULT_TOKEN real con permiso de write.
  -h, --help        Show this help

Environment overrides:
  VAULT_MODE        exec (default, container local) | run (externo efimero)
  VAULT_CONTAINER   Vault container name        (default: hub-vault)  [modo exec]
  VAULT_IMAGE       Imagen vault efimera        (default: hashicorp/vault:1.18) [modo run]
  VAULT_RUN_NETWORK Red docker del container efimero (default: bridge) [modo run]
  VAULT_ADDR        Vault address               (default: http://127.0.0.1:8200)
  VAULT_TOKEN       Vault token                 (default: root — solo dev)
  TOOLS_HOST        host de redis/keycloak/db   (default: host.docker.internal)
  REDIS_PORT REDIS_PASSWORD
  DB_HOST DB_PORT DB_NAME DB_USER DB_PASSWORD
  KC_URL KC_SERVICE_CLIENT KC_SERVICE_SECRET KC_ADMIN_CLIENT KC_ADMIN_SECRET
  RECAUDACORE_USERNAME RECAUDACORE_PASSWORD
  AUDIT_KC_SECRET

Examples:
  # dev single-tenant (container local hub-vault, token root)
  $(basename "${BASH_SOURCE[0]}")
  $(basename "${BASH_SOURCE[0]}") --tenant alpha

  # prod: Vault externo via container efimero
  VAULT_TOKEN=<write-token> TOOLS_HOST=redis.prod.local DB_HOST=pg.prod.local \\
    KC_URL=https://kc.prod.local DB_PASSWORD=*** \\
    $(basename "${BASH_SOURCE[0]}") --external https://vault.prod.local:8200
EOF
  exit 0
}

NS_OVERRIDE=""
KC_REALM_OVERRIDE=""

while [ $# -gt 0 ]; do
  case "$1" in
    --ns)       NS_OVERRIDE="$2"; shift 2 ;;
    --kc-realm) KC_REALM_OVERRIDE="$2"; shift 2 ;;
    --tenant)   TENANT_ID="$2"; shift 2 ;;
    --host)     TOOLS_HOST="$2"; KC_URL="http://${TOOLS_HOST}:8180"; DB_HOST="$TOOLS_HOST"; shift 2 ;;
    --external) VAULT_MODE="run"; VAULT_ADDR="$2"; shift 2 ;;
    -h|--help)  show_help ;;
    *) error "Unknown option: $1. Use --help for usage."; exit 1 ;;
  esac
done

# Namespace efectivo: --ns override > NAMESPACE_BASE[-<tenant>]
if [ -n "$NS_OVERRIDE" ]; then
  NS="${NS_OVERRIDE}"
elif [ -n "$TENANT_ID" ]; then
  NS="${NAMESPACE_BASE}-${TENANT_ID}"
else
  NS="${NAMESPACE_BASE}"
fi

# Realm de Keycloak para keycloak/service-client: --kc-realm override > hub-admin
KC_REALM="${KC_REALM_OVERRIDE:-hub-admin}"

v() {
  if [ "$VAULT_MODE" = "run" ]; then
    # Container efimero contra Vault externo. --rm lo autoremueve al terminar.
    docker run --rm -i ${VAULT_RUN_NETWORK:+--network "$VAULT_RUN_NETWORK"} \
      -e VAULT_TOKEN="$VAULT_TOKEN" -e VAULT_ADDR="$VAULT_ADDR" \
      "$VAULT_IMAGE" vault "$@"
  else
    docker exec -i -e VAULT_TOKEN="$VAULT_TOKEN" -e VAULT_ADDR="$VAULT_ADDR" \
      "$VAULT_CONTAINER" vault "$@"
  fi
}

main() {
  echo -e "${BOLD}"
  echo "+-----------------------------------------------+"
  echo "|        Vault Seed (single/dev) - HUB         |"
  echo "+-----------------------------------------------+"
  echo -e "${NC}"
  if [ "$VAULT_MODE" = "run" ]; then
    echo -e "  Mode:       ${CYAN}run (externo, container efimero)${NC}"
    echo -e "  Vault addr: ${CYAN}${VAULT_ADDR}${NC}"
    echo -e "  Image:      ${CYAN}${VAULT_IMAGE}${NC}"
  else
    echo -e "  Mode:       ${CYAN}exec (container local)${NC}"
    echo -e "  Container:  ${CYAN}${VAULT_CONTAINER}${NC}"
  fi
  echo -e "  Namespace:  ${CYAN}secret/${NS}${NC}"
  echo -e "  Tools host: ${CYAN}${TOOLS_HOST}${NC}"

  if ! command -v docker &>/dev/null; then
    error "docker is required"; exit 1
  fi

  if [ "$VAULT_MODE" = "run" ]; then
    if [ -z "$VAULT_ADDR" ]; then
      error "VAULT_ADDR vacio. Pasa --external <addr> o exporta VAULT_ADDR."
      exit 1
    fi
    if [ -z "$VAULT_TOKEN" ] || [ "$VAULT_TOKEN" = "root" ]; then
      error "En modo externo exporta un VAULT_TOKEN real con permiso de WRITE (no 'root' de dev)."
      exit 1
    fi
  else
    if [ -z "$(docker ps -q -f name="^${VAULT_CONTAINER}$" 2>/dev/null)" ]; then
      error "Vault container '${VAULT_CONTAINER}' is not running. Levanta deploy/tools/ primero."
      exit 1
    fi
  fi

  # KV v2: en dev mode 'secret/' ya esta montado; en prod normalmente tambien.
  # enable idempotente por si acaso (falla silenciosa si ya existe o sin permiso).
  v secrets enable -path=secret kv-v2 >/dev/null 2>&1 || true

  header "Seeding secret/${NS}"

  info "system/redis"
  v kv put "secret/${NS}/system/redis" \
    host="$TOOLS_HOST" port="$REDIS_PORT" password="$REDIS_PASSWORD" >/dev/null
  item "host=${TOOLS_HOST} port=${REDIS_PORT}"

  info "system/database"
  v kv put "secret/${NS}/system/database" \
    host="$DB_HOST" port="$DB_PORT" name="$DB_NAME" \
    username="$DB_USER" password="$DB_PASSWORD" >/dev/null
  item "host=${DB_HOST} port=${DB_PORT} name=${DB_NAME}"

  info "keycloak/service-client  (admin realm — ms-auth, ms-base)"
  v kv put "secret/${NS}/keycloak/service-client" \
    auth-server-url="$KC_URL" realm="$KC_REALM" \
    client-id="$KC_SERVICE_CLIENT" client-secret="$KC_SERVICE_SECRET" >/dev/null
  item "auth-server-url=${KC_URL} realm=${KC_REALM} client-id=${KC_SERVICE_CLIENT}"

  info "keycloak/admin-client   (Keycloak Admin API — ms-auth user management)"
  v kv put "secret/${NS}/keycloak/admin-client" \
    auth-server-url="$KC_URL" realm="$KC_REALM" \
    client-id="$KC_ADMIN_CLIENT" client-secret="$KC_ADMIN_SECRET" \
    grant-type=client_credentials >/dev/null
  item "auth-server-url=${KC_URL} realm=${KC_REALM} client-id=${KC_ADMIN_CLIENT}"

  info "keycloak/partner-client (partner realm — gateway token proxy)"
  v kv put "secret/${NS}/keycloak/partner-client" \
    auth-server-url="$KC_URL" realm="$KC_PARTNER_REALM" \
    client-id="$KC_PARTNER_CLIENT" client-secret="$KC_PARTNER_SECRET" >/dev/null
  item "auth-server-url=${KC_URL} realm=${KC_PARTNER_REALM} client-id=${KC_PARTNER_CLIENT}"

  info "audit/keycloak-secret"
  v kv put "secret/${NS}/audit" \
    keycloak-secret="$AUDIT_KC_SECRET" >/dev/null
  item "keycloak-secret=<set>"

  echo ""
  echo -e "${BOLD}${GREEN}=== Vault seeded ===${NC}"
  if [ "$VAULT_MODE" = "run" ]; then
    echo -e "  Verificar: ${CYAN}docker run --rm -e VAULT_TOKEN=*** -e VAULT_ADDR=${VAULT_ADDR} ${VAULT_IMAGE} vault kv list secret/${NS}${NC}"
  else
    echo -e "  Verificar: ${CYAN}docker exec -e VAULT_TOKEN=${VAULT_TOKEN} -e VAULT_ADDR=${VAULT_ADDR} ${VAULT_CONTAINER} vault kv list secret/${NS}${NC}"
  fi
  echo ""
  echo -e "  ${BOLD}Nota:${NC} los clientes externos (partner realm) se crean con keycloak-sync-partner.sh"
}

main "$@"
