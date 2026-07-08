#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# vault-pki.sh — PKI del hub sobre el motor `pki` de VAULT (ADR-0001)
#
# Reemplaza la PKI OpenSSL (create-pki.sh, deprecada). Jerarquía:
#   pki/      → HUB Root CA          (10 años, emite solo la intermedia)
#   pki_int/  → HUB Intermediate CA  (5 años, emite certs de servidor y partners)
#
# Uso:
#   deploy/scripts/vault-pki.sh init                    # CA raíz + intermedia + roles (una vez)
#   deploy/scripts/vault-pki.sh server <cn> [ip]        # cert TLS del gateway → server.p12 + truststore.p12
#   deploy/scripts/vault-pki.sh partner <nombre>        # cert de partner → .crt/.key/.p12 + chain
#   deploy/scripts/vault-pki.sh revoke <serial>         # revoca un certificado (entra al CRL)
#   deploy/scripts/vault-pki.sh status                  # estado de la PKI y certs emitidos
#
# Variables:
#   VAULT_ADDR (default http://127.0.0.1:8200)   VAULT_TOKEN (default root)
#   PARTNER_TTL (default 2160h = 90 días)        SERVER_TTL (default 8760h = 1 año)
#   KEYSTORE_PASSWORD / TRUSTSTORE_PASSWORD (default changeit)
#
# ⚠ El Vault del stack tools corre en DEV MODE (almacenamiento volátil): si el
#   contenedor se recrea, la CA se pierde y hay que re-emitir todo. Para
#   producción: Vault en modo real (raft) — es uno de los bloqueantes listados.
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail

VAULT_ADDR="${VAULT_ADDR:-http://127.0.0.1:8200}"
VAULT_TOKEN="${VAULT_TOKEN:-root}"
PARTNER_TTL="${PARTNER_TTL:-2160h}"
SERVER_TTL="${SERVER_TTL:-8760h}"
KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-changeit}"
TRUSTSTORE_PASSWORD="${TRUSTSTORE_PASSWORD:-changeit}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CERTS_DIR="$SCRIPT_DIR/../certs"
mkdir -p "$CERTS_DIR/partners"

GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
ok()   { echo -e "${GREEN}[✓]${NC} $1"; }
err()  { echo -e "${RED}[✗]${NC} $1" >&2; }
info() { echo -e "${CYAN}[i]${NC} $1"; }
warn() { echo -e "${YELLOW}[!]${NC} $1"; }

