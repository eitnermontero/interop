#!/usr/bin/env bash
# Crea los directorios de logs que las apps bind-mountean. Lee el .env del
# environment elegido para resolver LOG_DIR y TENANT_ID. Ajusta permisos para
# que el container (non-root) pueda escribir.
#
# Usage:
#   bash deploy/scripts/init-logs.sh <env>                    # usa .env
#   bash deploy/scripts/init-logs.sh <env> --env-file <path>  # usa .env custom (ej: .env.tenant1)
#   bash deploy/scripts/init-logs.sh -h
#
# <env> = development | production
#
# Que crea (cada path es ${LOG_DIR}${TENANT_ID:+-}${TENANT_ID}/<svc>/):
#   - gateway/        (logs mdqr-gateway)
#   - decrypt-service/   (logs mdqr-decode-service)
#   - admin-service/  (logs mdqr-admin-service)
#   - auth-service/ (logs mdqr-auth-service)
#
# Permisos: 0775 con chown a uid del container si hay privilegios, sino 0777.
set -euo pipefail

ENV_NAME=""
ENV_FILE=""

usage() {
  sed -n '2,/^set/p' "$0" | sed 's/^# \?//' | sed '$d'
}

while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --env-file) ENV_FILE="$2"; shift 2 ;;
    development|production) ENV_NAME="$1"; shift ;;
    *) echo "Unknown arg: $1" >&2; usage; exit 2 ;;
  esac
done

if [ -z "$ENV_NAME" ]; then
  echo "Missing required <env>" >&2; usage; exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TARGET_DIR="$SCRIPT_DIR/../$ENV_NAME"
[ -d "$TARGET_DIR" ] || { echo "No existe $TARGET_DIR" >&2; exit 1; }

cd "$TARGET_DIR"

if [ -z "$ENV_FILE" ]; then
  ENV_FILE=".env"
fi
[ -f "$ENV_FILE" ] || { echo "$TARGET_DIR/$ENV_FILE no existe" >&2; exit 1; }

LOG_DIR=""; TENANT_ID=""
while IFS='=' read -r key val; do
  case "$key" in
    LOG_DIR) LOG_DIR="${val%\"}"; LOG_DIR="${LOG_DIR#\"}" ;;
    TENANT_ID) TENANT_ID="${val%\"}"; TENANT_ID="${TENANT_ID#\"}" ;;
  esac
done < <(grep -E '^[A-Z_]+=' "$ENV_FILE" || true)

LOG_DIR="${LOG_DIR:-$HOME/logs/mdqr}"
LOG_DIR="${LOG_DIR/#\~/$HOME}"
LOG_DIR="${LOG_DIR/\$HOME/$HOME}"

SUFFIX=""
[ -n "$TENANT_ID" ] && SUFFIX="-$TENANT_ID"
BASE="${LOG_DIR}${SUFFIX}"

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
echo -e "${YELLOW}Init logs para $ENV_NAME (env=$ENV_FILE)${NC}"
echo "  LOG_DIR resuelto: $BASE"
echo "  TENANT_ID:        ${TENANT_ID:-<vacio>}"
echo

CONTAINER_UID="${CONTAINER_UID:-1000}"
CONTAINER_GID="${CONTAINER_GID:-0}"

ensure_traversal() {
  local p="$1"
  while [ "$p" != "/" ] && [ -n "$p" ]; do
    chmod o+x "$p" 2>/dev/null || true
    p="$(dirname "$p")"
  done
}
ensure_traversal "$BASE"

# Permisos sobre la raiz del tenant — los subdirs heredan por rolling policy del logback.
SUBDIRS=(gateway decrypt-service admin-service auth-service)
for sub in "${SUBDIRS[@]}"; do
  d="$BASE/$sub"
  if [ -d "$d" ]; then
    echo -e "  ${GREEN}✓${NC} ya existe  $d"
  else
    mkdir -p "$d"
    echo -e "  ${GREEN}+${NC} creado     $d"
  fi
done

if chown -R "$CONTAINER_UID:$CONTAINER_GID" "$BASE" 2>/dev/null; then
  chmod -R 775 "$BASE"
  perm_msg="chown -R $CONTAINER_UID:$CONTAINER_GID + chmod -R 775"
else
  chmod -R 777 "$BASE"
  perm_msg="chmod -R 777 (fallback — sin privilegios para chown a uid $CONTAINER_UID)"
fi
echo -e "  ${GREEN}→${NC} permisos en $BASE — $perm_msg"

echo
echo -e "  ${YELLOW}Container uid asumido:${NC} $CONTAINER_UID:$CONTAINER_GID (override con CONTAINER_UID/GID env)"

echo
echo -e "${GREEN}Listo.${NC} Ahora podés correr: docker compose up -d"
