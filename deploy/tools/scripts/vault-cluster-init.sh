#!/usr/bin/env bash
# Inicializa el cluster Vault (3 nodos raft HA).
#
# Acciones:
#   1. Espera que los 3 nodos respondan vault status
#   2. vault operator init en el nodo 1 (genera unseal keys + root token)
#   3. Unseal del nodo 1 con las 3 primeras keys
#   4. Espera que los nodos 2 y 3 hagan retry_join al raft
#   5. Unseal de los nodos 2 y 3
#   6. Imprime estado raft (vault operator raft list-peers)
#
# Las unseal keys + root token se guardan en deploy/tools/.vault-init.json
# (gitignored). PROD: guardalas en sitio seguro y borralas del disco.
#
# Lee config del .env del dir padre (deploy/tools/.env).
# Ejecutar UNA vez. Idempotente: si ya esta inicializado, solo unseal de nodos sealed.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLS_DIR="$(dirname "${SCRIPT_DIR}")"
ENV_FILE="${TOOLS_DIR}/.env"
INIT_FILE="${TOOLS_DIR}/.vault-init.json"

if [ -f "${ENV_FILE}" ]; then
  set -a
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
  set +a
fi

if [ -n "${COMPOSE_PROFILES:-}" ] && ! echo "${COMPOSE_PROFILES}" | grep -q 'cluster'; then
  echo "[error] El stack actual NO esta en cluster mode."
  echo "        COMPOSE_PROFILES actual: ${COMPOSE_PROFILES}"
  exit 1
fi

MDQR_HOST_IP="${MDQR_HOST_IP:-${1:-}}"
if [ -z "${MDQR_HOST_IP}" ]; then
  echo "[error] MDQR_HOST_IP no esta definido."
  exit 1
fi

MDQR_VAULT_1_NAME="${MDQR_VAULT_1_NAME:-mdqr-vault-1}"
MDQR_VAULT_2_NAME="${MDQR_VAULT_2_NAME:-mdqr-vault-2}"
MDQR_VAULT_3_NAME="${MDQR_VAULT_3_NAME:-mdqr-vault-3}"
MDQR_VAULT_1_PORT="${MDQR_VAULT_1_PORT:-8200}"
MDQR_VAULT_2_PORT="${MDQR_VAULT_2_PORT:-8210}"
MDQR_VAULT_3_PORT="${MDQR_VAULT_3_PORT:-8220}"

NODES=("${MDQR_VAULT_1_NAME}" "${MDQR_VAULT_2_NAME}" "${MDQR_VAULT_3_NAME}")

vault_exec() {
  local node="$1"; shift
  docker exec -e VAULT_ADDR=http://127.0.0.1:8200 "${node}" vault "$@"
}

# Vault status exit codes: 0=unsealed/active, 1=error, 2=sealed.
# Aca consideramos vivo si exit es 0 o 2.
wait_vault_alive() {
  local node="$1"
  for _ in $(seq 1 60); do
    set +e
    docker exec -e VAULT_ADDR=http://127.0.0.1:8200 "${node}" vault status >/dev/null 2>&1
    local rc=$?
    set -e
    if [ "${rc}" = "0" ] || [ "${rc}" = "2" ]; then
      return 0
    fi
    sleep 2
  done
  return 1
}

echo "[info] esperando que los 3 nodos respondan vault status..."
for n in "${NODES[@]}"; do
  if wait_vault_alive "${n}"; then
    echo "  [ok] ${n}"
  else
    echo "  [error] ${n} no responde"
    exit 1
  fi
done

# Check init status del primer nodo
INITIALIZED=$(vault_exec "${MDQR_VAULT_1_NAME}" status -format=json 2>/dev/null | grep -o '"initialized": *true' || true)

