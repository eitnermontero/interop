# 01 - Seguridad: Cadenas de seguridad, JWT y filtros del Gateway

## Arquitectura general

```
Cliente externo (partner)
    │
    │  POST /oauth2/token  (sin auth)
    │  POST /partner/v1/** (JWT hub-partner)
    ▼
hub-gateway :8080  ──── Spring Cloud Gateway
    │  Cadena 1 (Order=1) — partner
    │  Cadena 2 (Order=2) — admin
    │  Filtros globales: IpWhitelistFilter, RateLimitFilter
    │
    ├──── lb://hubbaseservice  ──▶  hub-ms-base :8081
    └──── lb://hubadminservice ──▶  hub-ms-auth :8083
```

**Keycloak** corre en el puerto `8180` (no confundir con el gateway en `8080`).

---

## Cadenas de seguridad (SecurityConfiguration.java)

### Cadena 1 — Partner (Order=1)

Gestiona el acceso de clientes M2M externos.

| Elemento | Valor |
|---|---|
| Rutas cubiertas | `/partner/v1/**`, `/oauth2/token` |
| `/oauth2/token` | `permitAll()` — proxy al token endpoint de Keycloak realm `hub-partner` |
| `/partner/v1/**` | Requiere JWT válido del realm `hub-partner` |
| Issuer JWT | `http://127.0.0.1:8180/realms/hub-partner` |
| Client Keycloak | `unilink-api` / secret `unilink-api-secret` |

### Cadena 2 — Admin (Order=2)

Gestiona el acceso interno y administrativo.

| Elemento | Valor |
|---|---|
| Rutas cubiertas | `/services/**`, `/management/**` |
| `/management/health` | `permitAll()` |
| Todo lo demás | Requiere JWT válido del realm `hub-admin` |
| Issuer JWT | `http://127.0.0.1:8180/realms/hub-admin` |
| Client Keycloak | `hubadminservice` / secret `hubadminservice-secret` |

### Rutas cubiertas por cada cadena

| Método | Ruta | Cadena | Autenticación |
|--------|------|--------|--------------|
| POST | `/oauth2/token` | 1 — partner | Sin auth (permitAll) |
| POST | `/partner/v1/qr/decode` | 1 — partner | JWT realm `hub-partner` |
| POST | `/partner/v1/qr/decode/file` | 1 — partner | JWT realm `hub-partner` |
| `*` | `/services/hubadminservice/**` | 2 — admin | JWT realm `hub-admin` |
| `*` | `/services/hubbaseservice/**` | 2 — admin | JWT realm `hub-admin` |
| GET | `/management/health` | 2 — admin | Sin auth (permitAll) |
| `*` | `/management/**` | 2 — admin | JWT realm `hub-admin` |

---

## Filtros globales

### IpWhitelistFilter (Order 2)

Verifica que la IP origen del request esté en la lista blanca del partner.

- **Clave Redis:** `whitelist:{partnerId}`
- **Comportamiento si Redis no está disponible:** fail-open (el request se permite)
- El `partnerId` se extrae del claim `azp` del JWT

Flujo:
```
1. Extraer partnerId del JWT (claim "azp")
2. Consultar Redis: whitelist:{partnerId}
3. Si la lista no está vacía y la IP del request no está en ella → 403
4. Si Redis no responde → continuar (fail-open)
```

Respuesta de error:
```json
{
    "type": "https://api.sintesis.com.bo/problems/ip-not-allowed",
    "title": "IP Not Allowed",
    "status": 403,
    "detail": "Request IP 203.0.113.99 is not in the whitelist for this partner",
    "errorCode": "ACCESS_DENIED",
    "timestamp": "2026-06-15T10:00:00Z"
}
```

### RateLimitFilter (Order 3)

Controla la cantidad de requests por partner en una ventana deslizante de 60 segundos.

- **Clave Redis:** `ratelimit:{partnerId}`
- **Algoritmo:** sliding window counter
- **Límite por defecto:** configurable en `ApplicationProperties`
- **Comportamiento si Redis no está disponible:** fail-open (el request se permite)

Respuesta de error:
```json
{
    "type": "https://api.sintesis.com.bo/problems/rate-limit-exceeded",
    "title": "Rate Limit Exceeded",
    "status": 429,
    "detail": "Rate limit exceeded for partner unilink-api",
    "errorCode": "RATE_LIMIT_EXCEEDED",
    "timestamp": "2026-06-15T10:00:00Z"
}
```

### GatewayExceptionHandler

Mapeo de excepciones a códigos HTTP:

