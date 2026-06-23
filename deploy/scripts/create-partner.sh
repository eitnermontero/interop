#!/usr/bin/env bash
set -euo pipefail

# --- Defaults --------------------------------------------------------
KC_URL="${KC_URL:-http://localhost:8080}"
TENANT_ID="${TENANT_ID:-}"
NAMESPACE_BASE="${NAMESPACE_BASE:-mdqr}"
# Realm/vault namespace efectivo: <base>[-<tenant>]
if [ -n "$TENANT_ID" ]; then
  KC_REALM="${KC_REALM:-${NAMESPACE_BASE}-${TENANT_ID}}"
  VAULT_NAMESPACE="${NAMESPACE_BASE}-${TENANT_ID}"
else
  KC_REALM="${KC_REALM:-${NAMESPACE_BASE}}"
  VAULT_NAMESPACE="${NAMESPACE_BASE}"
fi
KC_USERNAME="${KC_USERNAME:-admin}"
KC_PASSWORD="${KC_PASSWORD:-admin}"

VAULT_CONTAINER="${VAULT_CONTAINER:-mdqr-vault}"
VAULT_ADDR="http://127.0.0.1:8200"

DB_CONTAINER="${DB_CONTAINER:-postgres}"
DB_NAME="${DB_NAME:-mdqr}"
DB_USER="${DB_USER:-postgres}"

KC_TOKEN=""
# Single/dev-mode Vault: root token preseteado. Override con VAULT_TOKEN para cluster.
VAULT_TOKEN="${VAULT_TOKEN:-root}"

# --- Arg defaults ----------------------------------------------------
PARTNER_NAME=""
CLIENT_ID=""
CLIENT_SECRET=""
DESCRIPTION=""
ROLES="cart:read;cart:write;cart:pay"
CONTACT_EMAIL=""
CONTACT_PHONE=""
GENESIS_USER=""
GENESIS_PASS=""
GENESIS_METODO="USUARIO"
GENESIS_ORITRA="GSIS"
ENVIRONMENT="STAGE"
RATE_LIMIT=100
IP_WHITELIST=""
DOMAIN_WHITELIST=""
EXPIRES_AT=""
WITH_DB=false
DRY_RUN=false

# --- Colors ----------------------------------------------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

info()   { echo -e "${GREEN}[+]${NC} $1"; }
warn()   { echo -e "${YELLOW}[!]${NC} $1"; }
error()  { echo -e "${RED}[✗]${NC} $1" >&2; }
header() { echo -e "\n${BOLD}${CYAN}═══ $1 ═══${NC}"; }
item()   { echo -e "    ${CYAN}→${NC} $1"; }
dry()    { echo -e "    ${YELLOW}[DRY]${NC} $1"; }

