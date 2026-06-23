#!/usr/bin/env bash
set -euo pipefail

# --- Defaults --------------------------------------------------------
KC_URL="${KC_URL:-http://localhost:8080}"
KC_REALM_BASE="${KC_REALM_BASE:-mdqr}"
TENANT_ID="${TENANT_ID:-}"
KC_USERNAME="${KC_USERNAME:-admin}"
KC_PASSWORD="${KC_PASSWORD:-admin}"
KC_SERVICE_CLIENT="${KC_SERVICE_CLIENT:-mdqr-api}"
KC_SOBOCE_TEST_USER="${KC_SOBOCE_TEST_USER:-soboce-test}"
KC_SOBOCE_TEST_PASSWORD="${KC_SOBOCE_TEST_PASSWORD:-soboce123}"
KC_REDIRECT_URIS="${KC_REDIRECT_URIS:-http://localhost:8080/*,http://localhost:8081/*}"
KC_WEB_ORIGINS="${KC_WEB_ORIGINS:-http://localhost:8080,http://localhost:8081}"
SEED_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/keycloak-seed" && pwd)"
TOKEN=""

# Keycloak event ingestion: admin-service polls Keycloak Admin API for LOGIN/LOGOUT/REFRESH
# events via GET /admin/realms/{realm}/events - no SPI provider or webhook required.
# This script ensures the realm stores events (eventsEnabled, types, retention) so the
# poller can read them. No webhook variables needed for polling mode.

# ─── Parse flags ─────────────────────────────────────────────────────
CONFIRM_ALL=false

show_help() {
  cat <<EOF
Usage: $(basename "${BASH_SOURCE[0]}") [OPTIONS]

Idempotent Keycloak realm sync — creates missing resources, skips existing ones.

Options:
  --tenant <id>  Tenant ID. Si se setea, realm = <KC_REALM_BASE>-<tenant>
                 (ej. mdqr-alpha). Sin sufijo si vacio.
  -h, --help     Show this help message

Environment variables:
  KC_URL              Keycloak server URL          (default: http://localhost:8080)
  KC_REALM_BASE       Base realm name              (default: mdqr)
  TENANT_ID           Tenant ID (puede ser env o --tenant)
  KC_REALM            Override directo del realm (ignora KC_REALM_BASE/TENANT_ID)
  KC_SERVICE_CLIENT   Service client ID            (default: mdqr-api)
  KC_USERNAME              Admin username           (default: admin)
  KC_PASSWORD              Admin password           (default: admin)
  KC_SOBOCE_TEST_USER      SOBOCE test username     (default: soboce-test)
  KC_SOBOCE_TEST_PASSWORD  SOBOCE test password     (default: soboce123)

  Keycloak event storage (required for polling mode in admin-service):
  This script always enables eventsEnabled=true with LOGIN/LOGOUT types and 30d retention.
  admin-service polls GET /admin/realms/{realm}/events via the Admin API client - no extra
  env vars needed here. Configure poll interval via APPLICATION_AUDIT_KEYCLOAK_POLL_INTERVAL_MS.

Examples:
  # single-tenant — realm: mdqr
  $(basename "${BASH_SOURCE[0]}")

  # tenant alpha — realm: mdqr-alpha
  $(basename "${BASH_SOURCE[0]}") --tenant alpha

  # override completo
  KC_REALM=otro-realm $(basename "${BASH_SOURCE[0]}")
EOF
  exit 0
}

while [ $# -gt 0 ]; do
  case "$1" in
  --tenant)
    TENANT_ID="$2"
    shift 2
    ;;
  --help | -h) show_help ;;
  --yes-to-all)
    CONFIRM_ALL=true
    shift
    ;;
  *)
    echo "Unknown option: $1. Use --help for usage."
    exit 1
    ;;
  esac
done

# Realm efectivo: KC_REALM (override directo) > KC_REALM_BASE[-TENANT_ID]
if [ -z "${KC_REALM:-}" ]; then
  if [ -n "$TENANT_ID" ]; then
    KC_REALM="${KC_REALM_BASE}-${TENANT_ID}"
  else
    KC_REALM="${KC_REALM_BASE}"
  fi
fi

# --- Colors ----------------------------------------------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

# --- Helpers ---------------------------------------------------------
info() { echo -e "${GREEN}[+]${NC} $1"; }
warn() { echo -e "${YELLOW}[!]${NC} $1"; }
error() { echo -e "${RED}[✗]${NC} $1"; }
header() { echo -e "\n${BOLD}${CYAN}═══ $1 ═══${NC}"; }
item() { echo -e "    ${CYAN}→${NC} $1"; }

get_token() {
  TOKEN=$(curl -sf "${KC_URL}/realms/master/protocol/openid-connect/token" \
    -d "client_id=admin-cli" \
    -d "username=${KC_USERNAME}" \
    -d "password=${KC_PASSWORD}" \
    -d "grant_type=password" | jq -r '.access_token')

  if [ -z "$TOKEN" ] || [ "$TOKEN" = "null" ]; then
    error "Failed to authenticate with Keycloak at ${KC_URL}"
    exit 1
  fi
}

kc_get() {
  curl -sf -H "Authorization: Bearer ${TOKEN}" "${KC_URL}/admin/realms/${KC_REALM}$1"
}

