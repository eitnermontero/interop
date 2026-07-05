# Guía: agregar y exponer una API en el hub

Cómo publicar una API nueva para partners **sin escribir código** (ADR-0006/0007)
y que aparezca automáticamente en el **Swagger unificado** del gateway como
referencia técnica.

## Qué es "una API" para el hub

Un **producto** con: un contrato de forma (campos, tipos, requeridos), un
destino (el backend interno que la resuelve) y un scope OAuth2 que autoriza a
los partners suscritos. El motor genérico (dispatcher, validación, hash-chain,
auditoría, outbox, `ApiResponse`) ya existe y es el mismo para todas.

## Paso 1 — Declarar el bloque YAML

Editar el YAML del plano de control (en staging/prod vive en **Consul KV**; el
archivo fuente versionado es `deploy/staging/consul-config/base-service-application.yml`):

```yaml
hub:
  connectors:                        # solo si el destino es nuevo
    backend-mi-institucion:
      base-url: https://backend.institucion.gob.bo
      timeout-ms: 8000

  apis:
    denuncia-v1:                     # clave lógica (única)
      product: DENUNCIA              # código canónico — define la URL
      version: v1
      method: POST                   # POST | PATCH (PATCH requiere resource-id-field)
      connector: backend-mi-institucion
      target-path: /denuncias        # path en el destino; admite {campo} del payload
      required-scope: https://api.sintesis.com.bo/denuncia
      fields:                        # el contrato de validación (tipos: STRING,
        - { name: codigo,  type: STRING,  required: true, max-length: 50 }   # INTEGER, BOOLEAN,
        - { name: detalle, type: STRING,  required: false }                  # DATETIME, ARRAY)
        - { name: fecha,   type: DATETIME, required: false, format: iso8601 }
```

Reglas:
- Declarar **exactamente uno** de `connector` (HTTP genérico) o `adapter-bean`
  (bean `InboundPort` custom, solo para destinos no HTTP/JSON — eso sí es código).
- Para edición: producto con sufijo `_EDITAR` + `resource-id-field` — el
  dispatcher inyecta el `{id}` del path en ese campo del payload.
- Config inválida (conector inexistente, campo sin tipo…) = **el servicio no
  arranca** (fail-fast, deliberado).

## Paso 2 — Publicar la configuración

```bash
# La clave es config/base-service/<spring.application.name>.yml
curl -X PUT --data-binary @deploy/staging/consul-config/base-service-application.yml \
     http://<consul>:8500/v1/kv/config/base-service/hub-ms-base.yml
docker restart hub-staging-base-service     # segundos; sin rebuild de imagen
```

Verificar en el log de arranque: `Contratos inbound registrados: [..., DENUNCIA/v1]`.

## Paso 3 — Autorizar partners (Keycloak, realm `hub-partner`)

```bash
# Crear el scope del producto (CSV + sync, idempotente)
echo "https://api.sintesis.com.bo/denuncia,Crear denuncias" \
  >> deploy/scripts/keycloak-seed/partner/client-scopes.csv
# Habilitar el scope en los clients de los partners suscritos (columna scopes de clients.csv)
deploy/scripts/keycloak-sync-partner.sh
```

Scope habilitado en el client = suscripción activa (lo verifica el gateway en
cada request).

## Paso 4 — Verificar

```bash
TOKEN=$(curl -s -X POST http://<hub>/oauth2/token -d grant_type=client_credentials \
  -d client_id=<partner> -d client_secret=<secret> \
  -d scope=https://api.sintesis.com.bo/denuncia | jq -r .access_token)

curl -X POST http://<hub>/partner/v1/inbound/DENUNCIA/v1 \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-Idempotency-Key: $(uuidgen)" -d '{"codigo":"D-001"}'
```

Todo request queda auditado (`hub_audit_log`: hash + cadena por partner).

## El Swagger unificado (referencia técnica)

El OpenAPI de cada producto se **genera automáticamente** desde los contratos
cargados (no se escribe a mano): al registrar la API, sus paths, schemas
(campos/tipos/requeridos), headers (`X-Idempotency-Key`, `X-Correlation-ID`) y
el sobre `ApiResponse` aparecen en:

- `http://<hub>/v3/api-docs/base-service` — spec JSON agregada por el gateway.
- Swagger UI del gateway — selector con todos los servicios del hub.

Ese spec ES la referencia técnica para partners: siempre refleja lo que está
configurado, sin deriva documentación↔runtime.

## Checklist de alta

- [ ] Bloque en `hub.apis` (+ conector si es destino nuevo) publicado en Consul KV
- [ ] `docker restart` del base-service y contrato visible en el log
- [ ] Scope creado y habilitado a los partners suscritos
- [ ] Prueba con token real: 201/200 + violación de contrato → 400
- [ ] API visible en `/v3/api-docs/base-service`
- [ ] `hub_audit_log` registra la transacción
