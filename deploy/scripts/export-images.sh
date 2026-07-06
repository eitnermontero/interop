#!/usr/bin/env bash
# Empaqueta las imágenes del hub en un tar.gz para llevarlas a un servidor
# SIN registry (docker save → scp → docker load).
#
# Uso:
#   deploy/scripts/export-images.sh                  # exporta gateway + base-service
#   deploy/scripts/export-images.sh --with-tools     # incluye también keycloak/consul/vault/redis/postgres
#
# Salida: deploy/dist/hub-images-<fecha>.tar.gz
#
# En el servidor destino:
#   docker load -i hub-images-<fecha>.tar.gz
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIST_DIR="$SCRIPT_DIR/../dist"
mkdir -p "$DIST_DIR"

REGISTRY="${REGISTRY:-cr.sintesis.com.bo/hub-dev}"
STAMP="$(date +%Y%m%d-%H%M)"
OUT="$DIST_DIR/hub-images-$STAMP.tar.gz"

IMAGES=(
  "$REGISTRY/hub-gateway:latest"
  "$REGISTRY/hub-ms-base:latest"
)

if [[ "${1:-}" == "--with-tools" ]]; then
  IMAGES+=(
    "quay.io/keycloak/keycloak:26.6"
    "hashicorp/consul:1.22"
    "hashicorp/vault:1.21"
    "redis:8.8"
    "postgres:17-alpine"
  )
fi

echo "[+] Exportando ${#IMAGES[@]} imagen(es) a $OUT ..."
docker save "${IMAGES[@]}" | gzip > "$OUT"
echo "[+] Listo: $(du -h "$OUT" | cut -f1)  $OUT"
echo ""
echo "Siguiente paso:"
echo "  scp $OUT usuario@servidor:/opt/hub/"
echo "  ssh usuario@servidor 'docker load -i /opt/hub/$(basename "$OUT")'"
