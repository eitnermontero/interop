#!/usr/bin/env bash
# Wrapper para operar el stack de tools (Consul + Redis + Vault + Keycloak)
# sin tener que cd a deploy/tools/. Lee la config de deploy/tools/.env y muestra
# los puertos/URLs reales para conectar.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLS_DIR="$(cd "$SCRIPT_DIR/../tools" && pwd)"
ENV_FILE="$TOOLS_DIR/.env"

if [ ! -f "$ENV_FILE" ]; then
  echo "ERROR: $ENV_FILE no existe. Copialo desde .env.example primero:"
  echo "  cp $TOOLS_DIR/.env.example $ENV_FILE"
  exit 1
fi

env_get() {
  local key="$1" default="${2:-}"
  local val
  val=$(grep -E "^${key}=" "$ENV_FILE" 2>/dev/null | tail -1 | cut -d= -f2-)
  echo "${val:-$default}"
}

PROFILES=$(env_get COMPOSE_PROFILES single)
HOST_IP=$(env_get MDQR_HOST_IP "")
BIND_IP=$(env_get MDQR_TOOLS_BIND_IP 127.0.0.1)
[ -z "$HOST_IP" ] && HOST_IP=$(ip route get 1.1.1.1 2>/dev/null | awk '{print $7; exit}')

KC_PORT=$(env_get KEYCLOAK_HTTP_PORT 8080)
KC_HTTPS_PORT=$(env_get KEYCLOAK_HTTPS_PORT 8443)

CONSUL_PORT=$(env_get MDQR_CONSUL_HTTP_PORT 8500)
CONSUL_S1=$(env_get MDQR_CONSUL_S1_HTTP_PORT 8500)
CONSUL_S2=$(env_get MDQR_CONSUL_S2_HTTP_PORT 8501)
CONSUL_S3=$(env_get MDQR_CONSUL_S3_HTTP_PORT 8502)

REDIS_PORT=$(env_get MDQR_REDIS_PORT 6379)
REDIS_M1=$(env_get MDQR_REDIS_M1_PORT 6379)
REDIS_M2=$(env_get MDQR_REDIS_M2_PORT 6380)
REDIS_M3=$(env_get MDQR_REDIS_M3_PORT 6381)
REDIS_R1=$(env_get MDQR_REDIS_R1_PORT 6382)
REDIS_R2=$(env_get MDQR_REDIS_R2_PORT 6383)
REDIS_R3=$(env_get MDQR_REDIS_R3_PORT 6384)

VAULT_PORT=$(env_get MDQR_VAULT_PORT 8200)
VAULT_1_PORT=$(env_get MDQR_VAULT_1_PORT 8200)
VAULT_2_PORT=$(env_get MDQR_VAULT_2_PORT 8210)
VAULT_3_PORT=$(env_get MDQR_VAULT_3_PORT 8220)
VAULT_DEV_TOKEN=$(env_get MDQR_VAULT_DEV_ROOT_TOKEN root)

KC_USER=$(env_get KEYCLOAK_ADMIN_USER admin)
KC_PASS=$(env_get KEYCLOAK_ADMIN_PASSWORD admin)

DC=(docker compose --env-file "$ENV_FILE" -f "$TOOLS_DIR/docker-compose.yml")

if [ -t 1 ]; then
  B=$'\033[1m'; D=$'\033[2m'; R=$'\033[0m'
  CYAN=$'\033[36m'; GREEN=$'\033[32m'; RED=$'\033[31m'; YEL=$'\033[33m'
  BLUE=$'\033[34m'; MAG=$'\033[35m'
else
  B=''; D=''; R=''; CYAN=''; GREEN=''; RED=''; YEL=''; BLUE=''; MAG=''
fi

LAN_OPEN=false
[[ "$BIND_IP" == "0.0.0.0" ]] && LAN_OPEN=true

is_running() {
  docker ps --filter "name=^mdqr-keycloak$" --format '{{.Names}}' | grep -q .
}

url_line() {
  local port="$1" suffix="${2:-}"
  if $LAN_OPEN; then
    printf "%s%s%s   %s%s%s" \
      "$BLUE" "http://${HOST_IP}:${port}" "$R" \
      "$BLUE" "http://localhost:${port}" "$R"
  else
    printf "%s%s%s" "$BLUE" "http://localhost:${port}" "$R"
  fi
  [ -n "$suffix" ] && printf "   %s%s%s" "$D" "$suffix" "$R"
}

hp_line() {
  local port="$1" suffix="${2:-}"
  if $LAN_OPEN; then
    printf "%s%s:%s%s   %s%s%s" \
      "$MAG" "$HOST_IP" "$port" "$R" \
      "$MAG" "localhost:${port}" "$R"
  else
    printf "%s%s%s" "$MAG" "localhost:${port}" "$R"
  fi
  [ -n "$suffix" ] && printf "   %s%s%s" "$D" "$suffix" "$R"
}

label() { printf "  %s%-17s%s" "$B" "$1" "$R"; }