if [ -z "${INITIALIZED}" ]; then
  echo ""
  echo "[info] inicializando cluster (vault operator init en ${MDQR_VAULT_1_NAME})..."
  if [ -f "${INIT_FILE}" ]; then
    echo "[error] ${INIT_FILE} ya existe pero el cluster reporta no inicializado."
    echo "        Resolver manualmente: borrar volumen o eliminar ${INIT_FILE}."
    exit 1
  fi
  vault_exec "${MDQR_VAULT_1_NAME}" operator init -key-shares=5 -key-threshold=3 -format=json > "${INIT_FILE}"
  chmod 600 "${INIT_FILE}"
  echo "[ok] keys guardadas en ${INIT_FILE} (gitignored)"
else
  echo "[info] cluster ya inicializado, se omite operator init"
  if [ ! -f "${INIT_FILE}" ]; then
    echo "[error] cluster inicializado pero no encuentro ${INIT_FILE} con las unseal keys."
    echo "        No puedo continuar sin las keys."
    exit 1
  fi
fi

# Extract keys + root token (sin jq — uso python3 si esta, sino fallback grep)
extract_keys() {
  if command -v jq >/dev/null 2>&1; then
    jq -r '.unseal_keys_b64[]' "${INIT_FILE}"
  else
    python3 -c "import json; print('\n'.join(json.load(open('${INIT_FILE}'))['unseal_keys_b64']))"
  fi
}
extract_root_token() {
  if command -v jq >/dev/null 2>&1; then
    jq -r '.root_token' "${INIT_FILE}"
  else
    python3 -c "import json; print(json.load(open('${INIT_FILE}'))['root_token'])"
  fi
}

mapfile -t UNSEAL_KEYS < <(extract_keys)
ROOT_TOKEN=$(extract_root_token)

if [ "${#UNSEAL_KEYS[@]}" -lt 3 ]; then
  echo "[error] menos de 3 unseal keys en ${INIT_FILE}"
  exit 1
fi

unseal_node() {
  local node="$1"
  local sealed
  # vault status exit 2 = sealed; toleramos exit con || true para no romper set -e.
  set +e
  sealed=$(docker exec -e VAULT_ADDR=http://127.0.0.1:8200 "${node}" vault status -format=json 2>/dev/null \
    | grep -o '"sealed": *[a-z]*' | awk '{print $2}')
  set -e
  if [ "${sealed}" = "false" ]; then
    echo "  [skip] ${node} ya esta unsealed"
    return 0
  fi
  echo "  [info] unsealing ${node}..."
  for i in 0 1 2; do
    vault_exec "${node}" operator unseal "${UNSEAL_KEYS[$i]}" >/dev/null
  done
  echo "  [ok] ${node} unsealed"
}

echo ""
echo "[info] unseal nodo 1 (leader inicial del raft)..."
unseal_node "${MDQR_VAULT_1_NAME}"

# Dar tiempo a que nodos 2 y 3 hagan retry_join al raft del leader
echo ""
echo "[info] esperando 5s para que nodos 2 y 3 hagan retry_join al raft..."
sleep 5

echo ""
echo "[info] unseal nodos 2 y 3..."
unseal_node "${MDQR_VAULT_2_NAME}"
unseal_node "${MDQR_VAULT_3_NAME}"

echo ""
echo "[info] esperando que el raft cluster forme quorum..."
sleep 3

echo ""
echo "[info] raft peers (segun ${MDQR_VAULT_1_NAME}):"
docker exec -e VAULT_ADDR=http://127.0.0.1:8200 -e VAULT_TOKEN="${ROOT_TOKEN}" \
  "${MDQR_VAULT_1_NAME}" vault operator raft list-peers || true

echo ""
echo "===================================="
echo " ✓ VAULT CLUSTER INICIALIZADO"
echo "===================================="
echo " Root token: ${ROOT_TOKEN}"
echo " Unseal keys + token guardados en:"
echo "   ${INIT_FILE}"
echo " Endpoints:"
echo "   http://${MDQR_HOST_IP}:${MDQR_VAULT_1_PORT}"
echo "   http://${MDQR_HOST_IP}:${MDQR_VAULT_2_PORT}"
echo "   http://${MDQR_HOST_IP}:${MDQR_VAULT_3_PORT}"
echo "===================================="
