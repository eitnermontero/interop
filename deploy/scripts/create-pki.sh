#!/usr/bin/env bash
# Genera la PKI para mTLS del gateway HUB.
#
# ⚠ DEPRECADO: la PKI del hub ahora corre sobre el motor pki de Vault —
#   usar deploy/scripts/vault-pki.sh (init/server/partner/revoke).
#   Este script se conserva solo como referencia/contingencia sin Vault.
#
# Produce en deploy/certs/:
#   ca.crt              — Certificado raíz de la CA (importar en clientes/browsers)
#   ca.key              — Clave privada de la CA (NO distribuir)
#   server.p12          — Keystore del gateway (cert servidor + clave), contraseña $KEYSTORE_PASSWORD
#   truststore.p12      — Truststore del gateway (CA), contraseña $TRUSTSTORE_PASSWORD
#   partners/<name>.crt — Certificado del partner
#   partners/<name>.key — Clave privada del partner
#   partners/<name>.p12 — Bundle PKCS12 para el partner (cert + clave)
#
# Uso:
#   deploy/scripts/create-pki.sh                         # solo CA + server cert
#   deploy/scripts/create-pki.sh --partner unilink-api   # CA + server + cert partner
#   deploy/scripts/create-pki.sh --partner acme-corp     # añadir otro partner
#
# Opciones de entorno:
#   SERVER_CN          — CN del server cert  (default: 172.16.76.20)
#   CA_CN              — CN de la CA         (default: HUB Root CA)
#   CA_ORG             — Organización CA     (default: Sintesis)
#   CA_DAYS            — Validez CA en días  (default: 3650 = 10 años)
#   CERT_DAYS          — Validez certs       (default: 825 = ~2 años, máx iOS)
#   KEYSTORE_PASSWORD  — Password PKCS12 server keystore  (default: changeit)
#   TRUSTSTORE_PASSWORD— Password PKCS12 truststore       (default: changeit)
#
# Requiere: openssl, keytool (JDK)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CERTS_DIR="$(cd "$SCRIPT_DIR/../certs" && pwd)"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

info()   { echo -e "${GREEN}[+]${NC} $1"; }
warn()   { echo -e "${YELLOW}[!]${NC} $1"; }
header() { echo -e "\n${BOLD}${CYAN}=== $1 ===${NC}"; }

SERVER_CN="${SERVER_CN:-172.16.76.20}"
CA_CN="${CA_CN:-HUB Root CA}"
CA_ORG="${CA_ORG:-Sintesis}"
CA_COUNTRY="${CA_COUNTRY:-BO}"
CA_DAYS="${CA_DAYS:-3650}"
CERT_DAYS="${CERT_DAYS:-825}"
KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-changeit}"
TRUSTSTORE_PASSWORD="${TRUSTSTORE_PASSWORD:-changeit}"

PARTNER_NAME=""

usage() {
  echo "Uso: $0 [--partner <nombre>]"
  echo ""
  echo "  --partner <nombre>   Genera un certificado de cliente para el partner."
  echo "                       Puede llamarse varias veces o una vez con --partner."
  echo "  --server-cn <cn>     CN del certificado del servidor (default: $SERVER_CN)"
  echo ""
  echo "Ejemplos:"
  echo "  $0                            # solo CA + certificado servidor"
  echo "  $0 --partner unilink-api      # CA + servidor + partner"
  echo "  $0 --partner acme-corp        # agregar otro partner (CA ya existente)"
  exit 0
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --partner) PARTNER_NAME="$2"; shift 2 ;;
    --server-cn) SERVER_CN="$2"; shift 2 ;;
    -h|--help) usage ;;
    *) echo "Opción desconocida: $1"; usage ;;
  esac
done

mkdir -p "$CERTS_DIR/partners"

# ─── 1. CA ──────────────────────────────────────────────────────────────────
if [[ -f "$CERTS_DIR/ca.key" && -f "$CERTS_DIR/ca.crt" ]]; then
  warn "CA ya existe en $CERTS_DIR — reutilizando (omitir para generar nueva CA)."
else
  header "Generando CA raíz"
  openssl genrsa -out "$CERTS_DIR/ca.key" 4096 2>/dev/null
  openssl req -new -x509 \
    -days "$CA_DAYS" \
    -key "$CERTS_DIR/ca.key" \
    -out "$CERTS_DIR/ca.crt" \
    -subj "/C=${CA_COUNTRY}/O=${CA_ORG}/CN=${CA_CN}"
  chmod 600 "$CERTS_DIR/ca.key"
  info "CA generada: $CERTS_DIR/ca.crt"
fi

# ─── 2. Certificado del servidor (gateway) ────────────────────────────────────
if [[ -f "$CERTS_DIR/server.p12" ]]; then
  warn "Keystore del servidor ya existe — omitiendo generación del server cert."
else
  header "Generando certificado del servidor (CN=$SERVER_CN)"

  # Extension SAN: IP + DNS del servidor
  SAN_FILE="$(mktemp)"
  cat > "$SAN_FILE" <<EOF
