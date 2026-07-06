#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# bootstrap.sh — Levanta el HUB COMPLETO DESDE CERO con un solo comando.
#
# Pensado para implementaciones nuevas y demos: en una máquina con Docker y
# las imágenes cargadas, crea todo el entorno end-to-end:
#
#   1. Red compartida                    5. PKI de Vault (CAs + certs)
#   2. Tools (Keycloak/Consul/Vault/     6. Directorios de logs
#      Redis) + espera de salud          7. Config de APIs en Consul KV
#   3. Realms Keycloak + partners        8. Stack del hub (postgres/gateway/
#   4. Seeds de Vault                       base-service) + espera de salud
#                                        9. Smoke test E2E (token + POST + auditoría)
#
# Prerequisitos: docker + compose plugin, y las imágenes del hub cargadas
#   (./gradlew jibDockerBuild en esta máquina, o docker load del tar — ver
#    deploy/scripts/export-images.sh y docs/DEPLOY-STAGING-SERVER.md).
#
# Uso:
#   deploy/scripts/bootstrap.sh                 # todo de cero (idempotente)
#   PARTNER=felcn-api deploy/scripts/bootstrap.sh
#   SERVER_CN=mi-servidor SERVER_IP=10.0.0.5 deploy/scripts/bootstrap.sh
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/../.." && pwd)"
PARTNER="${PARTNER:-felcn-api}"
SERVER_CN="${SERVER_CN:-$(hostname)}"
SERVER_IP="${SERVER_IP:-$(hostname -I 2>/dev/null | awk '{print $1}')}"
SERVER_IP="${SERVER_IP:-127.0.0.1}"
GATEWAY_URL="${GATEWAY_URL:-http://127.0.0.1:8088}"
LOG_BASE="${LOG_BASE:-$HOME/logs/hub-staging}"

GREEN='\033[0;32m'; RED='\033[0;31m'; CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'
paso()  { echo -e "\n${BOLD}${CYAN}═══ [$1/9] $2 ═══${NC}"; }
ok()    { echo -e "${GREEN}[✓]${NC} $1"; }
fallo() { echo -e "${RED}[✗]${NC} $1"; exit 1; }

esperar() { # esperar <descripcion> <intentos> <comando...>
  local desc="$1" n="$2"; shift 2
  for i in $(seq 1 "$n"); do
    if "$@" >/dev/null 2>&1; then ok "$desc (intento $i)"; return 0; fi
    sleep 5
  done
  fallo "$desc no respondió tras $n intentos"
}

# ── 0. Prerequisitos ─────────────────────────────────────────────────────────
command -v docker >/dev/null || fallo "docker no está instalado"
if ! docker image inspect cr.sintesis.com.bo/hub-dev/hub-gateway:latest >/dev/null 2>&1 \
   || ! docker image inspect cr.sintesis.com.bo/hub-dev/hub-ms-base:latest >/dev/null 2>&1; then
  # Autocarga: buscar el tar de imágenes junto al paquete extraído
  TAR=$(ls -t "$REPO"/../hub-images-*.tar.gz "$REPO"/hub-images-*.tar.gz "$REPO"/deploy/dist/hub-images-*.tar.gz 2>/dev/null | head -1 || true)
  if [ -n "$TAR" ]; then
    echo -e "${CYAN}[i]${NC} Cargando imágenes desde $TAR (puede tardar unos minutos)..."
    docker load -i "$TAR" >/dev/null
  fi
fi
docker image inspect cr.sintesis.com.bo/hub-dev/hub-gateway:latest >/dev/null 2>&1 \
  || fallo "Falta la imagen hub-gateway y no se encontró hub-images-*.tar.gz junto al paquete."
docker image inspect cr.sintesis.com.bo/hub-dev/hub-ms-base:latest >/dev/null 2>&1 \
  || fallo "Falta la imagen hub-ms-base."
[ -f "$REPO/deploy/tools/.env" ] || { cp "$REPO/deploy/tools/.env.example" "$REPO/deploy/tools/.env"; ok "deploy/tools/.env creado desde .env.example"; }

paso 1 "Red compartida"
docker network inspect hub-shared >/dev/null 2>&1 || docker network create hub-shared >/dev/null
ok "red hub-shared"

paso 2 "Tools: Keycloak + Consul + Vault + Redis"
"$REPO/deploy/scripts/tools.sh" --up >/dev/null 2>&1 || "$REPO/deploy/scripts/tools.sh" --up
esperar "Keycloak"  40 curl -sf http://127.0.0.1:8180/realms/master
esperar "Consul"    12 curl -sf http://127.0.0.1:8500/v1/status/leader
esperar "Vault"     12 curl -sf http://127.0.0.1:8200/v1/sys/health
esperar "Redis"     12 docker exec hub-redis redis-cli ping

