#!/usr/bin/env bash
# =============================================================================
# keycloak-sync-partner.sh — Realm hub-partner (clientes externos / M2M)
#
# Idempotente: crea recursos faltantes, omite los existentes.
# Solo usa client_credentials — sin usuarios, sin SPA.
#
# Uso:
#   ./keycloak-sync-partner.sh [--yes-to-all]
#   ./keycloak-sync-partner.sh --add-client <clientId> <description> <secret>
#
# Variables de entorno:
#   KC_URL       URL de Keycloak  (default: http://localhost:8180)
#   KC_REALM     Nombre del realm (default: hub-partner)
#   KC_USERNAME  Admin de master  (default: admin)
#   KC_PASSWORD  Password admin   (default: admin)
# =============================================================================
set -euo pipefail

KC_URL="${KC_URL:-http://localhost:8180}"
KC_REALM="${KC_REALM:-hub-partner}"
KC_USERNAME="${KC_USERNAME:-admin}"
KC_PASSWORD="${KC_PASSWORD:-admin}"
SEED_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/keycloak-seed/partner" && pwd)"
TOKEN=""
CONFIRM_ALL=false
ADD_CLIENT_MODE=false
ADD_CLIENT_ID=""
ADD_CLIENT_DESC=""
ADD_CLIENT_SECRET=""

while [ $# -gt 0 ]; do
  case "$1" in
    --yes-to-all) CONFIRM_ALL=true; shift ;;
    --add-client)
      ADD_CLIENT_MODE=true
      ADD_CLIENT_ID="$2"; ADD_CLIENT_DESC="$3"; ADD_CLIENT_SECRET="$4"
      shift 4 ;;
    -h|--help)
      echo "Usage: $(basename "$0") [--yes-to-all]"
      echo "       $(basename "$0") --add-client <clientId> <description> <secret>"
      echo ""
      echo "Syncs the hub-partner Keycloak realm for external API partners (M2M)."
      echo ""
      echo "To add a new partner client:"
      echo "  1. Add a line to keycloak-seed/partner/clients.csv"
      echo "  2. Re-run this script, or use --add-client for one-off adds"
      exit 0 ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

# ─── Colors ────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

info()   { echo -e "${GREEN}[+]${NC} $1"; }
warn()   { echo -e "${YELLOW}[!]${NC} $1"; }
error()  { echo -e "${RED}[✗]${NC} $1"; }
header() { echo -e "\n${BOLD}${CYAN}═══ $1 ═══${NC}"; }
item()   { echo -e "    ${CYAN}→${NC} $1"; }

# ─── Keycloak helpers ───────────────────────────────────────────────────
get_token() {
  local response token err
  response=$(curl -s \
    "${KC_URL}/realms/master/protocol/openid-connect/token" \
    -d "client_id=admin-cli" \
    -d "username=${KC_USERNAME}" \
    -d "password=${KC_PASSWORD}" \
    -d "grant_type=password") || { error "Sin conexión a ${KC_URL}"; exit 1; }
  token=$(printf '%s' "$response" | jq -r '.access_token // empty' 2>/dev/null) || true
  if [ -z "$token" ]; then
    err=$(printf '%s' "$response" | jq -r '.error_description // .error // "sin access_token"' 2>/dev/null) || true
    error "Auth failed [${KC_URL}]: ${err:-sin respuesta válida de Keycloak}"
    exit 1
  fi
  TOKEN="$token"
}

kc_get() { curl -sf -H "Authorization: Bearer ${TOKEN}" "${KC_URL}/admin/realms/${KC_REALM}$1"; }

kc_post_get_id() {
  local loc
  loc=$(curl -s -D - -o /dev/null \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -d "$2" "${KC_URL}/admin/realms/${KC_REALM}$1" \
    | grep -i "^location:" | tr -d '\r' | awk '{print $2}')
  echo "${loc##*/}"
}