kc_post() {
  local path="$1" body="$2"
  curl -s -o /dev/null -w "%{http_code}" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -d "$body" \
    "${KC_URL}/admin/realms/${KC_REALM}${path}"
}

kc_post_get_id() {
  local path="$1" body="$2"
  local location
  location=$(curl -s -D - -o /dev/null \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -d "$body" \
    "${KC_URL}/admin/realms/${KC_REALM}${path}" | grep -i "^location:" | tr -d '\r' | awk '{print $2}')
  echo "${location##*/}"
}

# --- Read realm state ------------------------------------------------
read_realm_state() {
  header "Current Realm State: ${KC_REALM}"

  local realm_check
  realm_check=$(curl -s -o /dev/null -w "%{http_code}" \
    -H "Authorization: Bearer ${TOKEN}" \
    "${KC_URL}/admin/realms/${KC_REALM}")

  if [ "$realm_check" = "404" ]; then
    warn "Realm '${KC_REALM}' does not exist — will be created"
    REALM_EXISTS=false
    EXISTING_ROLES=""
    EXISTING_CLIENTS=""
    return
  fi
  REALM_EXISTS=true

  echo -e "\n  ${BOLD}Realm Roles:${NC}"
  EXISTING_ROLES=$(kc_get "/roles" | jq -r '.[].name' | sort)
  if [ -n "$EXISTING_ROLES" ]; then
    echo "$EXISTING_ROLES" | while read -r r; do item "$r"; done
  else
    item "(none)"
  fi

  echo -e "\n  ${BOLD}Clients:${NC}"
  EXISTING_CLIENTS=$(kc_get "/clients?max=100" | jq -r '.[].clientId' | sort)
  echo "$EXISTING_CLIENTS" | while read -r c; do item "$c"; done

  local svc_id
  svc_id=$(kc_get "/clients?clientId=${KC_SERVICE_CLIENT}" | jq -r '.[0].id // empty')
  if [ -n "$svc_id" ]; then
    echo -e "\n  ${BOLD}Service Client (${KC_SERVICE_CLIENT}):${NC}"
    item "ID: ${svc_id}"

    echo -e "  ${BOLD}  Authorization Scopes:${NC}"
    local scopes
    scopes=$(kc_get "/clients/${svc_id}/authz/resource-server/scope" 2>/dev/null | jq -r '.[].name' 2>/dev/null || echo "")
    if [ -n "$scopes" ]; then echo "$scopes" | while read -r s; do item "  $s"; done; else item "  (none)"; fi

    echo -e "  ${BOLD}  Resources:${NC}"
    local resources
    resources=$(kc_get "/clients/${svc_id}/authz/resource-server/resource" 2>/dev/null | jq -r '.[].name' 2>/dev/null || echo "")
    if [ -n "$resources" ]; then echo "$resources" | while read -r res; do item "  $res"; done; else item "  (none)"; fi

    echo -e "  ${BOLD}  Policies:${NC}"
    local policies
    policies=$(kc_get "/clients/${svc_id}/authz/resource-server/policy" 2>/dev/null | jq -r '.[].name' 2>/dev/null || echo "")
    if [ -n "$policies" ]; then echo "$policies" | while read -r p; do item "  $p"; done; else item "  (none)"; fi

    echo -e "  ${BOLD}  Permissions:${NC}"
    local permissions
    permissions=$(kc_get "/clients/${svc_id}/authz/resource-server/permission" 2>/dev/null | jq -r '.[].name' 2>/dev/null || echo "")
    if [ -n "$permissions" ]; then echo "$permissions" | while read -r pm; do item "  $pm"; done; else item "  (none)"; fi
  else
    warn "Service client '${KC_SERVICE_CLIENT}' not found — will be created"
  fi
}

