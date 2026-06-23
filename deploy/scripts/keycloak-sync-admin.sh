#!/usr/bin/env bash
# =============================================================================
# keycloak-sync-admin.sh — Realm mdqr-admin (usuarios internos / SPA admin)
#
# Idempotente: crea recursos faltantes, omite los existentes.
#
# Uso:
#   ./keycloak-sync-admin.sh [--yes-to-all]
#
# Variables de entorno:
#   KC_URL          URL de Keycloak      (default: http://localhost:8180)
#   KC_REALM        Nombre del realm     (default: mdqr-admin)
#   KC_USERNAME     Admin de master      (default: admin)
#   KC_PASSWORD     Password admin       (default: admin)
#   ADMIN_PASSWORD  Password del usuario admin del realm (default: admin)
# =============================================================================
set -euo pipefail

KC_URL="${KC_URL:-http://localhost:8180}"
KC_REALM="${KC_REALM:-mdqr-admin}"
KC_USERNAME="${KC_USERNAME:-admin}"
KC_PASSWORD="${KC_PASSWORD:-admin}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-admin}"
SEED_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/keycloak-seed/admin" && pwd)"
TOKEN=""
REALM_EXISTS=false
CONFIRM_ALL=false

while [ $# -gt 0 ]; do
  case "$1" in
    --yes-to-all) CONFIRM_ALL=true; shift ;;
    -h|--help)
      echo "Usage: $(basename "$0") [--yes-to-all]"
      echo "Syncs the mdqr-admin Keycloak realm for the internal admin web app."
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
  # curl -s (sin -f): siempre retorna 0 si hay conexión; body vacío si KC no responde.
  # || true en la extracción jq: evita que set -e mate el script si jq falla.
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

kc_get()  { curl -sf -H "Authorization: Bearer ${TOKEN}" "${KC_URL}/admin/realms/${KC_REALM}$1"; }

kc_post() {
  curl -s -o /dev/null -w "%{http_code}" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -d "$2" "${KC_URL}/admin/realms/${KC_REALM}$1"
}

kc_put() {
  curl -s -o /dev/null -w "%{http_code}" -X PUT \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -d "$2" "${KC_URL}/admin/realms/${KC_REALM}$1"
}

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
    warn "Realm '${KC_REALM}' already exists — skip"
    REALM_EXISTS=true
    return
  fi

  info "Creating realm: ${KC_REALM}"
  curl -sf -o /dev/null \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -d "$(jq -n --arg r "$KC_REALM" '{
      realm: $r,
      enabled: true,
      registrationAllowed: false,
      loginWithEmailAllowed: true,
      duplicateEmailsAllowed: false,
      resetPasswordAllowed: true,
      editUsernameAllowed: false,
      bruteForceProtected: true
    }')" "${KC_URL}/admin/realms"
  info "Realm '${KC_REALM}' created"
}

# ─── Roles ──────────────────────────────────────────────────────────────
sync_roles() {
  header "Roles"
  local existing
  existing=$(kc_get "/roles" | jq -r '.[].name' 2>/dev/null || echo "")

  while IFS=, read -r name description; do
    [ "$name" = "name" ] && continue
    if echo "$existing" | grep -qx "$name"; then
      warn "Role '${name}' already exists — skip"
    else
      local code
      code=$(kc_post "/roles" "$(jq -n --arg n "$name" --arg d "$description" '{name:$n, description:$d}')")
      [ "$code" = "201" ] && info "Role '${name}' created" || error "Failed role '${name}' (HTTP ${code})"
    fi
  done < "${SEED_DIR}/roles.csv"
}