cmd_info() {
  local status_chip
  if is_running; then
    status_chip="${GREEN}●${R} ${B}running${R}"
  else
    status_chip="${RED}●${R} ${B}stopped${R}  ${D}(tools.sh up)${R}"
  fi

  printf "\n  %sTools stack%s   %s\n" "$B" "$R" "$status_chip"
  printf "  %smode%s %s%s%s   %sbind%s %s%s%s   %shost-ip%s %s%s%s\n" \
    "$D" "$R" "$CYAN" "$PROFILES" "$R" \
    "$D" "$R" "$CYAN" "$BIND_IP" "$R" \
    "$D" "$R" "$CYAN" "$HOST_IP" "$R"
  printf "  %s%s%s\n\n" "$D" "-------------------------------------------------------------------------" "$R"

  if ! $LAN_OPEN; then
    printf "  %s⚠ bind=127.0.0.1 → puertos solo accesibles desde localhost del host.%s\n" "$YEL" "$R"
    printf "  %s  Apps en otra red Docker no podran conectar. Setear MDQR_TOOLS_BIND_IP=0.0.0.0 en .env%s\n\n" "$YEL" "$R"
  fi

  label "Keycloak"; printf "%s\n" "$(url_line "$KC_PORT")"
  printf "  %-17s %suser:%s %s%s%s   %spass:%s %s%s%s\n" \
    "" "$D" "$R" "$YEL" "$KC_USER" "$R" \
    "$D" "$R" "$YEL" "$KC_PASS" "$R"

  if [[ "$PROFILES" == *cluster* ]]; then
    label "Consul cluster"; printf "%s\n" "$(url_line "$CONSUL_S1" "server-1")"
    printf "  %-17s %s\n" "" "$(url_line "$CONSUL_S2" "server-2")"
    printf "  %-17s %s\n" "" "$(url_line "$CONSUL_S3" "server-3")"
    label "Redis cluster"; printf "%s\n" "$(hp_line "$REDIS_M1" "master-1")"
    printf "  %-17s %s\n" "" "$(hp_line "$REDIS_M2" "master-2")"
    printf "  %-17s %s\n" "" "$(hp_line "$REDIS_M3" "master-3")"
    printf "  %-17s %s\n" "" "$(hp_line "$REDIS_R1" "replica-1")"
    printf "  %-17s %s\n" "" "$(hp_line "$REDIS_R2" "replica-2")"
    printf "  %-17s %s\n" "" "$(hp_line "$REDIS_R3" "replica-3")"
    label "Vault cluster"; printf "%s\n" "$(url_line "$VAULT_1_PORT" "node-1")"
    printf "  %-17s %s\n" "" "$(url_line "$VAULT_2_PORT" "node-2")"
    printf "  %-17s %s\n" "" "$(url_line "$VAULT_3_PORT" "node-3")"
    if [ -f "$TOOLS_DIR/.vault-init.json" ]; then
      printf "  %-17s %sroot token guardado en deploy/tools/.vault-init.json%s\n" \
        "" "$D" "$R"
    else
      printf "  %-17s %s⚠ no init: corré tools.sh init-vault%s\n" "" "$YEL" "$R"
    fi
  else
    label "Consul"; printf "%s\n" "$(url_line "$CONSUL_PORT")"
    label "Redis"; printf "%s\n" "$(hp_line "$REDIS_PORT")"
    label "Vault"; printf "%s\n" "$(url_line "$VAULT_PORT" "dev mode")"
    printf "  %-17s %sroot token:%s %s%s%s\n" \
      "" "$D" "$R" "$YEL" "$VAULT_DEV_TOKEN" "$R"
  fi

  printf "\n  %sApps env%s %s(paste en deploy/development/.env):%s\n" \
    "$B" "$R" "$D" "$R"
  if [[ "$PROFILES" == *cluster* ]]; then
    cat <<EOF
    MDQR_CONSUL_HOST=$HOST_IP
    MDQR_CONSUL_PORT=$CONSUL_S1
    MDQR_REDIS_HOST=$HOST_IP
    MDQR_REDIS_PORT=$REDIS_M1
    MDQR_VAULT_HOST=$HOST_IP
    MDQR_VAULT_PORT=$VAULT_1_PORT
    MDQR_VAULT_AUTHENTICATION=APPROLE
    MDQR_KEYCLOAK_URL=http://$HOST_IP:$KC_PORT
EOF
  else
    cat <<EOF
    MDQR_CONSUL_HOST=$HOST_IP
    MDQR_CONSUL_PORT=$CONSUL_PORT
    MDQR_REDIS_HOST=$HOST_IP
    MDQR_REDIS_PORT=$REDIS_PORT
    MDQR_VAULT_HOST=$HOST_IP
    MDQR_VAULT_PORT=$VAULT_PORT
    MDQR_VAULT_AUTHENTICATION=TOKEN
    MDQR_VAULT_TOKEN=$VAULT_DEV_TOKEN
    MDQR_KEYCLOAK_URL=http://$HOST_IP:$KC_PORT
EOF
  fi
  echo
}