# --- Help ------------------------------------------------------------
show_help() {
  cat <<EOF
Usage: $(basename "${BASH_SOURCE[0]}") --name <name> [OPTIONS]

Creates a partner in Keycloak + Vault and optionally the database.
Partner ID will be: ptnr_<name>

Required:
  --name <name>               Partner short name (e.g. "acme" → ptnr_acme)

Keycloak:
  --client-id <id>            Keycloak client ID      (default: ptnr_<name>)
  --client-secret <secret>    Keycloak client secret  (default: same as client-id)
  --roles <r1;r2;r3>          Realm roles to assign   (default: cart:read;cart:write;cart:pay)
  --description <text>        Partner description     (default: "Partner <name>")

Vault (Genesis credentials):
  --genesis-user <user>       Genesis username        (prompted if omitted)
  --genesis-pass <pass>       Genesis password        (prompted if omitted)
  --genesis-metodo <metodo>   Genesis method          (default: USUARIO)
  --genesis-oritra <oritra>   Genesis oritra          (default: GSIS)

Database (optional):
  --db                        Also insert records into the database (partner + api_key_registry)
  --contact-email <email>     Contact email for DB record
  --contact-phone <phone>     Contact phone for DB record
  --environment <env>         API key environment: LIVE|STAGE   (default: STAGE)
  --rate-limit <n>            Requests/min rate limit           (default: 100)
  --ip-whitelist <ips>        Comma-separated IPs/CIDRs         (default: none)
  --domain-whitelist <hosts>  Comma-separated domains           (default: none)
  --expires-at <timestamp>    Expiry timestamp (ISO 8601)       (default: never)

Flags:
  --dry-run                   Show what would be done without executing
  -h, --help                  Show this message

Environment variables:
  KC_URL          Keycloak server URL        (default: http://localhost:8080)
  KC_REALM        Target realm               (default: mdqr)
  KC_USERNAME     Admin username             (default: admin)
  KC_PASSWORD     Admin password             (default: admin)
  VAULT_CONTAINER Vault container name       (default: mdqr-vault)
  VAULT_TOKEN     Vault token                (default: root)
  DB_CONTAINER    Postgres container name    (default: postgres)
  DB_NAME         Database name              (default: mdqr)
  DB_USER         Database user              (default: postgres)

Examples:
  $(basename "${BASH_SOURCE[0]}") --name acme
  $(basename "${BASH_SOURCE[0]}") --name acme --roles "cart:read;cart:write" --db
  $(basename "${BASH_SOURCE[0]}") --name acme --client-id cartcore_prod_acme --dry-run
  KC_URL=http://keycloak.prod:8080 $(basename "${BASH_SOURCE[0]}") --name acme
EOF
  exit 0
}

# --- Parse args ------------------------------------------------------
while [ $# -gt 0 ]; do
  case "$1" in
    --name)            PARTNER_NAME="$2";     shift 2 ;;
    --tenant)          TENANT_ID="$2";
                       KC_REALM="${NAMESPACE_BASE}-${TENANT_ID}"
                       VAULT_NAMESPACE="${NAMESPACE_BASE}-${TENANT_ID}"
                       shift 2 ;;
    --client-id)       CLIENT_ID="$2";        shift 2 ;;
    --client-secret)   CLIENT_SECRET="$2";    shift 2 ;;
    --description)     DESCRIPTION="$2";      shift 2 ;;
    --roles)           ROLES="$2";            shift 2 ;;
    --contact-email)   CONTACT_EMAIL="$2";    shift 2 ;;
    --contact-phone)   CONTACT_PHONE="$2";    shift 2 ;;
    --genesis-user)    GENESIS_USER="$2";     shift 2 ;;
    --genesis-pass)    GENESIS_PASS="$2";     shift 2 ;;
    --genesis-metodo)  GENESIS_METODO="$2";   shift 2 ;;
    --genesis-oritra)  GENESIS_ORITRA="$2";   shift 2 ;;
    --environment)     ENVIRONMENT="$2";       shift 2 ;;
    --rate-limit)      RATE_LIMIT="$2";        shift 2 ;;
    --ip-whitelist)    IP_WHITELIST="$2";      shift 2 ;;
    --domain-whitelist) DOMAIN_WHITELIST="$2"; shift 2 ;;
    --expires-at)      EXPIRES_AT="$2";        shift 2 ;;
    --db)              WITH_DB=true;           shift ;;
    --dry-run)         DRY_RUN=true;           shift ;;
    --help|-h)         show_help ;;
    *) error "Unknown option: $1. Use --help for usage."; exit 1 ;;
  esac
done

# --- Validate --------------------------------------------------------
if [ -z "$PARTNER_NAME" ]; then
  error "--name is required. Use --help for usage."
  exit 1
fi

if [[ "$PARTNER_NAME" =~ [^a-z0-9_] ]]; then
  error "--name must only contain lowercase letters, digits, and underscores."
  exit 1
fi

PARTNER_ID="ptnr_${PARTNER_NAME}"
CLIENT_ID="${CLIENT_ID:-${PARTNER_ID}}"
CLIENT_SECRET="${CLIENT_SECRET:-${CLIENT_ID}}"
DESCRIPTION="${DESCRIPTION:-Partner ${PARTNER_NAME}}"

if [[ "$ENVIRONMENT" != "LIVE" && "$ENVIRONMENT" != "STAGE" ]]; then
  error "--environment must be 'LIVE' or 'STAGE'"
  exit 1
fi

# --- Genesis credential prompts --------------------------------------
prompt_genesis_creds() {
  if [ -z "$GENESIS_USER" ]; then
    echo -ne "${BOLD}Genesis username for ${PARTNER_ID}:${NC} "
    read -r GENESIS_USER
  fi
  if [ -z "$GENESIS_PASS" ]; then
    echo -ne "${BOLD}Genesis password for ${PARTNER_ID}:${NC} "
    read -r GENESIS_PASS
  fi
}