# ─── OIDC Client Scopes ─────────────────────────────────────────────────
sync_client_scopes() {
  header "OIDC Client Scopes"

  while IFS=, read -r name description assignToClient assignType; do
    [ "$name" = "name" ] && continue

    local scope_id
    scope_id=$(curl -sf -H "Authorization: Bearer ${TOKEN}" \
      "${KC_URL}/admin/realms/${KC_REALM}/client-scopes" \
      | jq -r ".[] | select(.name==\"${name}\") | .id" 2>/dev/null || echo "")

    if [ -z "$scope_id" ]; then
      local code
      code=$(curl -s -o /dev/null -w "%{http_code}" \
        -H "Authorization: Bearer ${TOKEN}" \
        -H "Content-Type: application/json" \
        -d "$(jq -n --arg n "$name" --arg d "$description" '{
          name:$n, description:$d,
          protocol:"openid-connect",
          attributes:{"include.in.token.scope":"true","display.on.consent.screen":"false"}
        }')" "${KC_URL}/admin/realms/${KC_REALM}/client-scopes")
      if [ "$code" = "201" ] || [ "$code" = "409" ]; then
        info "Client scope '${name}' created"
        scope_id=$(curl -sf -H "Authorization: Bearer ${TOKEN}" \
          "${KC_URL}/admin/realms/${KC_REALM}/client-scopes" \
          | jq -r ".[] | select(.name==\"${name}\") | .id")
      else
        error "Failed client scope '${name}' (HTTP ${code})"; continue
      fi
    else
      warn "Client scope '${name}' already exists — skip create"
    fi

    # Assign to target client
    if [ -n "$assignToClient" ] && [ -n "$scope_id" ]; then
      local cid
      cid=$(kc_get "/clients?clientId=${assignToClient}" | jq -r '.[0].id // empty')
      if [ -n "$cid" ]; then
        local kind="${assignType:-default}"
        local assign_code
        assign_code=$(curl -s -o /dev/null -w "%{http_code}" -X PUT \
          -H "Authorization: Bearer ${TOKEN}" \
          "${KC_URL}/admin/realms/${KC_REALM}/clients/${cid}/${kind}-client-scopes/${scope_id}")
        [ "$assign_code" = "204" ] && item "Assigned '${name}' as ${kind} scope to ${assignToClient}" \
          || warn "Could not assign '${name}' to ${assignToClient} (HTTP ${assign_code}) — client may not exist yet"
      fi
    fi
  done < "${SEED_DIR}/client-scopes.csv"
}

# ─── SPA Client (public, PKCE) ──────────────────────────────────────────
sync_spa_clients() {
  header "SPA Clients (public, PKCE S256)"

  while IFS=, read -r clientId description redirects origins defaultRoles; do
    [ "$clientId" = "clientId" ] && continue

    local existing_id
    existing_id=$(kc_get "/clients?clientId=${clientId}" | jq -r '.[0].id // empty')
    if [ -n "$existing_id" ]; then
      warn "SPA client '${clientId}' already exists — skip"; continue
    fi

    local redirects_json origins_json
    IFS=';' read -ra rarr <<< "$redirects"
    redirects_json=$(printf '%s\n' "${rarr[@]}" | jq -R . | jq -s .)
    IFS=';' read -ra oarr <<< "$origins"
    origins_json=$(printf '%s\n' "${oarr[@]}" | jq -R . | jq -s .)

    local new_id
    new_id=$(kc_post_get_id "/clients" "$(jq -n \
      --arg cid "$clientId" --arg desc "$description" \
      --argjson ru "$redirects_json" --argjson wo "$origins_json" '{
        clientId:$cid, name:$desc, enabled:true,
        protocol:"openid-connect", publicClient:true,
        standardFlowEnabled:true, directAccessGrantsEnabled:false,
        serviceAccountsEnabled:false, implicitFlowEnabled:false,
        redirectUris:$ru, webOrigins:$wo,
        attributes:{"pkce.code.challenge.method":"S256","post.logout.redirect.uris":"+"}
      }')")

    [ -z "$new_id" ] && { error "Failed to create SPA client '${clientId}'"; continue; }
    info "SPA client '${clientId}' created (${new_id})"
    item "redirectUris: ${redirects}"
  done < "${SEED_DIR}/spa-clients.csv"
}

# ─── Service Client (confidential, service accounts) ────────────────────
sync_service_clients() {
  header "Service Clients (confidential)"

  while IFS=, read -r clientId description secret; do
    [ "$clientId" = "clientId" ] && continue

    local existing_id
    existing_id=$(kc_get "/clients?clientId=${clientId}" | jq -r '.[0].id // empty')
    if [ -n "$existing_id" ]; then
      warn "Service client '${clientId}' already exists — skip"; continue
    fi

    local new_id
    new_id=$(kc_post_get_id "/clients" "$(jq -n \
      --arg cid "$clientId" --arg desc "$description" --arg s "$secret" '{
        clientId:$cid, name:$desc, secret:$s, enabled:true,
        protocol:"openid-connect", publicClient:false,
        serviceAccountsEnabled:true,
        authorizationServicesEnabled:false,
        directAccessGrantsEnabled:false, standardFlowEnabled:false
      }')")

    [ -z "$new_id" ] && { error "Failed to create service client '${clientId}'"; continue; }
    info "Service client '${clientId}' created (${new_id})"

    # Force secret
    curl -sf -o /dev/null -X PUT \
      -H "Authorization: Bearer ${TOKEN}" \
      -H "Content-Type: application/json" \
      -d "$(jq -n --arg id "$new_id" --arg cid "$clientId" --arg s "$secret" \
        '{id:$id, clientId:$cid, secret:$s}')" \
      "${KC_URL}/admin/realms/${KC_REALM}/clients/${new_id}" || true
    item "Secret: ${secret}"
  done < "${SEED_DIR}/service-clients.csv"
}