# ─── Realm ──────────────────────────────────────────────────────────────
create_realm() {
  header "Realm: ${KC_REALM}"
  local status
  status=$(curl -s -o /dev/null -w "%{http_code}" \
    -H "Authorization: Bearer ${TOKEN}" "${KC_URL}/admin/realms/${KC_REALM}")

  if [ "$status" = "200" ]; then
    warn "Realm '${KC_REALM}' already exists — skip"; return
  fi

  info "Creating realm: ${KC_REALM}"
  curl -sf -o /dev/null \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -d "$(jq -n --arg r "$KC_REALM" '{
      realm: $r,
      enabled: true,
      registrationAllowed: false,
      loginWithEmailAllowed: false,
      bruteForceProtected: true,
      accessTokenLifespan: 300,
      ssoSessionMaxLifespan: 0
    }')" "${KC_URL}/admin/realms"
  info "Realm '${KC_REALM}' created (token TTL: 5 min)"
}

# ─── OIDC Client Scopes (API scopes) ────────────────────────────────────
sync_client_scopes() {
  header "API Scopes (OIDC Client Scopes)"

  while IFS=, read -r name description; do
    [ "$name" = "name" ] && continue

    local scope_id
    scope_id=$(curl -sf -H "Authorization: Bearer ${TOKEN}" \
      "${KC_URL}/admin/realms/${KC_REALM}/client-scopes" \
      | jq -r ".[] | select(.name==\"${name}\") | .id" 2>/dev/null || echo "")

    if [ -n "$scope_id" ]; then
      warn "Scope '${name}' already exists — skip"
    else
      local code
      code=$(curl -s -o /dev/null -w "%{http_code}" \
        -H "Authorization: Bearer ${TOKEN}" -H "Content-Type: application/json" \
        -d "$(jq -n --arg n "$name" --arg d "$description" '{
          name:$n, description:$d,
          protocol:"openid-connect",
          attributes:{
            "include.in.token.scope":"true",
            "display.on.consent.screen":"false"
          }
        }')" "${KC_URL}/admin/realms/${KC_REALM}/client-scopes")
      [ "$code" = "201" ] && info "Scope '${name}' created" \
        || error "Failed scope '${name}' (HTTP ${code})"
    fi
  done < "${SEED_DIR}/client-scopes.csv"
}

# ─── Partner clients ─────────────────────────────────────────────────────
create_partner_client() {
  local clientId="$1" description="$2" scopes_csv="$3" secret="$4"

  local existing_id
  existing_id=$(kc_get "/clients?clientId=${clientId}" | jq -r '.[0].id // empty')
  if [ -n "$existing_id" ]; then
    warn "Client '${clientId}' already exists — skip"; return
  fi

  local new_id
  new_id=$(kc_post_get_id "/clients" "$(jq -n \
    --arg cid "$clientId" --arg desc "$description" --arg s "$secret" '{
      clientId:$cid, name:$desc, secret:$s, enabled:true,
      protocol:"openid-connect", publicClient:false,
      serviceAccountsEnabled:true,
      authorizationServicesEnabled:false,
      directAccessGrantsEnabled:false,
      standardFlowEnabled:false,
      implicitFlowEnabled:false,
      attributes:{
        "access.token.lifespan": "300",
        "use.refresh.tokens": "false"
      }
    }')")
  [ -z "$new_id" ] && { error "Failed to create client '${clientId}'"; return; }
  info "Client '${clientId}' created (${new_id})"

  # Force secret
  curl -sf -o /dev/null -X PUT \
    -H "Authorization: Bearer ${TOKEN}" -H "Content-Type: application/json" \
    -d "$(jq -n --arg id "$new_id" --arg cid "$clientId" --arg s "$secret" \
      '{id:$id, clientId:$cid, secret:$s}')" \
    "${KC_URL}/admin/realms/${KC_REALM}/clients/${new_id}" || true

  # Assign optional scopes (the partner's allowed API scopes)
  IFS=';' read -ra scope_arr <<< "$scopes_csv"
  for scope_name in "${scope_arr[@]}"; do
    [ -z "$scope_name" ] && continue
    local scope_id
    scope_id=$(curl -sf -H "Authorization: Bearer ${TOKEN}" \
      "${KC_URL}/admin/realms/${KC_REALM}/client-scopes" \
      | jq -r ".[] | select(.name==\"${scope_name}\") | .id" 2>/dev/null || echo "")

    if [ -n "$scope_id" ]; then
      local code
      code=$(curl -s -o /dev/null -w "%{http_code}" -X PUT \
        -H "Authorization: Bearer ${TOKEN}" \
        "${KC_URL}/admin/realms/${KC_REALM}/clients/${new_id}/optional-client-scopes/${scope_id}")
      [ "$code" = "204" ] && item "Assigned optional scope: ${scope_name}" \
        || warn "Could not assign scope '${scope_name}' (HTTP ${code})"
    else
      warn "Scope '${scope_name}' not found — run sync first"
    fi
  done

  echo ""
  echo -e "    ${BOLD}client_id:${NC}     ${clientId}"
  echo -e "    ${BOLD}client_secret:${NC} ${secret}"
  echo -e "    ${BOLD}grant_type:${NC}    client_credentials"
  echo -e "    ${BOLD}scope:${NC}         ${scopes_csv}"
}

