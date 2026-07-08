#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# test-catalogo-caso-penal.sh — Smoke test E2E de las 18 APIs de escritura
# del catálogo MP→POL/FELCN (Ficha Técnica §3) contra el backend real de
# Fiscalía, todo vía el gateway del hub (token partner real, auditado).
#
# Requiere: el YAML deploy/staging/consul-config/base-service-application.yml
# ya publicado (deploy/scripts/hub-api.sh publish) y el backend real
# (http://172.16.76.20:3333) arriba.
#
# Encadena los ids reales devueltos por cada creación (pol_caso_id,
# pol_caso_persona_id, etc.) para probar también los PATCH — igual que el
# flujo real de un partner. Se detiene en el primer error (set -e). Como no
# se conoce de antemano la forma exacta de cada respuesta del backend, para
# los ids anidados en arreglos (delito/abogado/fiscal) el script muestra la
# respuesta cruda y pide pegar el id — para los ids planos (caso/agenda) los
# extrae solo.
#
# Uso:
#   deploy/scripts/test-catalogo-caso-penal.sh
#   GATEWAY_URL=http://172.16.76.20:8088 PARTNER=felcn-api \
#     deploy/scripts/test-catalogo-caso-penal.sh
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/../.." && pwd)"

GATEWAY_URL="${GATEWAY_URL:-http://127.0.0.1:8088}"
PARTNER="${PARTNER:-felcn-api}"
SCOPE="${SCOPE:-https://api.sintesis.com.bo/caso.penal}"

GREEN='\033[0;32m'; RED='\033[0;31m'; CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'
paso()  { echo -e "\n${BOLD}${CYAN}── [$1/18] $2 ──${NC}"; }
ok()    { echo -e "${GREEN}[✓]${NC} $1"; }
fallo() { echo -e "${RED}[✗]${NC} $1"; exit 1; }

SECRET=$(grep "^$PARTNER," "$REPO/deploy/scripts/keycloak-seed/partner/clients.csv" | cut -d, -f4)
[ -n "$SECRET" ] || fallo "no se encontró el secret de $PARTNER en clients.csv"

TOKEN=$(curl -sf -X POST "$GATEWAY_URL/oauth2/token" -d grant_type=client_credentials \
  -d "client_id=$PARTNER" -d "client_secret=$SECRET" -d "scope=$SCOPE" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin).get("access_token",""))')
[ -n "$TOKEN" ] || fallo "no se obtuvo token de $PARTNER"
ok "Token de '$PARTNER' emitido"

