# Staging — levantar el hub desde cero

Guía verificada end-to-end (2026-07-05). Levanta el entorno de staging completo:
tools compartidas + Postgres propio (DBs desde cero) + gateway + base-service
dockerizados, con las APIs configuradas por el admin en Consul KV (ADR-0007) y
un partner real dado de alta (ver `ONBOARDING-PARTNER.md`).

## Arquitectura del entorno

| Componente | Dónde corre | Puerto host |
|---|---|---|
| Gateway (staging) | contenedor `hub-staging-gateway` | **8088** |
| base-service (staging) | contenedor `hub-staging-base-service` | 8089 (solo localhost) |
| PostgreSQL staging | contenedor `hub-staging-postgres` | 5433 (solo localhost) |
| Keycloak / Consul / Vault / Redis | stack `hub-tools` (compartido) | 8180 / 8500 / 8200 / 6379 |

Los contenedores de staging alcanzan las tools **por nombre de contenedor** vía
la red `hub-shared` (`hub-keycloak:9080`, `hub-consul:8500`, `hub-vault:8200`,
`hub-redis:6379`). Las tools bindean sus puertos a `127.0.0.1` del host, así que
`host.docker.internal` NO sirve.

## 1. Prerequisitos (una sola vez)

```bash
# Red compartida
docker network create hub-shared

# Tools
deploy/scripts/tools.sh --up

# Realms Keycloak (hub-admin y hub-partner, con el scope caso.penal)
deploy/scripts/keycloak-sync-admin.sh
deploy/scripts/keycloak-sync-partner.sh

# Seeds de Vault (secretos de DB/Redis/Keycloak por namespace)
TOOLS_HOST=127.0.0.1 DB_NAME=hub_auth deploy/scripts/vault-seed.sh --ns hub-auth --kc-realm hub-admin
TOOLS_HOST=127.0.0.1 DB_NAME=hub_base deploy/scripts/vault-seed.sh --ns hub-base --kc-realm hub-admin

# PKI de Vault (CA raíz + intermedia + roles) — ver ONBOARDING-PARTNER.md
deploy/scripts/vault-pki.sh init
deploy/scripts/vault-pki.sh server localhost 127.0.0.1
```

## 2. Construir imágenes

No se necesita registry: `jibDockerBuild` construye directo en el daemon Docker
local. (El registry `cr.sintesis.com.bo/hub-dev` solo hace falta cuando el host
que despliega es distinto al que construye — en ese caso `./gradlew jib` +
`docker compose pull`.)

```bash
./gradlew :hub-gateway:jibDockerBuild :hub-ms-base:jibDockerBuild
```

## 3. Directorios de logs (contenedor corre como uid 1000)

```bash
bash deploy/scripts/init-logs.sh staging   # o: mkdir -p ~/logs/hub-staging/{gateway,base-service}
```

> Si Docker crea los directorios (quedan como root) el contenedor entra en
> crash-loop de logback. Fix: `docker run --rm -v ~/logs/hub-staging:/f alpine chown -R 1000:1000 /f`

## 4. Publicar la configuración de APIs (el paso del ADMIN)

Las APIs NO viven en la imagen: el admin las publica en Consul KV. La clave que
lee el servicio es `<prefijo>/<spring.application.name>.yml` =
**`config/base-service/hub-ms-base.yml`**:

```bash
curl -X PUT --data-binary @deploy/staging/consul-config/base-service-application.yml \
     http://127.0.0.1:8500/v1/kv/config/base-service/hub-ms-base.yml
```

El archivo declara los conectores y destinos (hoy: `backend-felcn` →
JSONPlaceholder simulando el backend de la institución). Cambiar un destino =
editar la KV + `docker restart hub-staging-base-service`. **Nunca rebuild.**

## 5. Levantar el stack

```bash
docker compose -f deploy/staging/docker-compose.yml --env-file deploy/staging/.env up -d
docker ps --filter name=hub-staging    # esperar (healthy)
```

