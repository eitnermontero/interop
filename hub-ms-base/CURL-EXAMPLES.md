# hub-ms-base — Ejemplos de API con curl

Ejemplos para probar las APIs del hub de interoperabilidad (ms-base, puerto 8081).

## Prerequisitos

- Aplicación corriendo en `http://localhost:8081` (perfil `local`)
- `jq` instalado para formatear JSON (opcional)
- Seguridad deshabilitada en modo local (ver `SecurityConfiguration.java`)

---

## Health

```bash
curl http://localhost:8081/actuator/health | jq '.'
```

---

## Inbound — casos penales

### Crear / despachar un caso (POST)

```bash
curl -X POST http://localhost:8081/api/inbound/CASO_PENAL/v1 \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: $(uuidgen)" \
  -H "X-Partner-Id: POL-001" \
  -d '{
    "nuc": "NUC-2026-001",
    "descripcion": "Caso de prueba",
    "fechaHecho": "2026-07-01"
  }' | jq '.'
```

### Editar un caso (PATCH)

```bash
CASO_ID="<id-devuelto-por-el-POST>"

curl -X PATCH "http://localhost:8081/api/inbound/CASO_PENAL/v1/${CASO_ID}" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: $(uuidgen)" \
  -H "X-Partner-Id: POL-001" \
  -d '{
    "descripcion": "Descripcion actualizada"
  }' | jq '.'
```

---

## Autenticacion (produccion)

En produccion, todas las llamadas requieren token JWT del partner:

```bash
# Token partner (via gateway, realm hub-partner)
PARTNER_TOKEN=$(curl -s -X POST http://127.0.0.1:8080/oauth2/token \
  -d grant_type=client_credentials \
  -d client_id=<partner-client-id> \
  -d client_secret=<partner-client-secret> \
  -d scope=https://api.sintesis.com.bo/caso.penal | jq -r .access_token)

curl -X POST http://localhost:8081/api/inbound/CASO_PENAL/v1 \
  -H "Authorization: Bearer $PARTNER_TOKEN" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: $(uuidgen)" \
  -d '{ ... }' | jq '.'
```

---

## Swagger UI

```
http://localhost:8081/swagger-ui.html
```

API Docs: `http://localhost:8081/v3/api-docs`