# curl → Vault HTTP API. vapi <método> <path> [json]
vapi() {
  local method="$1" path="$2" data="${3:-}"
  if [ -n "$data" ]; then
    curl -sf -X "$method" -H "X-Vault-Token: $VAULT_TOKEN" -d "$data" "$VAULT_ADDR/v1/$path"
  else
    curl -sf -X "$method" -H "X-Vault-Token: $VAULT_TOKEN" "$VAULT_ADDR/v1/$path"
  fi
}
jget() { python3 -c "import sys,json; d=json.load(sys.stdin)
for k in '$1'.split('.'): d=d[k]
print(d)"; }

mount_exists() { vapi GET sys/mounts | python3 -c "import sys,json; sys.exit(0 if '$1/' in json.load(sys.stdin) else 1)"; }

cmd_init() {
  info "Vault: $VAULT_ADDR"
  # ── Root CA ────────────────────────────────────────────────────────────
  if mount_exists pki; then
    warn "El mount pki/ ya existe — init es idempotente, no se recrea la CA"
  else
    vapi POST sys/mounts/pki '{"type":"pki","config":{"max_lease_ttl":"87600h"}}' >/dev/null
    vapi POST pki/root/generate/internal \
      '{"common_name":"HUB Root CA","organization":"Sintesis","country":"BO","ttl":"87600h","issuer_name":"hub-root"}' \
      | jget data.certificate > "$CERTS_DIR/vault-root-ca.crt"
    ok "Root CA creada (10 años) → $CERTS_DIR/vault-root-ca.crt"
    vapi POST pki/config/urls "{\"issuing_certificates\":\"$VAULT_ADDR/v1/pki/ca\",\"crl_distribution_points\":\"$VAULT_ADDR/v1/pki/crl\"}" >/dev/null
  fi

  # ── Intermediate CA ────────────────────────────────────────────────────
  if mount_exists pki_int; then
    warn "El mount pki_int/ ya existe — se conserva"
  else
    vapi POST sys/mounts/pki_int '{"type":"pki","config":{"max_lease_ttl":"43800h"}}' >/dev/null
    CSR=$(vapi POST pki_int/intermediate/generate/internal \
      '{"common_name":"HUB Intermediate CA","organization":"Sintesis","country":"BO"}' | jget data.csr)
    SIGNED=$(python3 -c "import json,sys; print(json.dumps({'csr': sys.argv[1], 'format':'pem_bundle', 'ttl':'43800h'}))" "$CSR" \
      | curl -sf -X POST -H "X-Vault-Token: $VAULT_TOKEN" -d @- "$VAULT_ADDR/v1/pki/root/sign-intermediate" | jget data.certificate)
    python3 -c "import json,sys; print(json.dumps({'certificate': sys.argv[1]}))" "$SIGNED" \
      | curl -sf -X POST -H "X-Vault-Token: $VAULT_TOKEN" -d @- "$VAULT_ADDR/v1/pki_int/intermediate/set-signed" >/dev/null
    vapi POST pki_int/config/urls "{\"issuing_certificates\":\"$VAULT_ADDR/v1/pki_int/ca\",\"crl_distribution_points\":\"$VAULT_ADDR/v1/pki_int/crl\"}" >/dev/null
    ok "Intermediate CA creada y firmada por la raíz (5 años)"
  fi

  # ── Cadena de confianza (root + intermedia) para clientes y truststore ─
  { curl -sf "$VAULT_ADDR/v1/pki_int/ca_chain"; echo; curl -sf "$VAULT_ADDR/v1/pki/ca/pem"; echo; } \
    > "$CERTS_DIR/vault-ca-chain.crt"
  ok "Cadena de CAs → $CERTS_DIR/vault-ca-chain.crt"

  # ── Roles de emisión ───────────────────────────────────────────────────
  vapi POST pki_int/roles/hub-partner "{
    \"allow_any_name\": true, \"enforce_hostnames\": false,
    \"client_flag\": true, \"server_flag\": false,
    \"key_type\": \"rsa\", \"key_bits\": 2048,
    \"max_ttl\": \"$PARTNER_TTL\", \"ttl\": \"$PARTNER_TTL\",
    \"organization\": \"Sintesis Partners\", \"country\": \"BO\"
  }" >/dev/null
  vapi POST pki_int/roles/hub-server "{
    \"allow_any_name\": true, \"enforce_hostnames\": false, \"allow_ip_sans\": true,
    \"client_flag\": false, \"server_flag\": true,
    \"key_type\": \"rsa\", \"key_bits\": 2048,
    \"max_ttl\": \"$SERVER_TTL\", \"ttl\": \"$SERVER_TTL\"
  }" >/dev/null
  ok "Roles de emisión: hub-partner (client, TTL $PARTNER_TTL) y hub-server (TLS, TTL $SERVER_TTL)"
  echo ""
  info "Siguiente: '$0 server <cn>' para el gateway y '$0 partner <nombre>' por cada partner"
}

emitir() { # emitir <role> <json-payload> <archivo-base>
  local role="$1" payload="$2" base="$3"
  local resp; resp=$(vapi POST "pki_int/issue/$role" "$payload")
  echo "$resp" | jget data.certificate > "$base.crt"
  echo "$resp" | jget data.private_key > "$base.key"
  chmod 600 "$base.key"
  echo "$resp" | python3 -c "import sys,json; print('\n'.join(json.load(sys.stdin)['data']['ca_chain']))" > "$base.chain.crt"
  echo "$resp" | jget data.serial_number
}