# --- Preview ---------------------------------------------------------
preview_changes() {
  header "Planned Changes"

  echo -e "\n  ${BOLD}1. Realm${NC}"
  if [ "$REALM_EXISTS" = false ]; then
    item "CREATE realm: ${KC_REALM}"
  else
    item "EXISTS — keep"
  fi

  echo -e "\n  ${BOLD}2. Service Client${NC}"
  if echo "$EXISTING_CLIENTS" | grep -qx "${KC_SERVICE_CLIENT}" 2>/dev/null; then
    item "EXISTS: ${KC_SERVICE_CLIENT} — skip"
  else
    item "CREATE: ${KC_SERVICE_CLIENT} (confidential, service-accounts, authorization)"
  fi

  echo -e "\n  ${BOLD}3. Realm Roles${NC} (from roles.csv)"
  while IFS=, read -r name description; do
    [ "$name" = "name" ] && continue
    if echo "$EXISTING_ROLES" | grep -qx "$name" 2>/dev/null; then
      item "EXISTS: ${name} — skip"
    else
      item "CREATE: ${name} — ${description}"
    fi
  done <"${SEED_DIR}/roles.csv"

  echo -e "\n  ${BOLD}4. Authorization Scopes${NC} (from scopes.csv)"
  while IFS=, read -r name displayName; do
    [ "$name" = "name" ] && continue
    item "SYNC: ${name} (${displayName})"
  done <"${SEED_DIR}/scopes.csv"

  if [ -f "${SEED_DIR}/client-scopes.csv" ]; then
    echo -e "\n  ${BOLD}4b. OIDC Client Scopes${NC} (from client-scopes.csv)"
    while IFS=, read -r name description assignToSvc assignType; do
      [ "$name" = "name" ] && continue
      [ -z "$name" ] && continue
      item "SYNC: ${name} (${assignType:-default} scope of ${KC_SERVICE_CLIENT}: ${assignToSvc})"
    done <"${SEED_DIR}/client-scopes.csv"
  fi

  echo -e "\n  ${BOLD}5. Resources${NC} (from resources.csv)"
  while IFS=, read -r name displayName uris scopes; do
    [ "$name" = "name" ] && continue
    item "SYNC: ${name} → ${uris} [${scopes}]"
  done <"${SEED_DIR}/resources.csv"

  echo -e "\n  ${BOLD}6. Policies${NC} (from policies.csv)"
  while IFS=, read -r name description type roles; do
    [ "$name" = "name" ] && continue
    item "SYNC: ${name} (${type}) → roles: ${roles}"
  done <"${SEED_DIR}/policies.csv"

  echo -e "\n  ${BOLD}7. Permissions${NC} (from permissions.csv)"
  while IFS=, read -r name description resource policy scopes; do
    [ "$name" = "name" ] && continue
    item "SYNC: ${name} → resource: ${resource}, policy: ${policy}, scopes: ${scopes}"
  done <"${SEED_DIR}/permissions.csv"

  echo -e "\n  ${BOLD}8. Admin User${NC}"
  local existing_admin
  existing_admin=$(kc_get "/users?username=admin&exact=true" 2>/dev/null | jq -r '.[0].id // empty' 2>/dev/null || echo "")
  if [ -n "$existing_admin" ]; then
    item "EXISTS: admin — skip"
  else
    item "CREATE: admin/admin with ALL realm roles"
  fi

  echo -e "\n  ${BOLD}9. Partner Clients${NC} (from clients.csv)"
  while IFS=, read -r clientId description roles; do
    [ "$clientId" = "clientId" ] && continue
    if echo "$EXISTING_CLIENTS" | grep -qx "$clientId" 2>/dev/null; then
      item "EXISTS: ${clientId} — skip"
    else
      item "CREATE: ${clientId} — roles: ${roles}"
    fi
  done <"${SEED_DIR}/clients.csv"

  echo -e "\n  ${BOLD}10. SPA Clients${NC} (from spa-clients.csv)"
  while IFS=, read -r clientId description redirects origins defaultRoles; do
    [ "$clientId" = "clientId" ] && continue
    if echo "$EXISTING_CLIENTS" | grep -qx "$clientId" 2>/dev/null; then
      item "EXISTS: ${clientId} — skip"
    else
      item "CREATE: ${clientId} (public, PKCE S256) → ${redirects} [default user role: ${defaultRoles}]"
    fi
  done <"${SEED_DIR}/spa-clients.csv"

  if [ -f "${SEED_DIR}/service-account-mgmt-roles.csv" ]; then
    echo -e "\n  ${BOLD}10b. Service-account realm-management roles${NC} (from service-account-mgmt-roles.csv)"
    while IFS=, read -r clientId realmMgmtRoles; do
      [ "$clientId" = "clientId" ] && continue
      item "SYNC: ${clientId} ← realm-management: ${realmMgmtRoles}"
    done <"${SEED_DIR}/service-account-mgmt-roles.csv"
  fi

  echo -e "\n  ${BOLD}11. SOBOCE Test User${NC}"
  local existing_soboce
  existing_soboce=$(kc_get "/users?username=${KC_SOBOCE_TEST_USER}&exact=true" 2>/dev/null | jq -r '.[0].id // empty' 2>/dev/null || echo "")
  if [ -n "$existing_soboce" ]; then
    item "EXISTS: ${KC_SOBOCE_TEST_USER} — skip"
  else
    item "CREATE: ${KC_SOBOCE_TEST_USER} with roles [soboce:cliente, soboce:operador]"
  fi

  echo -e "\n  ${BOLD}12. Keycloak Events (polling mode)${NC}"
  item "CONFIGURE: eventsEnabled=true, retention 30d, LOGIN/LOGOUT/REFRESH types"
  item "admin-service polls Keycloak Admin API - no SPI provider required"
}

# --- Execute ---------------------------------------------------------

create_realm() {
  if [ "$REALM_EXISTS" = false ]; then
    info "Creating realm: ${KC_REALM}"
    curl -sf -o /dev/null \
      -H "Authorization: Bearer ${TOKEN}" \
      -H "Content-Type: application/json" \
      -d "{\"realm\":\"${KC_REALM}\",\"enabled\":true}" \
      "${KC_URL}/admin/realms"
  fi
}

client_json() {
  # Convert comma-separated values to JSON arrays
  local redirect_json=$(echo "${KC_REDIRECT_URIS}" | awk -F',' '{for(i=1;i<=NF;i++) printf "\"%s\"%s", $i, (i<NF?",":"")}')
  local origins_json=$(echo "${KC_WEB_ORIGINS}" | awk -F',' '{for(i=1;i<=NF;i++) printf "\"%s\"%s", $i, (i<NF?",":"")}')

  cat <<JSON
{
  "clientId": "${KC_SERVICE_CLIENT}",
  "name": "MDQR API",
  "enabled": true,
  "protocol": "openid-connect",
  "publicClient": false,
  "secret": "mdqr-api",
  "serviceAccountsEnabled": true,
  "authorizationServicesEnabled": true,
  "directAccessGrantsEnabled": true,
  "standardFlowEnabled": true,
  "redirectUris": [${redirect_json}],
  "webOrigins": [${origins_json}]
}
JSON
}

