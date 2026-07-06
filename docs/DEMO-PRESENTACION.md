# Demo / presentación del hub — implementación desde cero en un servidor

Guión probado (2026-07-05): el entorno completo se destruyó y recreó desde cero
con `bootstrap.sh` en ~3 minutos, smoke test E2E incluido.

## Parte A — En la máquina de build (tiene el repo)

```bash
cd hub-interop

# 1. Construir imágenes (no requiere registry)
./gradlew :hub-gateway:jibDockerBuild :hub-ms-base:jibDockerBuild

# 2. Empaquetar imágenes (con tools para no depender del internet del servidor)
deploy/scripts/export-images.sh --with-tools

# 3. Empaquetar el deploy (sin código fuente)
tar --exclude='deploy/dist' -czf /tmp/hub-deploy.tar.gz deploy/

# 4. Enviar al servidor
scp deploy/dist/hub-images-*.tar.gz /tmp/hub-deploy.tar.gz root@<servidor>:/srv/projects/
```

## Parte B — En el servidor (desde cero)

```bash
# 0. Prerequisitos (solo si faltan): docker + python3 + openssl
curl -fsSL https://get.docker.com | sh && systemctl enable --now docker
apt-get install -y python3 openssl

# 1. Desempaquetar
mkdir -p /srv/projects/hub && cd /srv/projects/hub
tar -xzf /srv/projects/hub-deploy.tar.gz
docker load -i /srv/projects/hub-images-*.tar.gz

# 2. TODO DE CERO EN UN COMANDO (red, tools, realms, Vault, PKI, config, stack, smoke E2E)
SERVER_CN=$(hostname) SERVER_IP=<IP-del-servidor> deploy/scripts/bootstrap.sh
```

Al final imprime el resumen "HUB OPERATIVO" con todas las URLs. Puertos: solo el
**8088** (gateway) queda expuesto a la red; tools y DB bindean a 127.0.0.1.

## Parte C — Guión de la demo (los 6 momentos del producto)

```bash
SECRET=$(grep felcn-api deploy/scripts/keycloak-seed/partner/clients.csv | cut -d, -f4)
HUB=http://<IP-del-servidor>:8088
```

**1. "El partner se autentica"** — token OAuth2 vía el hub (nunca ve Keycloak):
```bash
TOKEN=$(curl -s -X POST $HUB/oauth2/token -d grant_type=client_credentials \
  -d client_id=felcn-api -d client_secret=$SECRET \
  -d scope=https://api.sintesis.com.bo/caso.penal | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')
```

**2. "Envía un caso penal"** — validado, reenviado al backend, auditado:
```bash
curl -s -X POST $HUB/partner/v1/inbound/CASO_PENAL/v1 \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-Idempotency-Key: demo-$(date +%s)" \
  -d '{"cud":"DEMO-001","id_externo_caso":1,"id_tipo_denuncia":3,"id_oficina":12,"id_estado":1,"id_etapa":1}'
```
*Mostrar: `success:true`, la respuesta del backend y el `correlationId`.*

**3. "El hub protege el contrato"** — payload inválido:
```bash
curl -s -X POST $HUB/partner/v1/inbound/CASO_PENAL/v1 \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"cud":"X"}'
```
*Mostrar: 400 con la lista exacta de violaciones. Y sin token → 401 con el mismo sobre.*

**4. "Toda transacción deja evidencia"** — auditoría con cadena de hashes:
```bash
PGPASSWORD=postgres psql -U postgres -h 127.0.0.1 -p 5433 -d hub_base -c \
 "SELECT partner_id, product, http_status, left(chain_hash,12) cadena, correlation_id FROM hub_audit_log ORDER BY ts DESC LIMIT 5;"
```
*Mostrar: el `correlation_id` es el mismo que recibió el partner (trazabilidad 1:1).*

**5. EL MOMENTO CLAVE — "Integrar una API nueva no es un proyecto, es configuración":**
```bash
# Editar deploy/staging/consul-config/base-service-application.yml y agregar
# un producto nuevo (bloque de ~10 líneas — ver docs/AGREGAR-API.md). Luego:
deploy/scripts/hub-api.sh publish
deploy/scripts/hub-api.sh list
```
*Mostrar: en <1 minuto la API existe, valida su contrato y ya aparece en el Swagger.*

**6. "La referencia técnica se escribe sola"**:
```bash
# En el navegador:
$HUB/v3/api-docs/base-service
```

## Si algo falla en vivo

- `deploy/scripts/hub-api.sh status` — salud de los contenedores.
- `docker logs hub-staging-base-service | tail -50` / `hub-staging-gateway`.
- Reset nuclear (3 min): `docker compose -f deploy/staging/docker-compose.yml --env-file deploy/staging/.env down -v && deploy/scripts/tools.sh --down -v && docker network rm hub-shared && deploy/scripts/bootstrap.sh`
- Troubleshooting detallado: `docs/STAGING.md`.
