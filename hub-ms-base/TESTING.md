# hub-ms-base — Guia de Testing

## Tests automatizados

Los tests estan en `src/test/java/bo/com/sintesis/hub/base/`:

| Clase | Tipo | Descripcion |
|---|---|---|
| `hub/CasoInboundIT` | Integracion (Testcontainers) | Flujo completo inbound CASO_PENAL |
| `hub/inbound/contract/ContractValidatorTest` | Unitario | Validacion de contratos por producto |
| `hub/inbound/stub/StubInboundAdapterTest` | Unitario | Adaptador stub para CASO_PENAL |
| `interop/outbound/efxrate/EfxRateAdapterTest` | Unitario | Adaptador EfxRate con mocks |
| `interop/outbound/efxrate/EfxRateClientIT` | Integracion (WireMock) | Cliente HTTP EfxRate |

Ejecutar:

```bash
./gradlew :hub-ms-base:test
```

---

## Testing manual (perfil local)

### 1. Levantar el stack

```bash
deploy/scripts/tools.sh --up
./gradlew :hub-ms-base:bootRun --args='--spring.profiles.active=local'
```

### 2. Crear un caso penal (inbound)

```bash
curl -X POST http://localhost:8081/api/inbound/CASO_PENAL/v1 \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: test-$(date +%s)" \
  -H "X-Partner-Id: POL-001" \
  -d '{
    "nuc": "NUC-2026-001",
    "descripcion": "Homicidio tentativa",
    "fechaHecho": "2026-07-01"
  }' | jq '.'
```

Respuesta esperada: `200 OK` con el payload del backend interno.

### 3. Verificar idempotencia

Repetir el mismo request con el mismo `X-Idempotency-Key`: debe devolver `409 Conflict`.

### 4. Health check

```bash
curl http://localhost:8081/actuator/health | jq '.'
```

---

## Variables de entorno relevantes (perfil local)

| Variable | Default | Descripcion |
|---|---|---|
| `LOCAL_DEV_API_KEY` | `dev-token-123456` | API key fallback para EfxRateClient |
| `KEYCLOAK_HOST` | `127.0.0.1` | Host de Keycloak |
| `KEYCLOAK_PORT` | `8180` | Puerto de Keycloak |
| `REDIS_HOST` | `127.0.0.1` | Host de Redis |
| `REDIS_PORT` | `6379` | Puerto de Redis |