[req]
distinguished_name = req_distinguished_name
req_extensions = v3_req
prompt = no
[req_distinguished_name]
C = ${CA_COUNTRY}
O = ${CA_ORG}
CN = ${SERVER_CN}
[v3_req]
subjectAltName = @alt_names
[alt_names]
IP.1 = ${SERVER_CN}
DNS.1 = ${SERVER_CN}
DNS.2 = localhost
EOF

  openssl genrsa -out "$CERTS_DIR/server.key" 2048 2>/dev/null
  openssl req -new \
    -key "$CERTS_DIR/server.key" \
    -out "$CERTS_DIR/server.csr" \
    -config "$SAN_FILE"
  openssl x509 -req \
    -days "$CERT_DAYS" \
    -in "$CERTS_DIR/server.csr" \
    -CA "$CERTS_DIR/ca.crt" \
    -CAkey "$CERTS_DIR/ca.key" \
    -CAcreateserial \
    -out "$CERTS_DIR/server.crt" \
    -extensions v3_req \
    -extfile "$SAN_FILE" 2>/dev/null
  rm -f "$SAN_FILE" "$CERTS_DIR/server.csr"

  # Keystore PKCS12 (cert + clave) para Spring Boot
  openssl pkcs12 -export \
    -in "$CERTS_DIR/server.crt" \
    -inkey "$CERTS_DIR/server.key" \
    -out "$CERTS_DIR/server.p12" \
    -name gateway \
    -passout "pass:${KEYSTORE_PASSWORD}"
  chmod 640 "$CERTS_DIR/server.p12" "$CERTS_DIR/server.key"
  info "Keystore del servidor: $CERTS_DIR/server.p12 (alias=gateway)"
fi

# ─── 3. Truststore (CA cert) para que el gateway valide certs de clientes ────
# Usa openssl pkcs12 (no requiere keytool/JDK).
# Spring Boot acepta PKCS12 con solo la cadena de confianza (sin clave privada).
if [[ -f "$CERTS_DIR/truststore.p12" ]]; then
  warn "Truststore ya existe — omitiendo."
else
  header "Generando truststore"
  openssl pkcs12 -export \
    -nokeys \
    -in "$CERTS_DIR/ca.crt" \
    -out "$CERTS_DIR/truststore.p12" \
    -passout "pass:${TRUSTSTORE_PASSWORD}" \
    -name hub-ca 2>/dev/null
  info "Truststore: $CERTS_DIR/truststore.p12 (alias=hub-ca)"
fi

# ─── 4. Certificado de cliente (partner) ─────────────────────────────────────
if [[ -n "$PARTNER_NAME" ]]; then
  PARTNER_DIR="$CERTS_DIR/partners"
  PARTNER_KEY="$PARTNER_DIR/${PARTNER_NAME}.key"
  PARTNER_CRT="$PARTNER_DIR/${PARTNER_NAME}.crt"
  PARTNER_P12="$PARTNER_DIR/${PARTNER_NAME}.p12"

  if [[ -f "$PARTNER_P12" ]]; then
    warn "Certificado para '$PARTNER_NAME' ya existe — omitiendo."
  else
    header "Generando certificado de cliente para partner: $PARTNER_NAME"

    openssl genrsa -out "$PARTNER_KEY" 2048 2>/dev/null
    openssl req -new \
      -key "$PARTNER_KEY" \
      -out "$PARTNER_DIR/${PARTNER_NAME}.csr" \
      -subj "/C=${CA_COUNTRY}/O=${CA_ORG}/CN=${PARTNER_NAME}"
    openssl x509 -req \
      -days "$CERT_DAYS" \
      -in "$PARTNER_DIR/${PARTNER_NAME}.csr" \
      -CA "$CERTS_DIR/ca.crt" \
      -CAkey "$CERTS_DIR/ca.key" \
      -CAcreateserial \
      -out "$PARTNER_CRT" 2>/dev/null
    rm -f "$PARTNER_DIR/${PARTNER_NAME}.csr"

    # Bundle PKCS12 para el partner (para usar con curl o librerías TLS)
    openssl pkcs12 -export \
      -in "$PARTNER_CRT" \
      -inkey "$PARTNER_KEY" \
      -out "$PARTNER_P12" \
      -name "${PARTNER_NAME}" \
      -passout "pass:${KEYSTORE_PASSWORD}"
    chmod 600 "$PARTNER_KEY" "$PARTNER_P12"
    info "Certificado de cliente: $PARTNER_P12"

    # Calcular thumbprint SHA-256 (mismo que cnf.x5t#S256 en el JWT)
    THUMBPRINT=$(openssl x509 -in "$PARTNER_CRT" -outform DER \
      | openssl dgst -sha256 -binary \
      | openssl base64 -A \
      | tr '+/' '-_' \
      | tr -d '=')
    echo ""
    echo -e "${BOLD}  Thumbprint SHA-256 (Base64url) de ${PARTNER_NAME}:${NC}"
    echo -e "  ${CYAN}${THUMBPRINT}${NC}"
    echo ""
    echo "  -> Registrar este thumbprint en Keycloak si se usa la validación estática."
    echo "     Con X.509 client auth habilitado, Keycloak lo emite automáticamente"
    echo "     en el claim cnf.x5t#S256 del token."
  fi
fi

# ─── Resumen ─────────────────────────────────────────────────────────────────
header "Resumen de certs en $CERTS_DIR"
ls -lh "$CERTS_DIR"/*.crt "$CERTS_DIR"/*.p12 "$CERTS_DIR"/*.key 2>/dev/null \
  | awk '{print "  "$NF, $5}'
[[ -n "$(ls $CERTS_DIR/partners/ 2>/dev/null)" ]] && \
  ls -lh "$CERTS_DIR/partners/" | awk '{print "  partners/"$NF, $5}'

echo ""
info "PKI lista. Pasos siguientes:"
echo "  1. Configurar SSL_KEYSTORE_PASSWORD y SSL_TRUSTSTORE_PASSWORD en deploy/production/.env"
echo "  2. Importar ca.crt en los navegadores/sistemas de los admins"
echo "  3. Entregar partners/<nombre>.p12 al partner (password = KEYSTORE_PASSWORD)"
echo "  4. Configurar X.509 client auth en Keycloak (realm hub-partner)"