set_client_secret() {
  local cid="$1"
  curl -sf -o /dev/null -X PUT \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -d "$(client_json | jq --arg id "$cid" '. + {id: $id}')" \
    "${KC_URL}/admin/realms/${KC_REALM}/clients/${cid}"
}

create_service_client() {
  local existing_id
  existing_id=$(kc_get "/clients?clientId=${KC_SERVICE_CLIENT}" | jq -r '.[0].id // empty')

  if [ -n "$existing_id" ]; then
    info "Service client '${KC_SERVICE_CLIENT}' already exists (${existing_id})"
    SVC_CLIENT_ID="$existing_id"

    local current_secret
    current_secret=$(kc_get "/clients/${SVC_CLIENT_ID}/client-secret" | jq -r '.value // empty')
    if [ "$current_secret" != "mdqr-api" ]; then
      info "Resetting client secret to 'mdqr-api'"
      set_client_secret "$SVC_CLIENT_ID"
    fi
    return
  fi

  info "Creating service client: ${KC_SERVICE_CLIENT}"
  SVC_CLIENT_ID=$(kc_post_get_id "/clients" "$(client_json)")
  info "Created with ID: ${SVC_CLIENT_ID}"

  info "Setting client secret to 'mdqr-api'"
  set_client_secret "$SVC_CLIENT_ID"
}

sync_roles() {
  header "Syncing Realm Roles"
  while IFS=, read -r name description; do
    [ "$name" = "name" ] && continue
    if echo "$EXISTING_ROLES" | grep -qx "$name" 2>/dev/null; then
      warn "Role '${name}' already exists — skip"
      continue
    fi
    local code
    code=$(kc_post "/roles" "{\"name\":\"${name}\",\"description\":\"${description}\"}")
    if [ "$code" = "201" ] || [ "$code" = "204" ] || [ "$code" = "409" ]; then
      info "Role '${name}' created"
    else
      error "Failed to create role '${name}' (HTTP ${code})"
    fi
  done <"${SEED_DIR}/roles.csv"
}

sync_scopes() {
  header "Syncing Authorization Scopes"
  while IFS=, read -r name displayName; do
    [ "$name" = "name" ] && continue
    local existing
    existing=$(kc_get "/clients/${SVC_CLIENT_ID}/authz/resource-server/scope?name=${name}" | jq -r '.[0].id // empty')
    if [ -n "$existing" ]; then
      warn "Scope '${name}' already exists — skip"
      continue
    fi
    local code
    code=$(kc_post "/clients/${SVC_CLIENT_ID}/authz/resource-server/scope" \
      "{\"name\":\"${name}\",\"displayName\":\"${displayName}\"}")
    if [ "$code" = "201" ] || [ "$code" = "204" ]; then
      info "Scope '${name}' created"
    else
      error "Failed to create scope '${name}' (HTTP ${code})"
    fi
  done <"${SEED_DIR}/scopes.csv"
}

sync_resources() {
  header "Syncing Resources"
  while IFS=, read -r name displayName uris scopes_csv; do
    [ "$name" = "name" ] && continue
    local existing
    existing=$(kc_get "/clients/${SVC_CLIENT_ID}/authz/resource-server/resource?name=${name}" | jq -r '.[0]._id // empty')
    if [ -n "$existing" ]; then
      warn "Resource '${name}' already exists — skip"
      continue
    fi

    local uris_json="[]"
    IFS=';' read -ra uri_arr <<<"$uris"
    uris_json=$(printf '%s\n' "${uri_arr[@]}" | jq -R . | jq -s .)

    local scopes_json="[]"
    IFS=';' read -ra scope_arr <<<"$scopes_csv"
    scopes_json=$(printf '%s\n' "${scope_arr[@]}" | jq -R '{name: .}' | jq -s .)

    local body
    body=$(jq -n \
      --arg name "$name" \
      --arg displayName "$displayName" \
      --argjson uris "$uris_json" \
      --argjson scopes "$scopes_json" \
      '{name: $name, displayName: $displayName, uris: $uris, scopes: $scopes}')

    local code
    code=$(kc_post "/clients/${SVC_CLIENT_ID}/authz/resource-server/resource" "$body")
    if [ "$code" = "201" ]; then
      info "Resource '${name}' created"
    else
      error "Failed to create resource '${name}' (HTTP ${code})"
    fi
  done <"${SEED_DIR}/resources.csv"
}

