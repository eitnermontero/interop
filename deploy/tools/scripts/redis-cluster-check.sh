#!/usr/bin/env bash
# Valida el estado del cluster Redis (3 masters + 3 replicas).
#
# Checks:
#   1. Los 6 nodos responden PING
#   2. cluster_state:ok y los 16384 slots cubiertos
#   3. cluster_known_nodes:6 y cluster_size:3
#   4. Cada master tiene su replica asignada (no quedaron huerfanos)
#   5. redis-cli --cluster check (consistency cross-nodos)
#   6. Write/read distribuido: SET en master, GET por otro nodo (sigue MOVED)
#
# Lee config del .env del dir padre (deploy/tools/.env).
# Override puntual: MDQR_HOST_IP=otra-ip ./scripts/redis-cluster-check.sh
#
# Exit code: 0 si todo OK, 1 si algun check falla.
set -euo pipefail

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

if [ -n "${COMPOSE_PROFILES:-}" ] && ! echo "${COMPOSE_PROFILES}" | grep -q 'cluster'; then
  echo "[error] El stack actual NO esta en cluster mode."
  echo "        COMPOSE_PROFILES actual: ${COMPOSE_PROFILES}"
  exit 1
fi

MDQR_REDIS_IMAGE="${MDQR_REDIS_IMAGE:-redis:8.0.0}"
MDQR_REDIS_M1_PORT="${MDQR_REDIS_M1_PORT:-6379}"
MDQR_REDIS_M2_PORT="${MDQR_REDIS_M2_PORT:-6380}"
MDQR_REDIS_M3_PORT="${MDQR_REDIS_M3_PORT:-6381}"
MDQR_REDIS_R1_PORT="${MDQR_REDIS_R1_PORT:-6382}"
MDQR_REDIS_R2_PORT="${MDQR_REDIS_R2_PORT:-6383}"
MDQR_REDIS_R3_PORT="${MDQR_REDIS_R3_PORT:-6384}"

NODES=(
  "${MDQR_HOST_IP}:${MDQR_REDIS_M1_PORT}"
  "${MDQR_HOST_IP}:${MDQR_REDIS_M2_PORT}"
  "${MDQR_HOST_IP}:${MDQR_REDIS_M3_PORT}"
  "${MDQR_HOST_IP}:${MDQR_REDIS_R1_PORT}"
  "${MDQR_HOST_IP}:${MDQR_REDIS_R2_PORT}"
  "${MDQR_HOST_IP}:${MDQR_REDIS_R3_PORT}"
)

ENTRY_HOST="${NODES[0]%:*}"
ENTRY_PORT="${NODES[0]##*:}"

run_redis_cli() {
  docker run --rm --network host "${MDQR_REDIS_IMAGE}" redis-cli "$@"
}

FAIL=0
section() { echo ""; echo "[$1] $2"; }
ok()      { echo "  ✓ $*"; }
fail()    { echo "  ✗ $*"; FAIL=1; }

section 1 "Conectividad: los 6 nodos responden PING"
for endpoint in "${NODES[@]}"; do
  host="${endpoint%:*}"
  port="${endpoint##*:}"
  if run_redis_cli -h "${host}" -p "${port}" ping 2>/dev/null | grep -q PONG; then
    ok "${endpoint}"
  else
    fail "${endpoint} no responde"
  fi
done

section 2 "cluster_state y cobertura de slots"
INFO=""
for _ in $(seq 1 15); do
  INFO=$(run_redis_cli -h "${ENTRY_HOST}" -p "${ENTRY_PORT}" cluster info 2>/dev/null || true)
  echo "${INFO}" | grep -q "cluster_state:ok" && break
  sleep 1
done
if echo "${INFO}" | grep -q "cluster_state:ok"; then
  ok "cluster_state:ok"
else
  fail "cluster_state NO es ok"
fi
SLOTS_OK=$(echo "${INFO}" | grep cluster_slots_ok | tr -d '\r' | cut -d: -f2)
SLOTS_FAIL=$(echo "${INFO}" | grep cluster_slots_fail | tr -d '\r' | cut -d: -f2)
if [ "${SLOTS_OK}" = "16384" ] && [ "${SLOTS_FAIL}" = "0" ]; then
  ok "16384/16384 slots OK, 0 fail"