# ─── Service-account realm-management roles ─────────────────────────────
sync_mgmt_roles() {
  header "Service-account realm-management roles"

  local realm_mgmt_id
  realm_mgmt_id=$(kc_get "/clients?clientId=realm-management" | jq -r '.[0].id // empty')
  [ -z "$realm_mgmt_id" ] && { error "realm-management client not found"; return; }

  while IFS=, read -r clientId roles_csv; do
    [ "$clientId" = "clientId" ] && continue

    local cid
    cid=$(kc_get "/clients?clientId=${clientId}" | jq -r '.[0].id // empty')
    [ -z "$cid" ] && { error "Client '${clientId}' not found"; continue; }

    local sa_user_id
    sa_user_id=$(kc_get "/clients/${cid}/service-account-user" | jq -r '.id // empty')
    [ -z "$sa_user_id" ] && { error "Service account for '${clientId}' not found"; continue; }

    IFS=';' read -ra role_arr <<< "$roles_csv"
    local payload="["
    for role_name in "${role_arr[@]}"; do
      [ -z "$role_name" ] && continue
      local rjson
      rjson=$(kc_get "/clients/${realm_mgmt_id}/roles/${role_name}" 2>/dev/null || echo "")
      [ -z "$rjson" ] || [ "$rjson" = "null" ] && { warn "  role '${role_name}' not found — skip"; continue; }
      payload="${payload}{\"id\":\"$(echo "$rjson"|jq -r .id)\",\"name\":\"${role_name}\"},"
    done
    payload="${payload%,}]"
    [ "$payload" = "]" ] && continue

    local code
    code=$(curl -s -o /dev/null -w "%{http_code}" \
      -H "Authorization: Bearer ${TOKEN}" -H "Content-Type: application/json" \
      -d "$payload" \
      "${KC_URL}/admin/realms/${KC_REALM}/users/${sa_user_id}/role-mappings/clients/${realm_mgmt_id}")
    [ "$code" = "204" ] && info "Assigned [${roles_csv}] to ${clientId} service account" \
      || error "Failed to assign mgmt roles to ${clientId} (HTTP ${code})"
  done < "${SEED_DIR}/service-account-mgmt-roles.csv"
}

# ─── Admin user ─────────────────────────────────────────────────────────
create_admin_user() {
  header "Admin User"

  local existing_id
  existing_id=$(kc_get "/users?username=admin&exact=true" | jq -r '.[0].id // empty')
  if [ -n "$existing_id" ]; then
    warn "User 'admin' already exists (${existing_id}) — skip"; return
  fi

  local user_id
  user_id=$(kc_post_get_id "/users" "$(jq -n --arg pw "$ADMIN_PASSWORD" '{
    username:"admin", email:"admin@mdqr.local",
    firstName:"Admin", lastName:"MDQR",
    enabled:true, emailVerified:true,
    credentials:[{type:"password",value:$pw,temporary:false}]
  }')")
  [ -z "$user_id" ] && { error "Failed to create admin user"; return; }
  info "Admin user created (${user_id})"

  # Assign ADMIN role
  local admin_role
  admin_role=$(kc_get "/roles/ADMIN" | jq '{id:.id,name:.name}')
  if [ -n "$admin_role" ] && [ "$admin_role" != "null" ]; then
    local code
    code=$(curl -s -o /dev/null -w "%{http_code}" \
      -H "Authorization: Bearer ${TOKEN}" -H "Content-Type: application/json" \
      -d "[${admin_role}]" \
      "${KC_URL}/admin/realms/${KC_REALM}/users/${user_id}/role-mappings/realm")
    [ "$code" = "204" ] && info "Assigned ADMIN role to admin user" \
      || error "Failed to assign ADMIN role (HTTP ${code})"
  fi

  echo -e "    ${BOLD}username:${NC} admin"
  echo -e "    ${BOLD}password:${NC} ${ADMIN_PASSWORD}"
}