# --- Keycloak helpers ------------------------------------------------
kc_get_token() {
  KC_TOKEN=$(curl -sf "${KC_URL}/realms/master/protocol/openid-connect/token" \
    -d "client_id=admin-cli" \
    -d "username=${KC_USERNAME}" \
    -d "password=${KC_PASSWORD}" \
    -d "grant_type=password" | jq -r '.access_token')

  if [ -z "$KC_TOKEN" ] || [ "$KC_TOKEN" = "null" ]; then
    error "Failed to authenticate with Keycloak at ${KC_URL}"
    exit 1
  fi
}

kc_get() {
  curl -sf -H "Authorization: Bearer ${KC_TOKEN}" "${KC_URL}/admin/realms/${KC_REALM}$1"
}

kc_post_get_id() {
  local path="$1" body="$2"
  curl -s -D - -o /dev/null \
    -H "Authorization: Bearer ${KC_TOKEN}" \
    -H "Content-Type: application/json" \
    -d "$body" \
    "${KC_URL}/admin/realms/${KC_REALM}${path}" \
    | grep -i "^location:" | tr -d '\r' | awk '{print $2}' | xargs basename
}

kc_put() {
  local path="$1" body="$2"
  curl -sf -o /dev/null -X PUT \
    -H "Authorization: Bearer ${KC_TOKEN}" \
    -H "Content-Type: application/json" \
    -d "$body" \
    "${KC_URL}/admin/realms/${KC_REALM}${path}"
}

# --- Vault helper ----------------------------------------------------
vault_get_token() {
  if [ ! "$(docker ps -q -f name="${VAULT_CONTAINER}" 2>/dev/null)" ]; then
    error "Vault container '${VAULT_CONTAINER}' is not running"
    exit 1
  fi

  # Single/dev mode: VAULT_TOKEN viene preseteado (root). Para cluster, exportar
  # VAULT_TOKEN con el root token de .vault-init.json antes de correr el script.
  if [ -z "$VAULT_TOKEN" ]; then
    error "VAULT_TOKEN vacio. En single mode usa 'root'; en cluster exporta el root token."
    exit 1
  fi
}

v() {
  docker exec -i \
    -e VAULT_TOKEN="$VAULT_TOKEN" \
    -e VAULT_ADDR="$VAULT_ADDR" \
    "$VAULT_CONTAINER" vault "$@"
}

# --- Preview ---------------------------------------------------------
preview() {
  header "Planned Changes"

  echo -e "\n  ${BOLD}Partner ID:${NC}   ${CYAN}${PARTNER_ID}${NC}"
  echo -e "  ${BOLD}Client ID:${NC}    ${CYAN}${CLIENT_ID}${NC}"
  echo -e "  ${BOLD}Description:${NC}  ${DESCRIPTION}"
  echo -e "  ${BOLD}Roles:${NC}        ${ROLES}"

  echo -e "\n  ${BOLD}1. Keycloak${NC}"
  item "CREATE client: ${CLIENT_ID}"
  item "  secret:      ${CLIENT_SECRET}"
  item "  roles:       ${ROLES}"

  echo -e "\n  ${BOLD}2. Vault${NC}"
  item "PUT secret/${VAULT_NAMESPACE}/partners/${PARTNER_ID}/genesis"
  item "  genesis-username: ${GENESIS_USER:-<will be prompted>}"
  item "  genesis-metodo:   ${GENESIS_METODO}"
  item "  genesis-oritra:   ${GENESIS_ORITRA}"
  item "PUT secret/${VAULT_NAMESPACE}/partners/${PARTNER_ID}/keycloak/${CLIENT_ID}"
  item "  client-secret:    ${CLIENT_SECRET}"

  if [ "$WITH_DB" = true ]; then
    echo -e "\n  ${BOLD}3. Database${NC}"
    item "INSERT INTO partner"
    item "  partner_id:        ${PARTNER_ID}"
    item "  partner_public_id: ${CLIENT_ID}"
    item "  name:              ${DESCRIPTION}"
    if [ -n "$CONTACT_EMAIL" ]; then item "  contact_email:     ${CONTACT_EMAIL}"; fi
    if [ -n "$CONTACT_PHONE" ]; then item "  contact_phone:     ${CONTACT_PHONE}"; fi
    item "INSERT INTO api_key_registry"
    item "  client_id:         ${CLIENT_ID}"
    item "  environment:       ${ENVIRONMENT}"
    item "  roles:             ${ROLES}"
    item "  rate_limit:        ${RATE_LIMIT}"
    if [ -n "$IP_WHITELIST" ];     then item "  ip_whitelist:      ${IP_WHITELIST}"; fi
    if [ -n "$DOMAIN_WHITELIST" ]; then item "  domain_whitelist:  ${DOMAIN_WHITELIST}"; fi
    if [ -n "$EXPIRES_AT" ];       then item "  expires_at:        ${EXPIRES_AT}"; fi
  fi
}