sync_policies() {
  header "Syncing Policies"
  while IFS=, read -r name description type roles_csv; do
    [ "$name" = "name" ] && continue
    local existing
    existing=$(kc_get "/clients/${SVC_CLIENT_ID}/authz/resource-server/policy?name=${name}" | jq -r '.[0].id // empty')
    if [ -n "$existing" ]; then
      warn "Policy '${name}' already exists — skip"
      continue
    fi

    local roles_json="[]"
    IFS=';' read -ra role_arr <<<"$roles_csv"
    local role_objects=""
    for role_name in "${role_arr[@]}"; do
      local role_id
      role_id=$(kc_get "/roles" | jq -r ".[] | select(.name==\"${role_name}\") | .id")
      if [ -n "$role_id" ]; then
        role_objects="${role_objects}{\"id\":\"${role_id}\",\"required\":true},"
      else
        error "Role '${role_name}' not found for policy '${name}'"
      fi
    done
    role_objects="[${role_objects%,}]"

    local body
    body=$(jq -n \
      --arg name "$name" \
      --arg description "$description" \
      --arg type "$type" \
      --argjson roles "$role_objects" \
      '{name: $name, description: $description, type: $type, logic: "POSITIVE", roles: $roles}')

    local code
    code=$(kc_post "/clients/${SVC_CLIENT_ID}/authz/resource-server/policy/role" "$body")
    if [ "$code" = "201" ]; then
      info "Policy '${name}' created"
    else
      error "Failed to create policy '${name}' (HTTP ${code})"
    fi
  done <"${SEED_DIR}/policies.csv"
}

sync_permissions() {
  header "Syncing Permissions"
  while IFS=, read -r name description resource_name policy_name scopes_csv; do
    [ "$name" = "name" ] && continue
    local existing
    existing=$(kc_get "/clients/${SVC_CLIENT_ID}/authz/resource-server/permission?name=${name}" | jq -r '.[0].id // empty')
    if [ -n "$existing" ]; then
      warn "Permission '${name}' already exists — skip"
      continue
    fi

    local resource_id
    resource_id=$(kc_get "/clients/${SVC_CLIENT_ID}/authz/resource-server/resource?name=${resource_name}" | jq -r '.[0]._id // empty')

    local policy_id
    policy_id=$(kc_get "/clients/${SVC_CLIENT_ID}/authz/resource-server/policy?name=${policy_name}" | jq -r '.[0].id // empty')

    IFS=';' read -ra scope_arr <<<"$scopes_csv"
    local scope_ids=""
    for scope_name in "${scope_arr[@]}"; do
      local sid
      sid=$(kc_get "/clients/${SVC_CLIENT_ID}/authz/resource-server/scope?name=${scope_name}" | jq -r '.[0].id // empty')
      if [ -n "$sid" ]; then
        scope_ids="${scope_ids}\"${sid}\","
      fi
    done
    scope_ids="[${scope_ids%,}]"

    local body
    body=$(jq -n \
      --arg name "$name" \
      --arg description "$description" \
      --argjson resources "[\"${resource_id}\"]" \
      --argjson policies "[\"${policy_id}\"]" \
      --argjson scopes "$scope_ids" \
      '{name: $name, description: $description, type: "scope", decisionStrategy: "UNANIMOUS", resources: $resources, policies: $policies, scopes: $scopes}')

    local code
    code=$(kc_post "/clients/${SVC_CLIENT_ID}/authz/resource-server/permission/scope" "$body")
    if [ "$code" = "201" ]; then
      info "Permission '${name}' created"
    else
      error "Failed to create permission '${name}' (HTTP ${code})"
    fi
  done <"${SEED_DIR}/permissions.csv"
}

sync_client_scopes() {
  header "Syncing OIDC Client Scopes"

  local seed_file="${SEED_DIR}/client-scopes.csv"
  if [ ! -f "$seed_file" ]; then
    warn "No client-scopes.csv — skipping"
    return
  fi

  while IFS=, read -r name description assignToSvc assignType; do
    [ "$name" = "name" ] && continue
    [ -z "$name" ] && continue

    # Realm-level OIDC client scope, included in the token 'scope' claim so the
    # default Spring converter maps it to SCOPE_<name>.
    local scope_id
    scope_id=$(kc_get "/client-scopes" | jq -r ".[] | select(.name==\"${name}\") | .id" 2>/dev/null || echo "")

    if [ -n "$scope_id" ]; then
      warn "Client scope '${name}' already exists — skip create"
    else
      local body
      body=$(jq -n \
        --arg name "$name" \
        --arg description "$description" \
        '{
          name: $name,
          description: $description,
          protocol: "openid-connect",
          attributes: {
            "include.in.token.scope": "true",
            "display.on.consent.screen": "false"
          }
        }')
      local code
      code=$(kc_post "/client-scopes" "$body")
      if [ "$code" = "201" ] || [ "$code" = "204" ] || [ "$code" = "409" ]; then
        info "Client scope '${name}' created"
        scope_id=$(kc_get "/client-scopes" | jq -r ".[] | select(.name==\"${name}\") | .id" 2>/dev/null || echo "")
      else
        error "Failed to create client scope '${name}' (HTTP ${code})"
        continue
      fi
    fi

    # Assign to the service client as a default (or optional) scope.
    if [ "$assignToSvc" = "true" ] && [ -n "${SVC_CLIENT_ID:-}" ] && [ -n "$scope_id" ]; then
      local kind="${assignType:-default}"
      local code
      code=$(curl -s -o /dev/null -w "%{http_code}" -X PUT \
        -H "Authorization: Bearer ${TOKEN}" \
        "${KC_URL}/admin/realms/${KC_REALM}/clients/${SVC_CLIENT_ID}/${kind}-client-scopes/${scope_id}")
      if [ "$code" = "204" ] || [ "$code" = "201" ]; then
        info "Assigned '${name}' as ${kind} scope of ${KC_SERVICE_CLIENT}"
      else
        error "Failed to assign '${name}' to ${KC_SERVICE_CLIENT} (HTTP ${code})"
      fi
    fi
  done <"$seed_file"
}

