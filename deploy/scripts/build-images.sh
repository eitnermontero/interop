#!/usr/bin/env bash
# Construye las imágenes Docker de los microservicios HUB directamente en el servidor.
#
# Requiere: JDK 25 accesible en /tmp/gradle-9.2.1 (ver instrucciones) o
#           como variable GRADLE_HOME. Si no está, se descarga Gradle 9.2.1.
#
# Uso:
#   deploy/scripts/build-images.sh                    # build de los 3 servicios
#   deploy/scripts/build-images.sh --gateway-only     # solo gateway
#   deploy/scripts/build-images.sh --tag 1.0.1        # tag alternativo
#
# Las imágenes quedan en el Docker daemon local con el prefijo:
#   local/hub/hub-gateway:<tag>
#   local/hub/hub-ms-base:<tag>
#   local/hub/hub-ms-auth:<tag>

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DOCKERFILE="$SCRIPT_DIR/../docker/Dockerfile.service"
GRADLE_VERSION="9.2.1"
GRADLE_HOME="${GRADLE_HOME:-/tmp/gradle-${GRADLE_VERSION}}"
REGISTRY="${REGISTRY:-local/hub}"
TAG="${TAG:-1.0.0}"

BUILD_GATEWAY=true
BUILD_MS_BASE=true
BUILD_MS_AUTH=true

RED='\033[0;31m'
GREEN='\033[0;32m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

info()   { echo -e "${GREEN}[+]${NC} $1"; }
header() { echo -e "\n${BOLD}${CYAN}=== $1 ===${NC}"; }
error()  { echo -e "${RED}[x]${NC} $1" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --gateway-only) BUILD_MS_BASE=false; BUILD_MS_AUTH=false; shift ;;
    --tag) TAG="$2"; shift 2 ;;
    --registry) REGISTRY="$2"; shift 2 ;;
    *) echo "Opción desconocida: $1"; exit 1 ;;
  esac
done

# ─── Verificar Gradle ────────────────────────────────────────────────────────
if [[ ! -x "$GRADLE_HOME/bin/gradle" ]]; then
  header "Descargando Gradle $GRADLE_VERSION"
  GRADLE_ZIP="/tmp/gradle-${GRADLE_VERSION}-bin.zip"
  curl -fsSL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
    -o "$GRADLE_ZIP"
  unzip -q "$GRADLE_ZIP" -d /tmp
  rm -f "$GRADLE_ZIP"
  info "Gradle extraído en $GRADLE_HOME"
fi

# ─── Build de fat JARs ──────────────────────────────────────────────────────
header "Compilando y empaquetando (bootJar)"

MODULES=""
[[ "$BUILD_GATEWAY"  == true ]] && MODULES="$MODULES :hub-gateway:bootJar"
[[ "$BUILD_MS_BASE"  == true ]] && MODULES="$MODULES :hub-ms-base:bootJar"
[[ "$BUILD_MS_AUTH"  == true ]] && MODULES="$MODULES :hub-ms-auth:bootJar"

docker run --rm \
  -v "${REPO_ROOT}:/workspace" \
  -v "${GRADLE_HOME}:/gradle" \
  -v "/root/.gradle:/root/.gradle" \
  -w /workspace \
  -e GRADLE_USER_HOME=/root/.gradle \
  eclipse-temurin:25-jdk \
  /gradle/bin/gradle $MODULES --no-daemon
info "JARs generados."

# ─── docker build ────────────────────────────────────────────────────────────
build_image() {
  local jar_path="$1"
  local image_name="$2"
  local tmpdir
  tmpdir=$(mktemp -d)
  cp "$jar_path" "$tmpdir/app.jar"
  cp "$DOCKERFILE" "$tmpdir/Dockerfile"
  docker build \
    -t "${REGISTRY}/${image_name}:${TAG}" \
    -t "${REGISTRY}/${image_name}:latest" \
    "$tmpdir"
  rm -rf "$tmpdir"
  info "Imagen: ${REGISTRY}/${image_name}:${TAG}"
}

header "Construyendo imágenes Docker"

[[ "$BUILD_GATEWAY" == true ]] && \
  build_image "$REPO_ROOT/hub-gateway/build/libs/hub-gateway-${TAG}.jar" "hub-gateway"

[[ "$BUILD_MS_BASE" == true ]] && \
  build_image "$REPO_ROOT/hub-ms-base/build/libs/hub-ms-base-${TAG}.jar" "hub-ms-base"

[[ "$BUILD_MS_AUTH" == true ]] && \
  build_image "$REPO_ROOT/hub-ms-auth/build/libs/hub-ms-auth-${TAG}.jar" "hub-ms-auth"

header "Imágenes disponibles"
docker images | grep "${REGISTRY}"
