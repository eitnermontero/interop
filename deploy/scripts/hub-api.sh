#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# hub-api.sh — CLI del OPERADOR para configurar las APIs del hub (ADR-0007)
#
# El operador edita el YAML del plano de control y este script se encarga de
# validar, publicar en Consul KV, reiniciar el servicio y verificar.
#
# Uso:
#   deploy/scripts/hub-api.sh validate    # valida el YAML local (sin publicar)
#   deploy/scripts/hub-api.sh diff        # compara YAML local vs lo publicado
#   deploy/scripts/hub-api.sh publish     # valida + publica + reinicia + verifica
#   deploy/scripts/hub-api.sh list        # APIs activas (desde el servicio)
#   deploy/scripts/hub-api.sh status      # salud del stack
#
# Variables (defaults = staging local; override por env para otro servidor):
#   HUB_API_FILE      YAML local del plano de control
#   HUB_CONSUL_URL    URL de Consul
#   HUB_KV_KEY        clave KV (config/base-service/<spring.application.name>.yml)
#   HUB_CONTAINER     contenedor del base-service a reiniciar
#   HUB_GATEWAY_URL   URL del gateway (para list vía swagger)
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

FILE="${HUB_API_FILE:-$REPO_ROOT/deploy/staging/consul-config/base-service-application.yml}"
CONSUL="${HUB_CONSUL_URL:-http://127.0.0.1:8500}"
KV_KEY="${HUB_KV_KEY:-config/base-service/hub-ms-base.yml}"
CONTAINER="${HUB_CONTAINER:-hub-staging-base-service}"
GATEWAY="${HUB_GATEWAY_URL:-http://127.0.0.1:8088}"

GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'
ok()   { echo -e "${GREEN}[✓]${NC} $1"; }
err()  { echo -e "${RED}[✗]${NC} $1"; }
warn() { echo -e "${YELLOW}[!]${NC} $1"; }
info() { echo -e "${CYAN}[i]${NC} $1"; }

validar() {
  [ -f "$FILE" ] || { err "No existe el archivo: $FILE"; exit 1; }
  python3 - "$FILE" <<'PYEOF'
import sys, yaml

TIPOS = {"STRING", "INTEGER", "BOOLEAN", "DATETIME", "ARRAY"}
errores = []

with open(sys.argv[1]) as f:
    doc = yaml.safe_load(f)

hub = (doc or {}).get("hub") or {}
connectors = hub.get("connectors") or {}
apis = hub.get("apis") or {}

if not apis:
    errores.append("hub.apis está vacío — no hay APIs declaradas")

overrides = []
for nombre, api in apis.items():
    api = api or {}
    ctx = f"hub.apis.{nombre}"
    # Bloque SIN product = override parcial de una API declarada en la imagen
    # (Spring mergea por clave): solo se valida lo que el override declara.
    es_override = not api.get("product") and not api.get("fields")
    if es_override:
        overrides.append(nombre)
    else:
        if not api.get("product"):
            errores.append(f"{ctx}: falta 'product'")
        if not api.get("version"):
            errores.append(f"{ctx}: falta 'version'")
    con = api.get("connector") or ""
    bean = api.get("adapter-bean")
    # adapter-bean: "" es un override válido (anula el default de la imagen)
    tiene_con = bool(con)
    tiene_bean = bool(bean)
    if tiene_con and tiene_bean:
        errores.append(f"{ctx}: 'connector' y 'adapter-bean' son excluyentes")
    if tiene_con:
        if con not in connectors:
            errores.append(f"{ctx}: el conector '{con}' no existe en hub.connectors (definidos: {list(connectors)})")
        if not api.get("target-path"):
            errores.append(f"{ctx}: falta 'target-path' (obligatorio con connector)")
    if not es_override and api.get("method", "POST") == "PATCH" and not api.get("resource-id-field"):
        errores.append(f"{ctx}: method PATCH requiere 'resource-id-field'")
    for i, f_ in enumerate(api.get("fields") or []):
        fctx = f"{ctx}.fields[{i}]"
        if not f_.get("name"):
            errores.append(f"{fctx}: falta 'name'")
        if f_.get("type") not in TIPOS:
            errores.append(f"{fctx}: type '{f_.get('type')}' inválido (usar {sorted(TIPOS)})")

for nombre, c in connectors.items():
    if not (c or {}).get("base-url"):
        errores.append(f"hub.connectors.{nombre}: falta 'base-url'")

if errores:
    print("ERRORES:")
    for e in errores:
        print(f"  - {e}")
    sys.exit(1)

print(f"OK — {len(apis)} bloque(s) ({len(overrides)} override(s) de destino), {len(connectors)} conector(es):")
for nombre, api in apis.items():
    destino = api.get("connector") or api.get("adapter-bean") or "(hereda de la imagen)"
    etiqueta = f"{api.get('product')}/{api.get('version')}" if api.get("product") else f"{nombre} (override)"
    print(f"  {etiqueta}  [{api.get('method','POST') if api.get('product') else '—'}]  → {destino}")
PYEOF
}

case "${1:-help}" in
  validate)
    info "Validando $FILE"
    validar && ok "Configuración válida"
    ;;

  diff)
    info "Local:  $FILE"
    info "Consul: $CONSUL/v1/kv/$KV_KEY"
    REMOTO=$(mktemp)
    curl -sf "$CONSUL/v1/kv/$KV_KEY?raw" -o "$REMOTO" || { warn "La clave no existe aún en Consul (primera publicación)"; rm -f "$REMOTO"; exit 0; }
    if diff -u "$REMOTO" "$FILE"; then ok "Sin diferencias — lo publicado es igual al local"; else warn "Hay diferencias (arriba). 'publish' las aplicará."; fi
    rm -f "$REMOTO"
    ;;

  publish)
    info "1/4 Validando..."
    validar || { err "Configuración inválida — NO se publicó nada"; exit 1; }
    info "2/4 Publicando en Consul KV ($KV_KEY)..."
    curl -sf -X PUT --data-binary @"$FILE" "$CONSUL/v1/kv/$KV_KEY" >/dev/null && ok "Publicado"
    info "3/4 Reiniciando $CONTAINER..."
    docker restart "$CONTAINER" >/dev/null
    for i in $(seq 1 36); do
      estado=$(docker inspect -f '{{.State.Health.Status}}' "$CONTAINER" 2>/dev/null || echo "?")
      [ "$estado" = "healthy" ] && ok "Servicio healthy" && break
      [ "$estado" = "unhealthy" ] && err "Servicio UNHEALTHY — revisar: docker logs $CONTAINER" && exit 1
      sleep 5
    done
    info "4/4 Contratos registrados:"
    docker logs --since 3m "$CONTAINER" 2>&1 | grep -o 'Contratos inbound registrados: \[.*\]' | tail -1 || warn "no se encontró la línea en el log"
    echo ""
    ok "Publicación completa. Verificar en el Swagger: $GATEWAY/v3/api-docs/base-service"
    ;;

  list)
    info "APIs activas (Swagger agregado del gateway):"
    curl -sf "$GATEWAY/v3/api-docs/base-service" | python3 -c '
import sys, json
s = json.load(sys.stdin)
for p, ops in sorted(s.get("paths", {}).items()):
    if "inbound" in p:
        for m in ops:
            print(f"  {m.upper():6} {p}")' || err "No se pudo consultar el gateway ($GATEWAY)"
    ;;

  status)
    docker ps --filter name=hub- --format '  {{.Names}}\t{{.Status}}' | sort
    ;;

  *)
    sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
    ;;
esac