create_admin_user() {
  header "Creating Admin User"

  local existing_id
  existing_id=$(kc_get "/users?username=admin&exact=true" | jq -r '.[0].id // empty')
  if [ -n "$existing_id" ]; then
    warn "User 'admin' already exists (${existing_id}) — skip"
    return
  fi

  info "Creating user: admin"
  local user_id
  user_id=$(kc_post_get_id "/users" "$(jq -n '{
    username: "admin",
    email: "admin@mdqr.local",
    firstName: "Admin",
    lastName: "MDQR",
    enabled: true,
    emailVerified: true,
    credentials: [{type: "password", value: "admin", temporary: false}]
  }')")

  if [ -z "$user_id" ]; then
    error "Failed to create admin user"
    return
  fi
  info "Admin user created (${user_id})"

  local all_roles
  all_roles=$(kc_get "/roles" | jq '[.[] | select(.name | startswith("cart:") or . == "SUPER_ADMIN") | {id: .id, name: .name}]')
  local role_count
  role_count=$(echo "$all_roles" | jq 'length')

  if [ "$role_count" -gt 0 ]; then
    local code
    code=$(curl -s -o /dev/null -w "%{http_code}" \
      -H "Authorization: Bearer ${TOKEN}" \
      -H "Content-Type: application/json" \
      -d "$all_roles" \
      "${KC_URL}/admin/realms/${KC_REALM}/users/${user_id}/role-mappings/realm")
    if [ "$code" = "204" ]; then
      info "Assigned ${role_count} roles to admin user"
    else
      error "Failed to assign roles to admin (HTTP ${code})"
    fi
  fi

  echo -e "    ${BOLD}username:${NC} admin"
  echo -e "    ${BOLD}password:${NC} admin"
}

sync_realm_management_roles() {
  header "Syncing service-account realm-management role mappings"

  local seed_file="${SEED_DIR}/service-account-mgmt-roles.csv"
  if [ ! -f "$seed_file" ]; then
    warn "No service-account-mgmt-roles.csv — skipping"
    return
  fi

  # Resolve realm-management client id once.
  local realm_mgmt_id
  realm_mgmt_id=$(kc_get "/clients?clientId=realm-management" | jq -r '.[0].id // empty')
  if [ -z "$realm_mgmt_id" ]; then
    error "realm-management client not found in realm — cannot assign management roles"
    return
  fi

  while IFS=, read -r clientId realmMgmtRoles; do
    [ "$clientId" = "clientId" ] && continue
    [ -z "$clientId" ] && continue

    local client_id
    client_id=$(kc_get "/clients?clientId=${clientId}" | jq -r '.[0].id // empty')
    if [ -z "$client_id" ]; then
      error "Client '${clientId}' not found — cannot assign realm-management roles"
      continue
    fi

    local sa_user_id
    sa_user_id=$(kc_get "/clients/${client_id}/service-account-user" | jq -r '.id // empty')
    if [ -z "$sa_user_id" ]; then
      error "Service account for '${clientId}' not found — make sure serviceAccountsEnabled=true"
      continue
    fi

    IFS=';' read -ra role_arr <<<"$realmMgmtRoles"
    local payload="["
    for role_name in "${role_arr[@]}"; do
      [ -z "$role_name" ] && continue
      local role_json
      role_json=$(kc_get "/clients/${realm_mgmt_id}/roles/${role_name}" 2>/dev/null)
      if [ -z "$role_json" ] || [ "$role_json" = "null" ]; then
        warn "realm-management role '${role_name}' not found — skipping"
        continue
      fi
      local rid rname
      rid=$(echo "$role_json" | jq -r '.id')
      rname=$(echo "$role_json" | jq -r '.name')
      payload="${payload}{\"id\":\"${rid}\",\"name\":\"${rname}\"},"
    done
    payload="${payload%,}]"

    if [ "$payload" = "]" ]; then
      warn "No valid realm-management roles for '${clientId}' — skip"
      continue
    fi

    local code
    code=$(curl -s -o /dev/null -w "%{http_code}" \
      -H "Authorization: Bearer ${TOKEN}" \
      -H "Content-Type: application/json" \
      -d "$payload" \
      "${KC_URL}/admin/realms/${KC_REALM}/users/${sa_user_id}/role-mappings/clients/${realm_mgmt_id}")
    if [ "$code" = "204" ]; then
      info "Assigned realm-management roles [${realmMgmtRoles}] to ${clientId} service account"
    else
      error "Failed to assign realm-management roles to ${clientId} (HTTP ${code})"
    fi
  done <"$seed_file"
}