# --- Create in Keycloak ----------------------------------------------
create_keycloak_client() {
  header "Keycloak"

  local existing_id
  existing_id=$(kc_get "/clients?clientId=${CLIENT_ID}" | jq -r '.[0].id // empty')

  if [ -n "$existing_id" ]; then
    warn "Client '${CLIENT_ID}' already exists (${existing_id}) — skip"
    return
  fi

  info "Creating client: ${CLIENT_ID}"
  local new_id
  new_id=$(kc_post_get_id "/clients" "$(jq -n \
    --arg cid "$CLIENT_ID" \
    --arg desc "$DESCRIPTION" \
    --arg secret "$CLIENT_SECRET" \
    '{
      clientId: $cid,
      name: $desc,
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
    error "Failed to create client '${CLIENT_ID}'"
    exit 1
  fi
  info "Client created (ID: ${new_id})"

  # Force secret (Keycloak may generate a random one)
  kc_put "/clients/${new_id}" "$(jq -n \
    --arg id "$new_id" \
    --arg cid "$CLIENT_ID" \
    --arg desc "$DESCRIPTION" \
    --arg secret "$CLIENT_SECRET" \
    '{
      id: $id,
      clientId: $cid,
      name: $desc,
      secret: $secret,
      enabled: true,
      protocol: "openid-connect",
      publicClient: false,
      serviceAccountsEnabled: true,
      authorizationServicesEnabled: false,
      directAccessGrantsEnabled: false,
      standardFlowEnabled: false
    }')"
  info "Client secret set to '${CLIENT_SECRET}'"

  # Assign roles to service account
  local sa_user_id
  sa_user_id=$(kc_get "/clients/${new_id}/service-account-user" | jq -r '.id // empty')

  if [ -n "$sa_user_id" ] && [ -n "$ROLES" ]; then
    local role_payload="["
    IFS=';' read -ra role_arr <<< "$ROLES"
    for role_name in "${role_arr[@]}"; do
      local role_json
      role_json=$(kc_get "/roles/${role_name}" 2>/dev/null || echo "")
      if [ -n "$role_json" ] && [ "$role_json" != "null" ]; then
        local rid rname
        rid=$(echo "$role_json" | jq -r '.id')
        rname=$(echo "$role_json" | jq -r '.name')
        role_payload="${role_payload}{\"id\":\"${rid}\",\"name\":\"${rname}\"},"
      else
        warn "Role '${role_name}' not found — skipped"
      fi
    done
    role_payload="${role_payload%,}]"

    if [ "$role_payload" != "]" ]; then
      local code
      code=$(curl -s -o /dev/null -w "%{http_code}" \
        -H "Authorization: Bearer ${KC_TOKEN}" \
        -H "Content-Type: application/json" \
        -d "$role_payload" \
        "${KC_URL}/admin/realms/${KC_REALM}/users/${sa_user_id}/role-mappings/realm")
      if [ "$code" = "204" ]; then
        info "Roles assigned: ${ROLES}"
      else
        error "Failed to assign roles (HTTP ${code})"
      fi
    fi
  fi

  echo -e "\n    ${BOLD}client_id:${NC}     ${CLIENT_ID}"
  echo -e "    ${BOLD}client_secret:${NC} ${CLIENT_SECRET}"
}