else
  fail "slots_ok=${SLOTS_OK} (esperado 16384), slots_fail=${SLOTS_FAIL} (esperado 0)"
fi

KNOWN=$(echo "${INFO}" | grep cluster_known_nodes | tr -d '\r' | cut -d: -f2)
SIZE=$(echo "${INFO}" | grep cluster_size | tr -d '\r' | cut -d: -f2)
section 3 "Topologia: known_nodes=6, size=3"
[ "${KNOWN}" = "6" ] && ok "known_nodes=6" || fail "known_nodes=${KNOWN} (esperado 6)"
[ "${SIZE}" = "3" ] && ok "size=3 (3 masters con slots)" || fail "size=${SIZE} (esperado 3)"

section 4 "Roles: 3 masters + 3 slaves"
NODES_OUT=$(run_redis_cli -h "${ENTRY_HOST}" -p "${ENTRY_PORT}" cluster nodes 2>/dev/null)
NUM_MASTERS=$(echo "${NODES_OUT}" | awk '$3 ~ /master/ {print}' | wc -l)
NUM_SLAVES=$(echo "${NODES_OUT}" | awk '$3 ~ /slave/ {print}' | wc -l)
[ "${NUM_MASTERS}" = "3" ] && ok "3 masters" || fail "${NUM_MASTERS} masters (esperado 3)"
[ "${NUM_SLAVES}" = "3" ] && ok "3 slaves" || fail "${NUM_SLAVES} slaves (esperado 3)"

ORPHANS=""
while read mid; do
  count=$(echo "${NODES_OUT}" | awk -v m="${mid}" '$4 == m {print}' | wc -l)
  if [ "${count}" -eq 0 ]; then
    ORPHANS="${ORPHANS}    master ${mid} sin replica\n"
  fi
done < <(echo "${NODES_OUT}" | awk '$3 ~ /master/ {print $1}')
if [ -z "${ORPHANS}" ]; then
  ok "todos los masters tienen replica"
else
  fail "masters sin replica:"
  printf "${ORPHANS}"
fi

section 5 "Consistency check (--cluster check)"
CHECK_OUT=$(run_redis_cli --cluster check "${NODES[0]}" 2>&1 || true)
if echo "${CHECK_OUT}" | grep -q "All 16384 slots covered"; then
  ok "cluster check pasa"
else
  fail "cluster check fallo:"
  echo "${CHECK_OUT}" | tail -10 | sed 's/^/    /'
fi

section 6 "Write/read cross-nodo (test funcional)"
TEST_KEY="cluster-check:$(date +%s%N)"
TEST_VAL="ok-$$"
if run_redis_cli -c -h "${ENTRY_HOST}" -p "${ENTRY_PORT}" SET "${TEST_KEY}" "${TEST_VAL}" 2>/dev/null | grep -q OK; then
  ok "SET ${TEST_KEY} OK desde ${NODES[0]}"
else
  fail "SET fallo"
fi
GOT=$(run_redis_cli -c -h "${MDQR_HOST_IP}" -p "${MDQR_REDIS_R3_PORT}" GET "${TEST_KEY}" 2>/dev/null || echo "")
if [ "${GOT}" = "${TEST_VAL}" ]; then
  ok "GET ${TEST_KEY} = ${TEST_VAL} desde nodo distinto (${MDQR_HOST_IP}:${MDQR_REDIS_R3_PORT})"
else
  fail "GET retorno '${GOT}' (esperado '${TEST_VAL}')"
fi
run_redis_cli -c -h "${ENTRY_HOST}" -p "${ENTRY_PORT}" DEL "${TEST_KEY}" >/dev/null 2>&1 || true

echo ""
if [ "${FAIL}" = "0" ]; then
  echo "===================================="
  echo " ✓ CLUSTER OK"
  echo "===================================="
  exit 0
else
  echo "===================================="
  echo " ✗ CLUSTER CON PROBLEMAS"
  echo "===================================="
  exit 1
fi