sync_clients() {
  header "Syncing Partner Clients"
  while IFS=, read -r clientId description roles_csv client_secret; do
    [ "$clientId" = "clientId" ] && continue

    local existing_id
    existing_id=$(kc_get "/clients?clientId=${clientId}" | jq -r '.[0].id // empty')
    if [ -n "$existing_id" ]; then
      warn "Client '${clientId}' already exists — skip"
      continue
    fi

    info "Creating client: ${clientId}"
    local new_id
    new_id=$(kc_post_get_id "/clients" "$(jq -n \
      --arg clientId "$clientId" \
      --arg description "$description" \
      --arg secret "${client_secret:-$clientId}" \
      '{
        clientId: $clientId,
        name: $description,
        secret: $secret,
        enabled: true,
        protocol: "openid-connect",
        publicClient: false,
        serviceAccountsEnabled: true,
        authorizationServicesEnabled: false,
        directAccessGrantsEnabled: false,
        standardFlowEnabled: false
      }')")

    if [ -z "$new_id" ]; then
      error "Failed to create client '${clientId}'"
      continue
    fi
    info "Client '${clientId}' created (${new_id})"

    # Force client secret
    local desired_secret="${client_secret:-$clientId}"
    curl -sf -o /dev/null -X PUT \
      -H "Authorization: Bearer ${TOKEN}" \
      -H "Content-Type: application/json" \
      -d "$(jq -n --arg id "$new_id" --arg cid "$clientId" --arg s "$desired_secret" \
        '{id: $id, clientId: $cid, secret: $s}')" \
      "${KC_URL}/admin/realms/${KC_REALM}/clients/${new_id}"
    info "Client secret set to '${desired_secret}'"

    local sa_user_id
    sa_user_id=$(kc_get "/clients/${new_id}/service-account-user" | jq -r '.id // empty')

    if [ -n "$sa_user_id" ] && [ -n "$roles_csv" ]; then
      IFS=';' read -ra role_arr <<<"$roles_csv"
      local role_payload="["
      for role_name in "${role_arr[@]}"; do
        local role_json
        role_json=$(kc_get "/roles/${role_name}" 2>/dev/null)
        if [ -n "$role_json" ] && [ "$role_json" != "null" ]; then
          local rid rname
          rid=$(echo "$role_json" | jq -r '.id')
          rname=$(echo "$role_json" | jq -r '.name')
          role_payload="${role_payload}{\"id\":\"${rid}\",\"name\":\"${rname}\"},"
        else
          warn "Role '${role_name}' not found — skipping assignment for ${clientId}"
        fi
      done
      role_payload="${role_payload%,}]"

      if [ "$role_payload" != "]" ]; then
        local code
        code=$(curl -s -o /dev/null -w "%{http_code}" \
          -H "Authorization: Bearer ${TOKEN}" \
          -H "Content-Type: application/json" \
          -d "$role_payload" \
          "${KC_URL}/admin/realms/${KC_REALM}/users/${sa_user_id}/role-mappings/realm")
        if [ "$code" = "204" ]; then
          info "Assigned roles [${roles_csv}] to ${clientId}"
        else
          error "Failed to assign roles to ${clientId} (HTTP ${code})"
        fi
      fi
    fi

    local secret
    secret=$(kc_get "/clients/${new_id}/client-secret" | jq -r '.value // empty')
    if [ -n "$secret" ]; then
      echo -e "    ${BOLD}client_id:${NC}     ${clientId}"
      echo -e "    ${BOLD}client_secret:${NC} ${secret}"
    fi
  done <"${SEED_DIR}/clients.csv"
}

sync_spa_clients() {
  header "Syncing SPA Clients"
  while IFS=, read -r clientId description redirects origins defaultRoles; do
    [ "$clientId" = "clientId" ] && continue

    local existing_id
    existing_id=$(kc_get "/clients?clientId=${clientId}" | jq -r '.[0].id // empty')
    if [ -n "$existing_id" ]; then
      warn "SPA client '${clientId}' already exists — skip"
      continue
    fi

    local redirects_json origins_json
    IFS=';' read -ra redirect_arr <<<"$redirects"
    redirects_json=$(printf '%s\n' "${redirect_arr[@]}" | jq -R . | jq -s .)
    IFS=';' read -ra origin_arr <<<"$origins"
    origins_json=$(printf '%s\n' "${origin_arr[@]}" | jq -R . | jq -s .)

    info "Creating SPA client: ${clientId}"
    local new_id
    new_id=$(kc_post_get_id "/clients" "$(jq -n \
      --arg clientId "$clientId" \
      --arg description "$description" \
      --argjson redirects "$redirects_json" \
      --argjson origins "$origins_json" \
      '{
        clientId: $clientId,
        name: $description,
        enabled: true,
        protocol: "openid-connect",
        publicClient: true,
        standardFlowEnabled: true,
        directAccessGrantsEnabled: false,
        serviceAccountsEnabled: false,
        implicitFlowEnabled: false,
        redirectUris: $redirects,
        webOrigins: $origins,
        attributes: {
          "pkce.code.challenge.method": "S256",
          "post.logout.redirect.uris": "+"
        }
      }')")

    if [ -z "$new_id" ]; then
      error "Failed to create SPA client '${clientId}'"
      continue
    fi
    info "SPA client '${clientId}' created (${new_id})"
    item "redirectUris: ${redirects}"
    item "webOrigins:   ${origins}"
    item "default user role hint: ${defaultRoles}"
  done <"${SEED_DIR}/spa-clients.csv"
}