# --- Create in Vault -------------------------------------------------
create_vault_secrets() {
  header "Vault"

  local genesis_path="secret/${VAULT_NAMESPACE}/partners/${PARTNER_ID}/genesis"
  local kc_path="secret/${VAULT_NAMESPACE}/partners/${PARTNER_ID}/keycloak/${CLIENT_ID}"

  if v kv get "$genesis_path" &>/dev/null; then
    warn "${genesis_path} already exists"
    echo -ne "    ${BOLD}Overwrite?${NC} [yes/N] "
    read -r overwrite_genesis
    if [ "$overwrite_genesis" = "yes" ]; then
      v kv put "$genesis_path" \
        genesis-username="$GENESIS_USER" \
        genesis-password="$GENESIS_PASS" \
        genesis-metodo="$GENESIS_METODO" \
        genesis-oritra="$GENESIS_ORITRA"
      info "${genesis_path} — overwritten"
    else
      info "${genesis_path} — skipped"
    fi
  else
    info "Writing genesis credentials..."
    v kv put "$genesis_path" \
      genesis-username="$GENESIS_USER" \
      genesis-password="$GENESIS_PASS" \
      genesis-metodo="$GENESIS_METODO" \
      genesis-oritra="$GENESIS_ORITRA"
    info "${genesis_path} — OK"
  fi

  if v kv get "$kc_path" &>/dev/null; then
    warn "${kc_path} already exists"
    echo -ne "    ${BOLD}Overwrite?${NC} [yes/N] "
    read -r overwrite_kc
    if [ "$overwrite_kc" = "yes" ]; then
      v kv put "$kc_path" client-secret="$CLIENT_SECRET"
      info "${kc_path} — overwritten"
    else
      info "${kc_path} — skipped"
    fi
  else
    info "Writing keycloak client secret..."
    v kv put "$kc_path" client-secret="$CLIENT_SECRET"
    info "${kc_path} — OK"
  fi
}