sync_partner_clients() {
  header "Partner Clients (client_credentials)"

  while IFS=, read -r clientId description scopes secret; do
    [ "$clientId" = "clientId" ] && continue
    create_partner_client "$clientId" "$description" "$scopes" "$secret"
  done < "${SEED_DIR}/clients.csv"
}

# ─── Main ────────────────────────────────────────────────────────────────
main() {
  echo -e "${BOLD}"
  echo "╔═══════════════════════════════════════════════════╗"
  echo "║  Keycloak Sync — Realm: hub-partner              ║"
  echo "╚═══════════════════════════════════════════════════╝"
  echo -e "${NC}"
  echo -e "  Server: ${CYAN}${KC_URL}${NC}"
  echo -e "  Realm:  ${CYAN}${KC_REALM}${NC}"
  echo -e "  Seed:   ${CYAN}${SEED_DIR}${NC}"

  for cmd in curl jq; do
    command -v "$cmd" &>/dev/null || { error "'${cmd}' not installed"; exit 1; }
  done

  info "Authenticating..."
  get_token
  info "Authenticated"

  # --add-client mode: one-off client creation
  if [ "$ADD_CLIENT_MODE" = true ]; then
    header "Adding client: ${ADD_CLIENT_ID}"
    create_realm
    get_token
    sync_client_scopes
    create_partner_client "$ADD_CLIENT_ID" "$ADD_CLIENT_DESC" \
      "https://api.sintesis.com.bo/caso.penal" "$ADD_CLIENT_SECRET"
    echo ""
    echo -e "${BOLD}${GREEN}═══ Client added ═══${NC}"
    return
  fi

  echo ""
  if [ "$CONFIRM_ALL" = false ]; then
    echo -ne "${BOLD}${YELLOW}Apply changes to realm '${KC_REALM}'?${NC} [yes/N] "
    read -r confirm
    [ "$confirm" != "yes" ] && { warn "Aborted"; exit 0; }
  fi

  create_realm
  get_token
  sync_client_scopes
  sync_partner_clients

  echo ""
  echo -e "${BOLD}${GREEN}═══ hub-partner sync complete ═══${NC}"
  echo ""
  echo -e "  ${BOLD}Token endpoint (via gateway):${NC}"
  echo -e "  ${CYAN}POST http://localhost:8080/oauth2/token${NC}"
  echo -e "  grant_type=client_credentials"
  echo -e "  scope=https://api.sintesis.com.bo/caso.penal"
  echo ""
  echo -e "  ${BOLD}Inbound — Caso Penal (via gateway):${NC}"
  echo -e "  ${CYAN}POST http://localhost:8080/partner/v1/inbound/CASO_PENAL/v1${NC}"
}

main "$@"
