#!/usr/bin/env bash
# Inicializa el cluster Redis (3 masters + 3 replicas).
#
# En single-host, el algoritmo de anti-affinity de "redis-cli --cluster create
# ... --cluster-replicas 1" NO funciona bien (todos los nodos comparten IP),
# asi que este script:
#   1. Crea el cluster solo con los 3 MASTERS
#   2. Agrega cada REPLICA explicitamente como slave del master correspondiente
#
# Lee config del .env del dir padre (deploy/tools/.env).
# Override puntual: MDQR_HOST_IP=otra-ip ./scripts/redis-cluster-init.sh
#
# Ejecutar UNA vez. Idempotente: si el cluster ya esta formado, retorna OK.
set -euo pipefail

# Auto-load .env del dir padre (deploy/tools/.env)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$(dirname "${SCRIPT_DIR}")/.env"
if [ -f "${ENV_FILE}" ]; then
  set -a
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
  set +a
fi

MDQR_HOST_IP="${MDQR_HOST_IP:-${1:-}}"
if [ -z "${MDQR_HOST_IP}" ]; then
  echo "[error] MDQR_HOST_IP no esta definido."
  echo "        Setealo en ${ENV_FILE} o pasalo como var: MDQR_HOST_IP=192.168.0.12 $0"
  exit 1
fi

# Sanity check: el stack actual debe estar en cluster mode.
if [ -n "${COMPOSE_PROFILES:-}" ] && ! echo "${COMPOSE_PROFILES}" | grep -q 'cluster'; then
  echo "[error] El stack actual NO esta en cluster mode — no hay nada que inicializar."
  echo "        COMPOSE_PROFILES actual: ${COMPOSE_PROFILES}"
  echo ""
  echo "        Para usar cluster, edita ${ENV_FILE}:"
  echo "          COMPOSE_PROFILES=cluster"
  echo "        Luego: docker compose down -v && docker compose up -d && ./scripts/redis-cluster-init.sh"
  exit 1
fi

MDQR_REDIS_IMAGE="${MDQR_REDIS_IMAGE:-redis:8.0.0}"
MDQR_REDIS_M1_PORT="${MDQR_REDIS_M1_PORT:-6379}"
MDQR_REDIS_M2_PORT="${MDQR_REDIS_M2_PORT:-6380}"
MDQR_REDIS_M3_PORT="${MDQR_REDIS_M3_PORT:-6381}"
MDQR_REDIS_R1_PORT="${MDQR_REDIS_R1_PORT:-6382}"
MDQR_REDIS_R2_PORT="${MDQR_REDIS_R2_PORT:-6383}"
MDQR_REDIS_R3_PORT="${MDQR_REDIS_R3_PORT:-6384}"

MASTERS=(
  "${MDQR_HOST_IP}:${MDQR_REDIS_M1_PORT}"
  "${MDQR_HOST_IP}:${MDQR_REDIS_M2_PORT}"
  "${MDQR_HOST_IP}:${MDQR_REDIS_M3_PORT}"
)
REPLICAS=(
  "${MDQR_HOST_IP}:${MDQR_REDIS_R1_PORT}"
  "${MDQR_HOST_IP}:${MDQR_REDIS_R2_PORT}"
  "${MDQR_HOST_IP}:${MDQR_REDIS_R3_PORT}"
)
ALL_NODES=("${MASTERS[@]}" "${REPLICAS[@]}")

run_redis_cli() {
  docker run --rm --network host "${MDQR_REDIS_IMAGE}" redis-cli "$@"
}

echo "[info] esperando que los 6 nodos respondan a PING..."
for endpoint in "${ALL_NODES[@]}"; do
  host="${endpoint%:*}"
  port="${endpoint##*:}"
  for _ in $(seq 1 60); do
    if run_redis_cli -h "${host}" -p "${port}" ping >/dev/null 2>&1; then
      echo "  [ok] ${endpoint}"
      break
    fi
    sleep 2
  done
done

first_host="${MASTERS[0]%:*}"
first_port="${MASTERS[0]##*:}"

# Idempotencia
if state=$(run_redis_cli -h "${first_host}" -p "${first_port}" cluster info 2>/dev/null) && \
   echo "${state}" | grep -q "cluster_state:ok" && \
   echo "${state}" | grep -q "cluster_known_nodes:6"; then
  echo "[info] cluster ya inicializado y healthy — nada que hacer"
  exit 0
fi

echo ""
echo "[info] paso 1/2: creando cluster solo con los 3 masters..."
run_redis_cli --cluster create "${MASTERS[@]}" --cluster-yes

sleep 3

get_master_id() {
  local target="$1"
  run_redis_cli -h "${first_host}" -p "${first_port}" cluster nodes 2>/dev/null \
    | awk -v t="${target}" '$2 ~ t && $3 ~ /master/ {print $1}' \
    | head -1
}

M1_ID=$(get_master_id "${MASTERS[0]}@")
M2_ID=$(get_master_id "${MASTERS[1]}@")
M3_ID=$(get_master_id "${MASTERS[2]}@")

if [ -z "${M1_ID}" ] || [ -z "${M2_ID}" ] || [ -z "${M3_ID}" ]; then
  echo "[error] no pude resolver IDs de los masters"
  echo "M1_ID=${M1_ID}  M2_ID=${M2_ID}  M3_ID=${M3_ID}"
  exit 1
fi

echo ""
echo "[info] paso 2/2: agregando 3 replicas (1 por master)..."
echo "  R1 (${REPLICAS[0]}) -> M1 (${MASTERS[0]} id=${M1_ID:0:8}...)"
run_redis_cli --cluster add-node "${REPLICAS[0]}" "${MASTERS[0]}" --cluster-slave --cluster-master-id "${M1_ID}"
echo "  R2 (${REPLICAS[1]}) -> M2 (${MASTERS[1]} id=${M2_ID:0:8}...)"
run_redis_cli --cluster add-node "${REPLICAS[1]}" "${MASTERS[1]}" --cluster-slave --cluster-master-id "${M2_ID}"
echo "  R3 (${REPLICAS[2]}) -> M3 (${MASTERS[2]} id=${M3_ID:0:8}...)"
run_redis_cli --cluster add-node "${REPLICAS[2]}" "${MASTERS[2]}" --cluster-slave --cluster-master-id "${M3_ID}"

echo ""
echo "[info] esperando cluster_state:ok..."
for _ in $(seq 1 30); do
  if run_redis_cli -h "${first_host}" -p "${first_port}" cluster info 2>/dev/null | grep -q "cluster_state:ok"; then
    echo "[ok] cluster_state:ok"
    break
  fi
  sleep 1
done

echo ""
echo "[info] estado final del cluster:"
run_redis_cli -h "${first_host}" -p "${first_port}" cluster info | grep -E 'cluster_state|cluster_slots|cluster_known_nodes|cluster_size'
echo ""
run_redis_cli -h "${first_host}" -p "${first_port}" cluster nodes | awk '{print $2, $3}'