paso 3 "Realms Keycloak (hub-admin + hub-partner con partners del CSV)"
echo yes | "$REPO/deploy/scripts/keycloak-sync-admin.sh"   >/dev/null || fallo "keycloak-sync-admin"
echo yes | "$REPO/deploy/scripts/keycloak-sync-partner.sh" >/dev/null || fallo "keycloak-sync-partner"
ok "realms sincronizados"

paso 4 "Seeds de Vault (secretos de DB/Redis/Keycloak)"
TOOLS_HOST=127.0.0.1 DB_NAME=hub_auth "$REPO/deploy/scripts/vault-seed.sh" --ns hub-auth --kc-realm hub-admin >/dev/null
TOOLS_HOST=127.0.0.1 DB_NAME=hub_base "$REPO/deploy/scripts/vault-seed.sh" --ns hub-base --kc-realm hub-admin >/dev/null
ok "namespaces hub-auth y hub-base seedeados"

paso 5 "PKI de Vault (CA raíz + intermedia + cert del gateway + partner)"
"$REPO/deploy/scripts/vault-pki.sh" init >/dev/null
"$REPO/deploy/scripts/vault-pki.sh" server "$SERVER_CN" "$SERVER_IP" >/dev/null
"$REPO/deploy/scripts/vault-pki.sh" partner "$PARTNER" >/dev/null
ok "PKI lista; credencial de '$PARTNER' en deploy/certs/partners/"

paso 6 "Directorios de logs (uid 1000 del contenedor)"
mkdir -p "$LOG_BASE/gateway" "$LOG_BASE/base-service"
docker run --rm -v "$LOG_BASE":/fix alpine chown -R 1000:1000 /fix >/dev/null 2>&1 || true
ok "$LOG_BASE"

paso 7 "Plano de control: APIs publicadas en Consul KV"
curl -sf -X PUT --data-binary @"$REPO/deploy/staging/consul-config/base-service-application.yml" \
  http://127.0.0.1:8500/v1/kv/config/base-service/hub-ms-base.yml >/dev/null
ok "config/base-service/hub-ms-base.yml"

paso 8 "Stack del hub (postgres + gateway + base-service)"
docker compose -f "$REPO/deploy/staging/docker-compose.yml" --env-file "$REPO/deploy/staging/.env" up -d 2>/dev/null
esperar "postgres"     12 bash -c 'docker inspect -f "{{.State.Health.Status}}" hub-staging-postgres | grep -q healthy'
esperar "base-service" 36 bash -c 'docker inspect -f "{{.State.Health.Status}}" hub-staging-base-service | grep -q healthy'
esperar "gateway"      24 bash -c 'docker inspect -f "{{.State.Health.Status}}" hub-staging-gateway | grep -q healthy'

paso 9 "Smoke test E2E"
SECRET=$(grep "^$PARTNER," "$REPO/deploy/scripts/keycloak-seed/partner/clients.csv" | cut -d, -f4)
[ -n "$SECRET" ] || fallo "no se encontró el secret de $PARTNER en clients.csv"
TOKEN=$(curl -s -X POST "$GATEWAY_URL/oauth2/token" -d grant_type=client_credentials \
  -d "client_id=$PARTNER" -d "client_secret=$SECRET" \
  -d 'scope=https://api.sintesis.com.bo/caso.penal' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin).get("access_token",""))')
[ -n "$TOKEN" ] || fallo "no se obtuvo token del partner"
ok "token de '$PARTNER' emitido vía el gateway"
HTTP=$(curl -s -o /tmp/bootstrap-smoke.json -w '%{http_code}' -m 25 -X POST \
  "$GATEWAY_URL/partner/v1/inbound/CASO_PENAL/v1" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-Idempotency-Key: bootstrap-$(date +%s%N)" \
  -d '{"cud":"BOOT-001","id_externo_caso":1,"id_tipo_denuncia":3,"id_oficina":12,"id_estado":1,"id_etapa":1}')
[ "$HTTP" = "201" ] || fallo "POST caso devolvió $HTTP: $(cat /tmp/bootstrap-smoke.json)"
ok "POST /partner/v1/inbound/CASO_PENAL/v1 → 201"
APIS=$(curl -s "$GATEWAY_URL/v3/api-docs/base-service" | python3 -c 'import sys,json; print(len([p for p in json.load(sys.stdin)["paths"] if "inbound" in p]))')
ok "Swagger unificado publica $APIS endpoint(s) inbound"

echo ""
echo -e "${BOLD}${GREEN}════════════ HUB OPERATIVO ════════════${NC}"
echo "  Gateway (partners):  $GATEWAY_URL"
echo "  Token endpoint:      POST $GATEWAY_URL/oauth2/token"
echo "  Swagger unificado:   $GATEWAY_URL/v3/api-docs/base-service"
echo "  Consul UI:           http://127.0.0.1:8500"
echo "  Auditoría:           psql -h 127.0.0.1 -p 5433 -d hub_base (postgres/postgres)"
echo "  Partner demo:        $PARTNER (cert: deploy/certs/partners/$PARTNER.p12)"
echo "  Configurar APIs:     deploy/scripts/hub-api.sh {validate|diff|publish|list}"