wait_healthy() {
  local name="$1" timeout="${2:-120}" elapsed=0
  printf "[tools.sh] Waiting for %s to be healthy" "$name"
  while [ $elapsed -lt $timeout ]; do
    local status
    status=$(docker inspect --format='{{.State.Health.Status}}' "$name" 2>/dev/null || echo "missing")
    if [ "$status" = "healthy" ]; then
      echo " ✓"
      return 0
    fi
    printf "."
    sleep 3
    elapsed=$((elapsed + 3))
  done
  echo " TIMEOUT"
  return 1
}

cmd_up() {
  echo "[tools.sh] up — mode=$PROFILES"
  "${DC[@]}" up -d
  if [[ "$PROFILES" == *cluster* ]]; then
    echo "[tools.sh] init redis cluster (idempotente)..."
    bash "$TOOLS_DIR/scripts/redis-cluster-init.sh"
    echo "[tools.sh] init vault cluster (idempotente)..."
    bash "$TOOLS_DIR/scripts/vault-cluster-init.sh"
  fi
  echo
  wait_healthy "${MDQR_KEYCLOAK_NAME:-mdqr-keycloak}" 180
  wait_healthy "${MDQR_CONSUL_NAME:-mdqr-consul}"
  wait_healthy "${MDQR_VAULT_NAME:-mdqr-vault}"
  wait_healthy "${MDQR_REDIS_NAME:-mdqr-redis}"
  echo
  cmd_info
}

cmd_down() {
  "${DC[@]}" down "$@"
}

cmd_status() {
  "${DC[@]}" ps
}

cmd_logs() {
  "${DC[@]}" logs --tail=100 -f "$@"
}

cmd_restart() {
  "${DC[@]}" restart "$@"
}

cmd_init_redis() {
  bash "$TOOLS_DIR/scripts/redis-cluster-init.sh"
}

cmd_check_redis() {
  bash "$TOOLS_DIR/scripts/redis-cluster-check.sh"
}

cmd_init_vault() {
  bash "$TOOLS_DIR/scripts/vault-cluster-init.sh"
}

cmd_help() {
  cat <<EOF
${B}tools.sh${R} — wrapper del stack de tools (Consul + Redis + Vault + Keycloak)

${B}USO${R}
  tools.sh [comando] [args...]
  tools.sh                          ${D}# default: info${R}

${B}COMANDOS${R}
  ${CYAN}-i${R}, ${CYAN}--info${R}        ${D}(info)${R}    Muestra URLs/puertos + snippet .env  ${D}[default]${R}
  ${CYAN}-u${R}, ${CYAN}--up${R}          ${D}(up)${R}      Levanta tools (auto init redis+vault cluster si aplica)
  ${CYAN}-d${R}, ${CYAN}--down${R}        ${D}(down)${R}    Baja tools. Acepta -v para borrar volumes
  ${CYAN}-s${R}, ${CYAN}--status${R}      ${D}(status,ps)${R} docker compose ps
  ${CYAN}-l${R}, ${CYAN}--logs${R}        ${D}(logs)${R}    Tail logs (todos o de un servicio)
  ${CYAN}-r${R}, ${CYAN}--restart${R}     ${D}(restart)${R} Restart todos o un servicio
  ${CYAN}-I${R}, ${CYAN}--init-redis${R}  ${D}(init-redis)${R} Corre redis-cluster-init.sh  ${D}(cluster mode)${R}
  ${CYAN}-C${R}, ${CYAN}--check-redis${R} ${D}(check-redis)${R} Corre redis-cluster-check.sh ${D}(cluster mode)${R}
  ${CYAN}-V${R}, ${CYAN}--init-vault${R}  ${D}(init-vault)${R} Corre vault-cluster-init.sh  ${D}(cluster mode)${R}
  ${CYAN}-h${R}, ${CYAN}--help${R}        ${D}(help)${R}    Muestra esta ayuda

${B}EJEMPLOS${R}
  tools.sh -u                       ${D}# levantar todo${R}
  tools.sh --logs mdqr-keycloak      ${D}# tail logs de keycloak${R}
  tools.sh -d -v                    ${D}# down + borrar volumes${R}
  tools.sh -r mdqr-redis-master-1    ${D}# restart un container${R}
EOF
}

main() {
  local cmd="${1:-info}"; shift || true
  case "$cmd" in
    -u|--up|up)                    cmd_up "$@" ;;
    -d|--down|down)                cmd_down "$@" ;;
    -i|--info|info|"")             cmd_info ;;
    -s|--status|status|ps)         cmd_status ;;
    -l|--logs|logs)                cmd_logs "$@" ;;
    -r|--restart|restart)          cmd_restart "$@" ;;
    -I|--init-redis|init-redis)    cmd_init_redis ;;
    -C|--check-redis|check-redis)  cmd_check_redis ;;
    -V|--init-vault|init-vault)    cmd_init_vault ;;
    -h|--help|help)                cmd_help ;;
    *)
      printf "%scomando desconocido:%s %s\n\n" "$RED" "$R" "$cmd"
      cmd_help
      exit 2
      ;;
  esac
}

main "$@"