Al primer arranque: Postgres crea `hub_base`/`hub_auth` (init-db.sql) y
Liquibase aplica el esquema completo (hub_audit_log particionada + outbox).

## 6. Verificación E2E

```bash
# Token del partner (vía proxy del gateway — el partner nunca ve Keycloak)
SECRET=<client_secret del partner>
TOKEN=$(curl -s -X POST http://127.0.0.1:8088/oauth2/token \
  -d grant_type=client_credentials -d client_id=felcn-api \
  -d client_secret=$SECRET \
  -d scope=https://api.sintesis.com.bo/caso.penal | jq -r .access_token)

# Crear caso → 201 con la respuesta del backend
curl -X POST http://127.0.0.1:8088/partner/v1/inbound/CASO_PENAL/v1 \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-Idempotency-Key: $(uuidgen)" \
  -d '{"cud":"X-001","id_externo_caso":1,"id_tipo_denuncia":3,"id_oficina":12,"id_estado":1,"id_etapa":1}'

# Editar caso → 200
curl -X PATCH http://127.0.0.1:8088/partner/v1/inbound/CASO_PENAL/v1/88 \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-Idempotency-Key: $(uuidgen)" \
  -d '{"id_tipo_denuncia":4,"denominacion_caso":"editado"}'

# Auditoría (evidencia con cadena de hashes)
PGPASSWORD=postgres psql -U postgres -h 127.0.0.1 -p 5433 -d hub_base \
  -c "SELECT direction, partner_id, product, http_status, chain_hash FROM hub_audit_log ORDER BY ts DESC LIMIT 5;"
```

## Troubleshooting (problemas reales encontrados y sus fixes)

| Síntoma | Causa | Fix |
|---|---|---|
| base-service/gateway en crash-loop, logback `FileNotFoundException` | Dir de logs creado por Docker como root; contenedor corre como uid 1000 | §3 (init-logs / chown) |
| 502 en `POST /oauth2/token` | `keycloak.partner-client.auth-server-url` viene de Vault con `127.0.0.1` (inalcanzable desde contenedor) | Override por env `KEYCLOAK_PARTNER_CLIENT_AUTH_SERVER_URL` (ya cableado en `001-gateway.yml`) |
| Responde el **stub** en vez del backend | Otra instancia (p.ej. bootRun local) registrada en Consul como `hubbaseservice` — el LB balancea entre ambas | Bajar la instancia local o usar `TENANT_ID` para aislar nombres |
| La config de Consul KV no se aplica | Clave incorrecta: con `format: files` el servicio lee `config/base-service/hub-ms-base.yml` (nombre de la app), no `application.yml` | Usar la clave correcta (§4) |
| PATCH al backend → 503 con backend sano | `SimpleClientHttpRequestFactory` no soporta PATCH | Corregido: `HttpForwardingAdapter` usa `JdkClientHttpRequestFactory` |

## Gaps conocidos (pendientes para prod)

1. **mTLS de transporte apagado** en staging (`HUB_MTLS_TEST_MODE=true`): la
   PKI de Vault ya emite todo (`vault-pki.sh`); habilitación del listener en
   `ONBOARDING-PARTNER.md` §6.
2. Vault del stack tools en **dev mode** (volátil): la CA se pierde si se
   recrea el contenedor — re-correr `vault-pki.sh init/server/partner`. Prod
   exige Vault en modo real (raft).
3. Adaptador genérico sin resilience4j ni auth `API_KEY` Vault (ADR-0007 fase 2).
4. Firma Vault Transit de la cadena de auditoría inactiva (`NoOpAuditSigner`).

> Resueltos 2026-07-05: errores del gateway ya son `ApiResponse`; el filtro de
> correlación propaga `X-Correlation-ID` a la auditoría; `partner_id` usa `azp`.