| Excepción | HTTP | Descripción |
|---|---|---|
| `ConnectException` | 502 | Microservicio destino no disponible |
| `ConnectTimeoutException` | 502 | Timeout al conectar con el microservicio |
| `TimeoutException` | 504 | El microservicio no respondió a tiempo |
| `ResponseStatusException` | (respeta el status original) | Errores propagados desde el microservicio |
| `Exception` (genérica) | 500 | Error interno no clasificado |

---

## Tokens JWT

### Obtener token partner (vía gateway)

El gateway expone `/oauth2/token` como proxy al endpoint de Keycloak del realm `hub-partner`. El cliente hace una sola llamada al gateway; no necesita conocer la dirección de Keycloak.

```bash
PARTNER_TOKEN=$(curl -s -X POST http://127.0.0.1:8080/oauth2/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials&client_id=unilink-api&client_secret=unilink-api-secret" \
  | jq -r '.access_token')
```

### Obtener token admin (directo a Keycloak)

```bash
ADMIN_TOKEN=$(curl -s -X POST http://127.0.0.1:8180/realms/hub-admin/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password&client_id=hubadminservice&client_secret=hubadminservice-secret&username=admin&password=admin" \
  | jq -r '.access_token')
```

### Nota sobre consistencia del issuer

El issuer declarado en los tokens y el configurado en el gateway **deben coincidir exactamente**. Usar siempre `127.0.0.1:8180`, nunca `localhost:8180`. Si hay discrepancia, Spring Security rechaza el token con 401 incluso en rutas marcadas como `permitAll()`.

---

## Configuración de Keycloak

### Realm `hub-partner`

| Campo | Valor |
|---|---|
| Realm | `hub-partner` |
| Client ID | `unilink-api` |
| Client Secret | `unilink-api-secret` |
| Grant type | `client_credentials` |
| Uso | APIs externas — `/partner/v1/**` |

### Realm `hub-admin`

| Campo | Valor |
|---|---|
| Realm | `hub-admin` |
| Client ID | `hubadminservice` |
| Client Secret | `hubadminservice-secret` |
| Grant type | `password` (usuarios humanos) o `client_credentials` (M2M) |
| Uso | APIs internas — `/services/**`, Swagger, OIDC |

---

## Enrutamiento hacia microservicios

El gateway usa Consul para resolución de nombres. Los microservicios se registran con estos nombres:

| Microservicio | Nombre en Consul | URI en gateway |
|---|---|---|
| `hub-ms-auth` | `hubadminservice` | `lb://hubadminservice` |
| `hub-ms-base` | `hubbaseservice` | `lb://hubbaseservice` |

La ruta `/services/hubadminservice/**` aplica `StripPrefix=2` antes de enviar al microservicio.

Para que el enrutamiento funcione en local, el perfil `local` deshabilita Vault y Consul config, pero habilita Consul discovery.

---

## Formato de errores (RFC 7807)

Todos los errores del gateway y de los microservicios siguen RFC 7807 con `Content-Type: application/problem+json`.

Estructura base:
```json
{
    "type": "https://api.sintesis.com.bo/problems/{tipo}",
    "title": "Descripción corta",
    "status": 400,
    "detail": "Descripción detallada del error",
    "timestamp": "2026-06-15T10:00:00Z",
    "errorCode": "CODIGO_ERROR"
}
```

Errores de validación incluyen el campo `violations`:
```json
{
    "type": "https://api.sintesis.com.bo/problems/validation-error",
    "title": "Validation Error",
    "status": 400,
    "detail": "One or more fields have validation errors",
    "timestamp": "2026-06-15T10:00:00Z",
    "errorCode": "VALIDATION_ERROR",
    "violations": [
        {
            "field": "content",
            "message": "must not be blank"
        }
    ]
}
```

### Tabla de códigos de error

| errorCode | HTTP | Descripción |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Datos de entrada inválidos |
| `AUTHENTICATION_ERROR` | 401 | Token ausente, expirado o inválido |
| `ACCESS_DENIED` | 403 | Sin permisos o IP no autorizada |
| `CERTIFICATE_NOT_FOUND` | 404 | Certificado no existe |
| `RATE_LIMIT_EXCEEDED` | 429 | Límite de requests excedido |
| `INVALID_QR_FORMAT` | 400 | Formato de QR no reconocido |
| `DECRYPTION_ERROR` | 500 | Error al desencriptar el QR |
| `TUXEDO_API_ERROR` | 502 | Error en el servicio Tuxedo backend |
| `INTERNAL_ERROR` | 500 | Error interno no clasificado |