# ─── Test users (dev) ────────────────────────────────────────────────────
create_test_users() {
  header "Test Users (dev)"

  for entry in "operator:OPERATOR:Operador de prueba" "auditor:AUDITOR:Auditor de prueba"; do
    local username role description
    username=$(echo "$entry" | cut -d: -f1)
    role=$(echo "$entry"    | cut -d: -f2)
    description=$(echo "$entry" | cut -d: -f3)

    local existing_id
    existing_id=$(kc_get "/users?username=${username}&exact=true" | jq -r '.[0].id // empty')
    if [ -n "$existing_id" ]; then
      warn "User '${username}' already exists — skip"; continue
    fi

    local user_id
    user_id=$(kc_post_get_id "/users" "$(jq -n \
      --arg u "$username" --arg d "$description" '{
        username:$u, email:($u+"@mdqr.local"),
        firstName:$d, lastName:"Test",
        enabled:true, emailVerified:true,
        credentials:[{type:"password",value:$u,temporary:false}]
      }')")
    [ -z "$user_id" ] && { error "Failed to create user '${username}'"; continue; }

    local role_json
    role_json=$(kc_get "/roles/${role}" | jq '{id:.id,name:.name}')
    if [ -n "$role_json" ] && [ "$role_json" != "null" ]; then
      curl -s -o /dev/null -w "%{http_code}" \
        -H "Authorization: Bearer ${TOKEN}" -H "Content-Type: application/json" \
        -d "[${role_json}]" \
        "${KC_URL}/admin/realms/${KC_REALM}/users/${user_id}/role-mappings/realm" > /dev/null
    fi
    info "User '${username}' created (password: ${username}, role: ${role})"
  done
}

# ─── Events config ───────────────────────────────────────────────────────
sync_events_config() {
  header "Realm Events"
  local code
  code=$(curl -s -o /dev/null -w "%{http_code}" -X PUT \
    -H "Authorization: Bearer ${TOKEN}" -H "Content-Type: application/json" \
    -d "$(jq -n '{
      eventsEnabled:true, adminEventsEnabled:true, adminEventsDetailsEnabled:true,
      eventsListeners:["jboss-logging"],
      eventsExpiration:2592000,
      enabledEventTypes:["LOGIN","LOGIN_ERROR","LOGOUT","LOGOUT_ERROR",
        "REFRESH_TOKEN","REFRESH_TOKEN_ERROR","TOKEN_EXCHANGE","TOKEN_EXCHANGE_ERROR"]
    }')" "${KC_URL}/admin/realms/${KC_REALM}/events/config")
  [ "$code" = "204" ] && info "Events config updated (30d retention)" \
    || error "Failed to update events config (HTTP ${code})"
}

# ─── Main ────────────────────────────────────────────────────────────────
main() {
  echo -e "${BOLD}"
  echo "╔═══════════════════════════════════════════════════╗"
  echo "║   Keycloak Sync — Realm: mdqr-admin               ║"
  echo "╚═══════════════════════════════════════════════════╝"
  echo -e "${NC}"
  echo -e "  Server: ${CYAN}${KC_URL}${NC}"
  echo -e "  Realm:  ${CYAN}${KC_REALM}${NC}"
  echo -e "  Seed:   ${CYAN}${SEED_DIR}${NC}"

  for cmd in curl jq; do
    command -v "$cmd" &>/dev/null || { error "'${cmd}' not installed"; exit 1; }
  done

  info "Authenticating with Keycloak..."
  get_token
  info "Authenticated"

  echo ""
  if [ "$CONFIRM_ALL" = false ]; then
    echo -ne "${BOLD}${YELLOW}Apply changes to realm '${KC_REALM}'?${NC} [yes/N] "
    read -r confirm
    [ "$confirm" != "yes" ] && { warn "Aborted"; exit 0; }
  fi

  create_realm
  get_token

  sync_roles
  sync_service_clients
  sync_spa_clients
  sync_client_scopes
  sync_mgmt_roles
  create_admin_user
  create_test_users
  sync_events_config

  echo ""
  echo -e "${BOLD}${GREEN}═══ mdqr-admin sync complete ═══${NC}"
}

main "$@"