create_soboce_test_user() {
  header "Creating SOBOCE Test User"

  local existing_id
  existing_id=$(kc_get "/users?username=${KC_SOBOCE_TEST_USER}&exact=true" | jq -r '.[0].id // empty')
  if [ -n "$existing_id" ]; then
    warn "User '${KC_SOBOCE_TEST_USER}' already exists (${existing_id}) — skip"
    return
  fi

  info "Creating user: ${KC_SOBOCE_TEST_USER}"
  local user_id
  user_id=$(kc_post_get_id "/users" "$(jq -n \
    --arg username "$KC_SOBOCE_TEST_USER" \
    --arg password "$KC_SOBOCE_TEST_PASSWORD" \
    '{
      username: $username,
      email: ($username + "@mdqr.local"),
      firstName: "SOBOCE",
      lastName: "Test",
      enabled: true,
      emailVerified: true,
      credentials: [{type: "password", value: $password, temporary: false}]
    }')")

  if [ -z "$user_id" ]; then
    error "Failed to create SOBOCE test user"
    return
  fi
  info "SOBOCE test user created (${user_id})"

  local soboce_roles
  soboce_roles=$(kc_get "/roles" | jq '[.[] | select(.name | startswith("soboce:")) | {id: .id, name: .name}]')
  local role_count
  role_count=$(echo "$soboce_roles" | jq 'length')

  if [ "$role_count" -gt 0 ]; then
    local code
    code=$(curl -s -o /dev/null -w "%{http_code}" \
      -H "Authorization: Bearer ${TOKEN}" \
      -H "Content-Type: application/json" \
      -d "$soboce_roles" \
      "${KC_URL}/admin/realms/${KC_REALM}/users/${user_id}/role-mappings/realm")
    if [ "$code" = "204" ]; then
      info "Assigned ${role_count} SOBOCE roles to ${KC_SOBOCE_TEST_USER}"
    else
      error "Failed to assign SOBOCE roles (HTTP ${code})"
    fi
  else
    warn "No 'soboce:*' roles found — make sure roles.csv was synced first"
  fi

  echo -e "    ${BOLD}username:${NC} ${KC_SOBOCE_TEST_USER}"
  echo -e "    ${BOLD}password:${NC} ${KC_SOBOCE_TEST_PASSWORD}"
}

sync_events_config() {
  header "Keycloak Events (polling mode)"

  # admin-service polls Keycloak Admin API for LOGIN/LOGOUT events - no SPI provider needed.
  # This step ensures the realm stores events with the required types and retention so
  # the poller can find them. Idempotent: safe to re-run.
  info "Enabling realm event storage (eventsEnabled, event types, retention)"

  local body
  body=$(jq -n '{
    eventsEnabled: true,
    adminEventsEnabled: true,
    adminEventsDetailsEnabled: true,
    eventsListeners: ["jboss-logging"],
    eventsExpiration: 2592000,
    enabledEventTypes: [
      "LOGIN", "LOGIN_ERROR", "LOGOUT", "LOGOUT_ERROR",
      "REFRESH_TOKEN", "REFRESH_TOKEN_ERROR",
      "TOKEN_EXCHANGE", "TOKEN_EXCHANGE_ERROR"
    ]
  }')

  local code
  code=$(curl -s -o /dev/null -w "%{http_code}" -X PUT \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -d "$body" \
    "${KC_URL}/admin/realms/${KC_REALM}/events/config")

  if [ "$code" = "204" ]; then
    info "Realm events config updated"
    item "eventsEnabled: true"
    item "eventsExpiration: 2592000 (30 days)"
    item "events: LOGIN LOGIN_ERROR LOGOUT LOGOUT_ERROR REFRESH_TOKEN REFRESH_TOKEN_ERROR"
  else
    error "Failed to update realm events config (HTTP ${code})"
  fi
}

# ─── Main ────────────────────────────────────────────────────────────
main() {
  echo -e "${BOLD}"
  echo "╔═══════════════════════════════════════════════╗"
  echo "║       Keycloak Realm Sync — MDQR              ║"
  echo "╚═══════════════════════════════════════════════╝"
  echo -e "${NC}"
  echo -e "  Server:         ${CYAN}${KC_URL}${NC}"
  echo -e "  Realm:          ${CYAN}${KC_REALM}${NC}"
  echo -e "  Service Client: ${CYAN}${KC_SERVICE_CLIENT}${NC}"
  echo -e "  User:           ${CYAN}${KC_USERNAME}${NC}"
  echo -e "  Seed dir:       ${CYAN}${SEED_DIR}${NC}"

  for f in roles.csv scopes.csv resources.csv policies.csv permissions.csv clients.csv spa-clients.csv; do
    if [ ! -f "${SEED_DIR}/${f}" ]; then
      error "Missing seed file: ${SEED_DIR}/${f}"
      exit 1
    fi
  done

  for cmd in curl jq; do
    if ! command -v "$cmd" &>/dev/null; then
      error "'${cmd}' is required but not installed"
      exit 1
    fi
  done

  info "Authenticating with Keycloak..."
  get_token
  info "Authenticated"

  read_realm_state

  preview_changes

  echo ""
  if [ "$CONFIRM_ALL" = false ]; then
    echo -ne "${BOLD}${YELLOW}Apply these changes?${NC} [yes/N] "
    read -r confirm
    if [ "$confirm" != "yes" ]; then
      warn "Aborted by user"
      exit 0
    fi
  else
    info "Auto-applying changes (--yes-to-all)"
  fi

  echo ""
  header "Applying Changes"

  create_realm

  get_token

  create_service_client
  sync_roles
  sync_scopes
  sync_client_scopes
  sync_resources
  sync_policies
  sync_permissions
  create_admin_user
  sync_clients
  sync_spa_clients
  sync_realm_management_roles
  create_soboce_test_user
  sync_events_config

  echo ""
  echo -e "${BOLD}${GREEN}═══ Sync Complete ═══${NC}"
}

main "$@"