AUTH=(-H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json')
llamar() { # llamar <METODO> <path> <json>
  local metodo="$1" path="$2" body="$3"
  curl -s -o /tmp/tcp-resp.json -w '%{http_code}' -m 25 -X "$metodo" \
    "$GATEWAY_URL/partner/v1/inbound/$path" "${AUTH[@]}" \
    -H "X-Idempotency-Key: tcp-$(date +%s%N)-$RANDOM" -d "$body"
}
dato() { python3 -c "import json; print(json.load(open('/tmp/tcp-resp.json'))['data'].get('$1',''))" 2>/dev/null || true; }
exigir_2xx() { # exigir_2xx <desc> <http_code>
  [[ "$2" == 2* ]] || fallo "$1 devolvió $2: $(cat /tmp/tcp-resp.json)"
  ok "$1 → $2"
}
pedir_id() { # pedir_id <nombre-campo> -> lee de stdin, muestra la respuesta cruda antes
  cat /tmp/tcp-resp.json; echo ""
  read -r -p "    $1 = " valor
  [ -n "$valor" ] || fallo "$1 vacío"
  echo "$valor"
}

paso 1 "CASO_PENAL/v1 — crear caso"
HTTP=$(llamar POST "CASO_PENAL/v1" '{
  "cud":"TCP-001","mp_caso_id":900001,"tipo_denuncia_id":1,
  "creacion_fecha_hora":"2026-07-07T10:00:00Z","oficina_comun_id":1,
  "caso_estado_id":1,"caso_etapa_id":1}')
exigir_2xx "Crear caso" "$HTTP"
POL_CASO_ID=$(dato pol_caso_id)
[ -n "$POL_CASO_ID" ] || fallo "no se obtuvo pol_caso_id: $(cat /tmp/tcp-resp.json)"
echo "    pol_caso_id=$POL_CASO_ID"

paso 2 "CASO_PENAL_EDITAR/v1 — editar caso"
HTTP=$(llamar PATCH "CASO_PENAL_EDITAR/v1/$POL_CASO_ID" '{"tipo_denuncia_id":2,"hecho_zona":"Zona Sur"}')
exigir_2xx "Editar caso" "$HTTP"

paso 3 "CASO_PENAL_DELITO/v1 — registrar delito"
HTTP=$(llamar POST "CASO_PENAL_DELITO/v1" "{\"pol_caso_id\":$POL_CASO_ID,\"delitos\":[{\"mp_caso_delito_id\":1,\"delito_id\":10,\"es_principal\":true,\"es_tentativo\":false}]}")
exigir_2xx "Registrar delito" "$HTTP"
POL_CASO_DELITO_ID=$(pedir_id pol_caso_delito_id)

paso 4 "CASO_PENAL_DELITO_EDITAR/v1 — dar de baja delito"
HTTP=$(llamar PATCH "CASO_PENAL_DELITO_EDITAR/v1/$POL_CASO_DELITO_ID" '{"es_principal":true,"es_tentativo":false,"estado":1}')
exigir_2xx "Editar delito" "$HTTP"

paso 5 "CASO_PENAL_SUJETO/v1 — registrar sujeto"
HTTP=$(llamar POST "CASO_PENAL_SUJETO/v1" "{\"pol_caso_id\":$POL_CASO_ID,\"sujetos\":[{\"mp_caso_persona_id\":1,\"tipo_sujeto_id\":[1],\"persona_natural\":{\"tipo_identidad_id\":1,\"tipo_documento_id\":1,\"numero_documento\":\"1234567\",\"nombres\":\"Juan\",\"primer_apellido\":\"Perez\"}}]}")
exigir_2xx "Registrar sujeto" "$HTTP"
POL_CASO_PERSONA_ID=$(pedir_id pol_caso_persona_id)

paso 6 "CASO_PENAL_SUJETO_EDITAR/v1 — editar sujeto"
HTTP=$(llamar PATCH "CASO_PENAL_SUJETO_EDITAR/v1/$POL_CASO_PERSONA_ID" '{"tipo_sujeto_id":[1,2]}')
exigir_2xx "Editar sujeto" "$HTTP"

paso 7 "CASO_PENAL_ABOGADO/v1 — registrar abogado"
HTTP=$(llamar POST "CASO_PENAL_ABOGADO/v1" "{\"pol_caso_persona_id\":$POL_CASO_PERSONA_ID,\"abogados\":[{\"mp_caso_persona_abogado_id\":1,\"codigo_rpa\":\"RPA-1\",\"ci\":\"7654321\",\"nombres\":\"Carlos\",\"fecha_nacimiento\":\"1985-03-20\"}]}")
exigir_2xx "Registrar abogado" "$HTTP"
POL_ABOGADO_ID=$(pedir_id pol_caso_persona_abogado_id)

paso 8 "CASO_PENAL_ABOGADO_EDITAR/v1 — dar de baja abogado"
HTTP=$(llamar PATCH "CASO_PENAL_ABOGADO_EDITAR/v1/$POL_ABOGADO_ID" '{"estado":0,"motivo_baja":"prueba"}')
exigir_2xx "Editar abogado" "$HTTP"

paso 9 "CASO_PENAL_SITUACION_JURIDICA/v1 — registrar situación jurídica"
HTTP=$(llamar POST "CASO_PENAL_SITUACION_JURIDICA/v1" "{\"pol_caso_persona_id\":$POL_CASO_PERSONA_ID,\"situaciones_juridicas\":[{\"mp_caso_persona_situacion_juridica_id\":1,\"situacion_juridica_id\":1,\"fecha_inicio\":\"2026-07-01T00:00:00Z\"}]}")
exigir_2xx "Registrar situación jurídica" "$HTTP"

paso 10 "CASO_PENAL_DOMICILIO/v1 — registrar domicilio"
HTTP=$(llamar POST "CASO_PENAL_DOMICILIO/v1" "{\"pol_caso_persona_id\":$POL_CASO_PERSONA_ID,\"mp_persona_domicilio_id\":1,\"pais_id\":1}")
exigir_2xx "Registrar domicilio" "$HTTP"

paso 11 "CASO_PENAL_FISCAL/v1 — registrar fiscal"
HTTP=$(llamar POST "CASO_PENAL_FISCAL/v1" "{\"pol_caso_id\":$POL_CASO_ID,\"fiscales\":[{\"mp_caso_funcionario_id\":1,\"tipo_responsable_id\":1,\"ci\":\"1112223\",\"nombres\":\"Maria\",\"fecha_nacimiento\":\"1980-01-10\"}]}")
exigir_2xx "Registrar fiscal" "$HTTP"
POL_FUNCIONARIO_ID=$(pedir_id pol_caso_funcionario_id)

paso 12 "CASO_PENAL_FISCAL_EDITAR/v1 — dar de baja fiscal"
HTTP=$(llamar PATCH "CASO_PENAL_FISCAL_EDITAR/v1/$POL_FUNCIONARIO_ID" '{"estado":0}')
exigir_2xx "Editar fiscal" "$HTTP"

paso 13 "CASO_PENAL_ACTIVIDAD/v1 — registrar actividad"
HTTP=$(llamar POST "CASO_PENAL_ACTIVIDAD/v1" "{\"pol_caso_id\":$POL_CASO_ID,\"actividades\":[{\"mp_caso_actividad_id\":1,\"actividad_id\":10,\"referencia\":\"Requerimiento 1/2026\",\"archivo_hash\":\"abc123\"}]}")
exigir_2xx "Registrar actividad" "$HTTP"

paso 14 "CASO_PENAL_RESERVA/v1 — reservar caso"
HTTP=$(llamar POST "CASO_PENAL_RESERVA/v1" "{\"estado\":1,\"tabla\":1,\"tabla_id\":$POL_CASO_ID}")
exigir_2xx "Reservar caso" "$HTTP"

paso 15 "CASO_PENAL_JUZGADO/v1 — asignar juzgado del caso"
HTTP=$(llamar POST "CASO_PENAL_JUZGADO/v1" "{\"pol_caso_id\":$POL_CASO_ID,\"juzgado_id\":5}")
exigir_2xx "Asignar juzgado del caso" "$HTTP"

paso 16 "CASO_PENAL_SUJETO_JUZGADO/v1 — asignar juzgado de sujetos"
HTTP=$(llamar POST "CASO_PENAL_SUJETO_JUZGADO/v1" "{\"pol_caso_persona_ids\":[$POL_CASO_PERSONA_ID],\"juzgado_id\":5}")
exigir_2xx "Asignar juzgado de sujetos" "$HTTP"

paso 17 "CASO_PENAL_AGENDA/v1 — registrar audiencia"
HTTP=$(llamar POST "CASO_PENAL_AGENDA/v1" "{\"pol_caso_id\":$POL_CASO_ID,\"oj_audiencia_id\":100,\"oj_audiencia_detalle_id\":200,\"tipo_audiencia_id\":1,\"tipo_actividad_id\":1,\"juzgado_id\":5,\"sala_audiencia_id\":1,\"estado_audiencia_id\":1,\"fecha_hora_inicio\":\"2026-08-01T09:00:00Z\",\"fecha_hora_fin\":\"2026-08-01T11:00:00Z\",\"descripcion\":\"Audiencia de prueba\"}")
exigir_2xx "Registrar audiencia" "$HTTP"
POL_AGENDA_ID=$(dato pol_agenda_id)
[ -n "$POL_AGENDA_ID" ] || fallo "no se obtuvo pol_agenda_id: $(cat /tmp/tcp-resp.json)"
echo "    pol_agenda_id=$POL_AGENDA_ID"

paso 18 "CASO_PENAL_AGENDA_EDITAR/v1 — reprogramar audiencia"
HTTP=$(llamar PATCH "CASO_PENAL_AGENDA_EDITAR/v1/$POL_AGENDA_ID" '{"descripcion":"Audiencia reprogramada","estado":1}')
exigir_2xx "Editar audiencia" "$HTTP"

echo ""
echo -e "${BOLD}${GREEN}════════ 18/18 productos probados OK ════════${NC}"
echo "  Caso de prueba: pol_caso_id=$POL_CASO_ID"
echo "  Revisar auditoría: psql -h 127.0.0.1 -p 5433 -d hub_base -c \"select product,http_status,created_at from hub_audit_log order by created_at desc limit 20;\""
rm -f /tmp/tcp-resp.json