# --- Create in DB ----------------------------------------------------
create_db_record() {
  header "Database"

  if [ ! "$(docker ps -q -f name="${DB_CONTAINER}" 2>/dev/null)" ]; then
    error "DB container '${DB_CONTAINER}' is not running — skip"
    return
  fi

  # -- partner ----------------------------------------------------------
  local partner_db_id
  partner_db_id=$(docker exec "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -tAq \
    -c "SELECT id FROM partner WHERE partner_id = '${PARTNER_ID}'" 2>/dev/null || echo "")

  if [ -n "$partner_db_id" ]; then
    warn "partner '${PARTNER_ID}' already exists in DB (id=${partner_db_id}) — skip insert"
  else
    local email_val="NULL"
    local phone_val="NULL"
    [ -n "$CONTACT_EMAIL" ] && email_val="'${CONTACT_EMAIL}'"
    [ -n "$CONTACT_PHONE" ] && phone_val="'${CONTACT_PHONE}'"

    partner_db_id=$(docker exec "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -tAq \
      -c "INSERT INTO partner (
            id, partner_id, partner_public_id, name, description,
            contact_email, contact_phone, is_active,
            created_by, created_date, last_modified_by, last_modified_date
          ) VALUES (
            nextval('partner_seq'),
            '${PARTNER_ID}',
            '${CLIENT_ID}',
            '${DESCRIPTION}',
            '${DESCRIPTION}',
            ${email_val},
            ${phone_val},
            true,
            'create-partner-script',
            NOW(),
            'create-partner-script',
            NOW()
          ) RETURNING id;" 2>/dev/null || echo "")

    if [ -z "$partner_db_id" ]; then
      error "Failed to insert partner into DB"
      exit 1
    fi
    info "Partner '${PARTNER_ID}' inserted into DB (id=${partner_db_id})"
  fi

  # -- api_key_registry -------------------------------------------------
  local existing_key
  existing_key=$(docker exec "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -tAq \
    -c "SELECT id FROM api_key_registry WHERE client_id = '${CLIENT_ID}'" 2>/dev/null || echo "")

  if [ -n "$existing_key" ]; then
    warn "api_key_registry entry for '${CLIENT_ID}' already exists (id=${existing_key}) — skip insert"
    return
  fi

  # Build PostgreSQL array literals
  local roles_pg ip_pg domain_pg expires_val
  roles_pg="'{$(echo "$ROLES" | tr ';' ',')}'"
  [ -z "$IP_WHITELIST" ]     && ip_pg="'{}'"     || ip_pg="'{${IP_WHITELIST}}'"
  [ -z "$DOMAIN_WHITELIST" ] && domain_pg="'{}'" || domain_pg="'{${DOMAIN_WHITELIST}}'"
  expires_val="NULL"
  [ -n "$EXPIRES_AT" ] && expires_val="'${EXPIRES_AT}'"

  docker exec "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -c "
    INSERT INTO api_key_registry (
      id, partner_id, client_id, environment, description,
      roles, is_active, rate_limit,
      ip_whitelist, domain_whitelist, expires_at,
      created_by, created_date, last_modified_by, last_modified_date
    ) VALUES (
      nextval('api_key_registry_seq'),
      ${partner_db_id},
      '${CLIENT_ID}',
      '${ENVIRONMENT}',
      '${DESCRIPTION}',
      ${roles_pg},
      true,
      ${RATE_LIMIT},
      ${ip_pg},
      ${domain_pg},
      ${expires_val},
      'create-partner-script',
      NOW(),
      'create-partner-script',
      NOW()
    );
  " > /dev/null
  info "API key registry entry created for '${CLIENT_ID}' (env=${ENVIRONMENT})"
}

# --- Dry run ---------------------------------------------------------
run_dry() {
  header "Dry Run — no changes will be made"

  dry "kc_get_token → ${KC_URL}"
  dry "kc_post /clients → { clientId: ${CLIENT_ID}, secret: ${CLIENT_SECRET} }"
  dry "kc_assign roles [${ROLES}] → service account of ${CLIENT_ID}"
  dry "vault kv put secret/${VAULT_NAMESPACE}/partners/${PARTNER_ID}/genesis { genesis-username: ${GENESIS_USER:-<prompted>}, ... }"
  dry "vault kv put secret/${VAULT_NAMESPACE}/partners/${PARTNER_ID}/keycloak/${CLIENT_ID} { client-secret: ${CLIENT_SECRET} }"
  if [ "$WITH_DB" = true ]; then
    dry "psql INSERT INTO partner (partner_id='${PARTNER_ID}', partner_public_id='${CLIENT_ID}', ...)"
    dry "psql INSERT INTO api_key_registry (client_id='${CLIENT_ID}', environment='${ENVIRONMENT}', rate_limit=${RATE_LIMIT}, roles=[${ROLES}], ...)"
  fi

  echo ""
  info "Dry run complete — use without --dry-run to apply"
}

# --- Main ------------------------------------------------------------
main() {
  echo -e "${BOLD}"
  echo "╔═══════════════════════════════════════════════╗"
  echo "║         Create Partner — MDQR                 ║"
  echo "╚═══════════════════════════════════════════════╝"
  echo -e "${NC}"
  echo -e "  Partner ID:     ${CYAN}${PARTNER_ID}${NC}"
  echo -e "  Client ID:      ${CYAN}${CLIENT_ID}${NC}"
  echo -e "  Roles:          ${CYAN}${ROLES}${NC}"
  echo -e "  Keycloak:       ${CYAN}${KC_URL}${NC} (realm: ${KC_REALM})"
  echo -e "  Vault:          ${CYAN}${VAULT_CONTAINER}${NC}"
  if [ "$WITH_DB" = true ];  then echo -e "  DB:             ${CYAN}${DB_CONTAINER}${NC} (${DB_NAME})"; fi
  if [ "$DRY_RUN" = true ];  then echo -e "  ${YELLOW}Mode:           DRY RUN${NC}"; fi

  for cmd in curl jq docker; do
    if ! command -v "$cmd" &>/dev/null; then
      error "'${cmd}' is required but not installed"
      exit 1
    fi
  done

  # Prompt genesis creds before preview so they appear in it
  if [ "$DRY_RUN" = false ]; then
    prompt_genesis_creds
  fi

  preview

  echo ""
  echo -ne "${BOLD}${YELLOW}Apply these changes?${NC} [yes/N] "
  read -r confirm
  if [ "$confirm" != "yes" ]; then
    warn "Aborted"
    exit 0
  fi

  if [ "$DRY_RUN" = true ]; then
    run_dry
    exit 0
  fi

  echo ""
  header "Applying"

  info "Authenticating with Keycloak..."
  kc_get_token
  info "Keycloak authenticated"

  vault_get_token

  create_keycloak_client
  create_vault_secrets
  [ "$WITH_DB" = true ] && create_db_record

  echo ""
  echo -e "${BOLD}${GREEN}═══ Partner created successfully ═══${NC}"
  echo ""
  echo -e "  ${BOLD}partner_id:${NC}    ${PARTNER_ID}"
  echo -e "  ${BOLD}client_id:${NC}     ${CLIENT_ID}"
  echo -e "  ${BOLD}client_secret:${NC} ${CLIENT_SECRET}"
}

main "$@"