cmd_partner() {
  local nombre="${1:?uso: $0 partner <nombre>}"
  local base="$CERTS_DIR/partners/$nombre"
  info "Emitiendo certificado de partner '$nombre' (role hub-partner, TTL $PARTNER_TTL)..."
  local serial; serial=$(emitir hub-partner "{\"common_name\":\"$nombre\",\"ttl\":\"$PARTNER_TTL\"}" "$base")
  openssl pkcs12 -export -in "$base.crt" -inkey "$base.key" -certfile "$base.chain.crt" \
    -name "$nombre" -out "$base.p12" -passout "pass:$KEYSTORE_PASSWORD"
  chmod 600 "$base.p12"
  ok "Emitido. serial=$serial"
  echo "   $base.crt / .key / .p12 (password=$KEYSTORE_PASSWORD) / .chain.crt"
  echo "   Entregar al partner: $nombre.p12 + vault-ca-chain.crt (canal seguro)"
  echo "   Revocar con: $0 revoke $serial"
}

cmd_server() {
  local cn="${1:?uso: $0 server <cn> [ip]}"
  local ip="${2:-127.0.0.1}"
  local base="$CERTS_DIR/vault-server"
  info "Emitiendo certificado TLS del gateway (CN=$cn, IP SAN=$ip)..."
  local serial; serial=$(emitir hub-server "{\"common_name\":\"$cn\",\"alt_names\":\"localhost\",\"ip_sans\":\"$ip\",\"ttl\":\"$SERVER_TTL\"}" "$base")
  # Keystore del gateway (cert + clave + cadena)
  openssl pkcs12 -export -in "$base.crt" -inkey "$base.key" -certfile "$base.chain.crt" \
    -name "hub-gateway" -out "$CERTS_DIR/server.p12" -passout "pass:$KEYSTORE_PASSWORD"
  # Truststore (CAs que el gateway acepta como emisoras de certs de cliente).
  # openssl en vez de keytool: el servidor no tiene por qué tener un JDK instalado.
  rm -f "$CERTS_DIR/truststore.p12"
  openssl pkcs12 -export -in "$CERTS_DIR/vault-ca-chain.crt" -nokeys \
    -out "$CERTS_DIR/truststore.p12" -passout "pass:$TRUSTSTORE_PASSWORD" -name hub-vault-ca
  ok "Emitido. serial=$serial"
  echo "   $CERTS_DIR/server.p12 (password=$KEYSTORE_PASSWORD)"
  echo "   $CERTS_DIR/truststore.p12 (password=$TRUSTSTORE_PASSWORD)"
}

cmd_revoke() {
  local serial="${1:?uso: $0 revoke <serial>}"
  vapi POST pki_int/revoke "{\"serial_number\":\"$serial\"}" | jget data.revocation_time >/dev/null \
    && ok "Certificado $serial revocado (publicado en el CRL: $VAULT_ADDR/v1/pki_int/crl)"
}

cmd_status() {
  info "Vault: $VAULT_ADDR"
  for m in pki pki_int; do
    if mount_exists "$m"; then ok "mount $m/ activo"; else err "mount $m/ NO existe (correr init)"; fi
  done
  echo ""
  info "Certificados emitidos por la intermedia (seriales):"
  vapi LIST pki_int/certs 2>/dev/null | python3 -c "import sys,json
try:
    for k in json.load(sys.stdin)['data']['keys']: print('  ', k)
except Exception: print('   (ninguno)')" || echo "   (ninguno)"
}

case "${1:-help}" in
  init)    cmd_init ;;
  partner) shift; cmd_partner "$@" ;;
  server)  shift; cmd_server "$@" ;;
  revoke)  shift; cmd_revoke "$@" ;;
  status)  cmd_status ;;
  *) sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//' ;;
esac
