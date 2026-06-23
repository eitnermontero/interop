# Guía de APIs para el Frontend — MDQR

Referencia completa para integrar el sistema MDQR desde aplicaciones frontend (Angular, React, etc.).

---

## Base URL y Configuración

| Ambiente | Gateway | Keycloak |
|----------|---------|----------|
| Desarrollo local | `http://localhost:8080` | `http://localhost:8180` |
| QA (`ssaqa001`) | `http://199.3.0.63:8080` | `http://199.3.0.63:8180` |
| Producción | `https://api.sintesis.com.bo` | *(por definir)* |

Todos los endpoints del sistema pasan por el gateway. **No llamar directamente a ms-auth (8083) ni ms-base (8081)** desde el frontend.

---

## Autenticación

El sistema tiene dos flujos según el tipo de cliente:

| Cliente | Flujo | Librería |
|---------|-------|---------|
| SPA Angular (admin) | Authorization Code + PKCE S256 | `keycloak-angular` |
| Partner M2M (externo) | Client Credentials | curl / HTTP client |

---

### Flujo Admin — SPA Angular (PKCE)

El SPA **nunca llama `/admin/auth/login` directamente**. `keycloak-angular` maneja todo el flujo PKCE: redirige al browser a la pantalla de Keycloak, el usuario ingresa sus credenciales ahí, y Keycloak devuelve el token al SPA vía redirect. El token vive en memoria, nunca en localStorage.

#### Configuración del SPA (`apps/admin/public/assets/config.json`)

```json
{
  "apiUrl": "http://localhost:8080",
  "appName": "MDQR Admin",
  "basePath": "/",
  "keycloak": {
    "url": "http://127.0.0.1:8180",
    "realm": "mdqr-admin",
    "clientId": "mdqr-admin-fe"
  }
}
```

El cliente `mdqr-admin-fe` es **público** (no tiene secret), usa Authorization Code + PKCE S256. Lo crea `keycloak-sync-admin.sh`.

#### Inicialización en Angular

```typescript
// app.config.ts
export function appConfig(cfg: RuntimeConfig): ApplicationConfig {
  return {
    providers: [
      provideHttpClient(),
      provideRouter(routes),
      provideMdqrAuth({
        authority: cfg.keycloak.url,       // http://127.0.0.1:8180
        realm: cfg.keycloak.realm,         // mdqr-admin
        clientId: cfg.keycloak.clientId,   // mdqr-admin-fe
        bearerUrls: ['^' + cfg.apiUrl + '/.*'],  // agrega token a todas las llamadas al gateway
      }),
    ],
  };
}
```

`provideMdqrAuth` configura `keycloak-angular` con PKCE S256 y el interceptor `includeBearerTokenInterceptor` que agrega automáticamente el `Authorization: Bearer` a cada petición HTTP cuya URL coincida con `bearerUrls`.

#### Endpoints que el SPA llama después del login

**Perfil del usuario autenticado:**

```bash
curl -s http://127.0.0.1:8080/services/mdqradminservice/admin/auth/me \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq .
```

```json
{
  "id": "uuid-del-usuario",
  "username": "admin",
  "email": "admin@sintesis.com.bo",
  "firstName": "Admin",
  "lastName": "User",
  "fullName": "Admin User",
  "roles": ["ADMIN", "OPERATOR"]
}
```

**Árbol de permisos (menús + acciones RBAC):**

```bash
curl -s http://127.0.0.1:8080/services/mdqradminservice/admin/auth/me/permissions \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq .
```

```json
{
  "user": { "id": "uuid", "username": "admin", "roles": ["ADMIN"] },
  "menus": [
    {
      "code": "CERTIFICATES",
      "name": "Certificados",
      "icon": "certificate",
      "route": "/certificates",
      "actions": ["READ", "CREATE", "UPDATE", "DELETE"],
      "children": []
    },
    {
      "code": "AUDIT",
      "name": "Auditoría",
      "icon": "audit",
      "route": "/audit",
      "actions": ["READ", "EXPORT"],
      "children": []
    }
  ]
}
```

#### Token admin para pruebas con curl (no aplica al SPA)

```bash
ADMIN_TOKEN=$(curl -s -X POST \
  'http://127.0.0.1:8180/realms/mdqr-admin/protocol/openid-connect/token' \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=client_credentials&client_id=mdqradminservice&client_secret=mdqradminservice-secret' \
  | jq -r '.access_token')
```

> Este token usa el service account `mdqradminservice` (confidencial). Solo para testing con curl. El SPA usa `mdqr-admin-fe` (público, PKCE).

---

### Flujo Partner — API Externa M2M

Para clientes externos que consumen la API de desencriptación (integración sistema a sistema, no SPA).

**Obtener token (client credentials)**

```bash
PARTNER_TOKEN=$(curl -s -X POST http://127.0.0.1:8080/oauth2/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials&client_id=unilink-api&client_secret=unilink-api-secret" \
  | jq -r '.access_token')

echo "Partner token: ${PARTNER_TOKEN:0:60}..."
```

Respuesta:
```json
{
  "access_token": "eyJhbGciOiJSUzI1NiIs...",
  "expires_in": 300,
  "token_type": "Bearer",
  "scope": "openid profile"
}
```

### Recomendaciones de seguridad

- El SPA admin usa `keycloak-angular` — tokens en memoria, refresh automático cada 30min
- El partner M2M renueva el token antes de que expire (`expires_in: 300s`)
- Nunca exponer `client_secret` en código frontend
- En caso de 401 en el SPA, `AuthFacade.login()` redirige a Keycloak automáticamente

---

## Prerrequisito: Cargar un certificado de prueba

Antes de poder desencriptar un QR, debe existir al menos un certificado registrado para la entidad.
Esta operación requiere token **admin** (no partner).

```bash
# 1. Obtener token admin
ADMIN_TOKEN=$(curl -s -X POST \
  http://127.0.0.1:8180/realms/mdqr-admin/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials&client_id=mdqradminservice&client_secret=mdqradminservice-secret" \
  | jq -r '.access_token')

# 2. Cargar certificado de Banco Solidario (ejemplo real de preprod)
curl -s -X POST http://127.0.0.1:8080/services/mdqrbaseservice/api/certificates \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  --data '{
    "pemContent": "-----BEGIN CERTIFICATE-----\nMIIHsDCCBpigAwIBAgIKepD3YwADAAS3ijANBgkqhkiG9w0BAQsFADBbMRIwEAYK\nCZImiZPyLGQBGRYCYm8xEzARBgoJkiaJk/IsZAEZFgNjb20xFDASBgoJkiaJk/Is\nZAEZFgRic29sMRowGAYDVQQDExFCU09MLVZBRExQWlNSVi1DQTAeFw0yNjA1MDEx\nOTM2NDlaFw0yODA1MTAxOTM2NDlaMIGCMQswCQYDVQQGEwJCTzELMAkGA1UECBMC\nTVUxDzANBgNVBAcTBkxhIFBhejEdMBsGA1UEChMUQmFuY28gU29saWRhcmlvIFMu\nQS4xFjAUBgNVBAsTDVByZXByb2R1Y2Npb24xHjAcBgNVBAMTFXZhY2h4MXByZS5i\nc29sLmNvbS5ibzCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAJ/lXrU2\nt2uIO2OSVoXyJHRDHALxIowxeYUT/veGE2XmuA7Efjb+CNEW4zZ9aaxlFbPCTbq1\nwQ6s6DILkW3zbmjd9byfiBCz+lEABYvhGKcltgi8xD83RcFosUYSIMyU0eT6yNHf\nKd8NS7npYgbpkbNhIVvf3GWgVKOqnGAMWjvynqOnpj6Yme6W0BbE5lBH8Dcl6d2Z\nFn8z3XzJj5NhafdIRWV0LmJccG2Xdf2kAqvQlF8cIVDeR3+fwgI6JJSLJJyAgrFb\nw2+7ttVTPmXUEl2/Mn4rfM8q2lk4hB/vEUdbkOr20+MA+ozJihpMJG6TI0F/ezDy\nEsblNExWybLdxbUCAwEAAaOCBEwwggRIMCsGA1UdEQQkMCKCFXZhY2h4MXByZS5i\nc29sLmNvbS5ib4IJdmFjaHgxcHJlMB0GA1UdDgQWBBQAnjTki2/6UhPrU56QbsAs\nAjth0DAfBgNVHSMEGDAWgBR344w1N9ZJW0Z0yqcT46L1CRPMAzCCAVQGA1UdHwSC\nAUswggFHMIIBQ6CCAT+gggE7hoHAbGRhcDovLy9DTj1CU09MLVZBRExQWlNSVi1D\nQSgzKSxDTj1WQURMUFpTUlYsQ049Q0RQLENOPVB1YmxpYyUyMEtleSUyMFNlcnZp\nY2VzLENOPVNlcnZpY2VzLENOPUNvbmZpZ3VyYXRpb24sREM9YnNvbCxEQz1jb20s\nREM9Ym8/Y2VydGlmaWNhdGVSZXZvY2F0aW9uTGlzdD9iYXNlP29iamVjdENsYXNz\nPWNSTERpc3RyaWJ1dGlvblBvaW50hkBodHRwOi8vVkFETFBaU1JWLmJzb2wuY29t\nLmJvL0NlcnRFbnJvbGwvQlNPTC1WQURMUFpTUlYtQ0EoMykuY3JshjRodHRwOi8v\ndmFkbHB6c3J2L0NlcnRFbnJvbGwvQlNPTC1WQURMUFpTUlYtQ0EoMykuY3JsMIIB\nTwYIKwYBBQUHAQEEggFBMIIBPTCBswYIKwYBBQUHMAKGgaZsZGFwOi8vL0NOPUJT\nT0wtVkFETFBaU1JWLUNBLENOPUFJQSxDTj1QdWJsaWMlMjBLZXklMjBTZXJ2aWNl\ncy5DTj1TZXJ2aWNlcyxDTj1Db25maWd1cmF0aW9uLERDPWJzb2wsREM9Y29tLERD\nPWJvP2NBQ2VydGlmaWNhdGU/YmFzZT9vYmplY3RDbGFzcz1jZXJ0aWZpY2F0aW9u\nQXV0aG9yaXR5MGIGCCsGAQUFBzAChlZodHRwOi8vVkFETFBaU1JWLmJzb2wuY29t\nLmJvL0NlcnRFbnJvbGwvVkFETFBaU1JWLmJzb2wuY29tLmJvX0JTT0wtVkFETFBa\nU1JWLUNBKDMpLmNydDAhBggrBgEFBQcwAYYVaHR0cDovL3ZhZGxwenNydi9vY3Nw\nMAsGA1UdDwQEAwIFoDA9BgkrBgEEAYI3FQcEMDAuBiYrBgEEAYI3FQiBl4tMg/q3\nGIGhlT+E9sJJhtS2U0yB3fkYgunJMgIBZAIBCDA7BgNVHSUENDAyBiYrBgEEAYI3\nFQiBl4tMg/q3GIGhlT+E9sJJhtS2U0yDnMxkg8ipGwYIKwYBBQUHAwEwRQYJKwYB\nBAGCNxUKBDgwNjAoBiYrBgEEAYI3FQiBl4tMg/q3GIGhlT+E9sJJhtS2U0yDnMxk\ng8ipGzAKBggrBgEFBQcDATBeBgNVHSAEVzBVMFMGJisGAQQBgjcVCIGXi0yD+rcY\ngaGVP4T2wkmG1LZTTIHhogqF3KMFMCkwJwYIKwYBBQUHAgEWG2h0dHBzOi8vd3d3\nLmJhbmNvc29sLmNvbS5ibzANBgkqhkiG9w0BAQsFAAOCAQEAQAFk7yGyYizeG/6m\nOfVpXuNL4U/+iiy0xvIZQzg++l2dheY2AK5ntfnzMkqe8s0WiCyAvX9KalRfo7ED\n3chdgCR1DO74x11XpFtdNS42trXmU6wMLeEbU3pV3m9rnlas1QmAW4dwZ1/M88nt\nXcUqx2CkdjpgV/X659KIEEGRC/r0TzbVtcnMX9hlVDUHyp6Z3pak5GPDUb7STa6v\nueRwYWgoUJNohWiVpIx+IGQnXwxJVaTCn7fSifTW59mKfhXYRqQcgqT4TjGrbYll\nBhrRAL+2ri2S5D1mJ7PzR3E9wBDjKo7krLw3DgxzyImo0LpmQooQOu7IIUP3NVS/\n9TUxqw==\n-----END CERTIFICATE-----",
    "entityId": "1017",
    "entityName": "Banco Solidario S.A.",
    "description": "Certificado Banco Sol - Producción 2026-2028",
    "tags": ["bancosol", "produccion", "2026-2028"]
  }' | jq .
```

Respuesta esperada (201 Created):
```json
{
  "id": 1,
  "serialNumber": "7a90f763...",
  "entityId": "1017",
  "entityName": "Banco Solidario S.A.",
  "issuerCn": "BSOL-VADLPZSRV-CA",
  "validFrom": "2026-05-11T19:36:49Z",
  "validTo": "2028-05-10T19:36:49Z",
  "daysRemaining": 694,
  "status": "ACTIVE",
  "isActive": true,
  "isRevoked": false
}
```

Verificar que quedó cargado:
```bash
curl -s http://127.0.0.1:8080/services/mdqrbaseservice/api/certificates \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq '.content[] | {id, entityId, entityName, status}'
```

---

## API Partner — Desencriptación de QR

Base path en el gateway: `/partner/v1/` → internamente enruta a ms-base `/api/`

Todos los endpoints requieren: `Authorization: Bearer <partner_token>`

### POST /partner/v1/qr/decode

Desencripta el contenido de un código QR bancario.

```bash
curl -s -X POST http://127.0.0.1:8080/partner/v1/qr/decode \
  -H "Authorization: Bearer $PARTNER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "inputType": "DECODED_DATA",
    "content": "<string_qr_escaneado>",
    "entityIdRequest": "MLD1017",
    "externalReference": "TXN-20260615-001"
  }' | jq .
```

Campos del request:

| Campo | Tipo | Requerido | Descripción |
|-------|------|-----------|-------------|
| `inputType` | `DECODED_DATA` \| `BASE64_IMAGE` | Sí | Tipo de contenido del QR |
| `content` | string | Sí | Contenido del QR escaneado o imagen en Base64 |
| `entityIdRequest` | string | No | Código de entidad solicitante (ej: MLD1017) |
| `externalReference` | string | No | Referencia externa (orden, transacción) |
| `metadata` | `{[key: string]: string}` | No | Metadatos adicionales |

Respuesta exitosa (200):
```json
{
  "logId": "a1b2c3d4-e5f6-...",
  "decryptedData": "{\"amount\": 100.00, \"account\": \"123456\"}",
  "certificateCode": "69e6b38b",
  "entityId": "MLD1017",
  "qrType": "PAYMENT",
  "processingTimeMs": 45,
  "decryptedAt": "2026-06-15T14:30:00Z",
  "fromCache": false
}
```

### POST /partner/v1/qr/decode/file

Desencripta un QR a partir de una imagen (multipart).

```bash
curl -s -X POST http://127.0.0.1:8080/partner/v1/qr/decode/file \
  -H "Authorization: Bearer $PARTNER_TOKEN" \
  -F "file=@/ruta/al/qr.png" \
  -F "entityIdRequest=MLD1017" \
  -F "externalReference=TXN-001" | jq .
```

Parámetros multipart:

| Campo | Tipo | Requerido | Descripción |
|-------|------|-----------|-------------|
| `file` | MultipartFile | Sí | Imagen con el código QR (PNG, JPG, BMP) |
| `entityIdRequest` | string | No | Código de entidad solicitante |
| `externalReference` | string | No | Referencia externa |

---

## API Admin — Certificados

Base path en el gateway: `/services/mdqrbaseservice/api/certificates`

Todos los endpoints requieren: `Authorization: Bearer <admin_token>`

### GET /services/mdqrbaseservice/api/certificates

Lista certificados con paginación.

```bash
curl -s "http://127.0.0.1:8080/services/mdqrbaseservice/api/certificates?page=0&size=20&sort=validTo,asc" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq .
```

Query params: `page` (default 0), `size` (default 20), `sort` (campo,dirección).

Respuesta: Page de `CertificateDTO` con header `X-Total-Count`.

### POST /services/mdqrbaseservice/api/certificates

Registra un nuevo certificado a partir del contenido PEM.

```bash
curl -s -X POST http://127.0.0.1:8080/services/mdqrbaseservice/api/certificates \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "pemContent": "-----BEGIN CERTIFICATE-----\nMIIDXTCCAkWg...\n-----END CERTIFICATE-----",
    "entityId": "MLD1017",
    "entityName": "Banco Mercantil Santa Cruz",
    "description": "Certificado de producción 2026",
    "tags": ["produccion", "bsc"],
    "notificationEmails": ["admin@sintesis.com.bo"]
  }' | jq .
# → 201 Created con CertificateDTO
```

### POST /services/mdqrbaseservice/api/certificates/upload-file

Sube un certificado desde archivo .pem o .crt (multipart).

```bash
curl -s -X POST http://127.0.0.1:8080/services/mdqrbaseservice/api/certificates/upload-file \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -F "file=@/ruta/banco.pem" \
  -F "entityId=MLD1017" \
  -F "entityName=Banco Mercantil Santa Cruz" | jq .
# → 201 Created
```

### POST /services/mdqrbaseservice/api/certificates/validate

Valida un certificado sin guardarlo.

```bash
curl -s -X POST http://127.0.0.1:8080/services/mdqrbaseservice/api/certificates/validate \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"pemContent": "-----BEGIN CERTIFICATE-----\n...", "entityId": "MLD1017"}' | jq .
# → 200 con metadata del certificado
```

### GET /services/mdqrbaseservice/api/certificates/{id}

Detalle completo de un certificado (incluye PEM content).

```bash
curl -s http://127.0.0.1:8080/services/mdqrbaseservice/api/certificates/1 \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq .
```

### GET /services/mdqrbaseservice/api/certificates/{id}/pem

Descarga el contenido PEM del certificado.

```bash
curl -s http://127.0.0.1:8080/services/mdqrbaseservice/api/certificates/1/pem \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -o certificado.pem
```

### GET /services/mdqrbaseservice/api/certificates/entity/{entityId}

Lista certificados de una entidad específica.

```bash
curl -s http://127.0.0.1:8080/services/mdqrbaseservice/api/certificates/entity/MLD1017 \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq .
```

### GET /services/mdqrbaseservice/api/certificates/expiring/{days}

Lista certificados que expiran en N días.

```bash
curl -s http://127.0.0.1:8080/services/mdqrbaseservice/api/certificates/expiring/30 \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq .
```

### POST /services/mdqrbaseservice/api/certificates/{id}/activate

```bash
curl -s -X POST http://127.0.0.1:8080/services/mdqrbaseservice/api/certificates/1/activate \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq .status
```

### POST /services/mdqrbaseservice/api/certificates/{id}/deactivate

```bash
curl -s -X POST http://127.0.0.1:8080/services/mdqrbaseservice/api/certificates/1/deactivate \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq .status
```

### POST /services/mdqrbaseservice/api/certificates/{id}/revoke

Revocación irreversible.

```bash
curl -s -X POST http://127.0.0.1:8080/services/mdqrbaseservice/api/certificates/1/revoke \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"reason": "Certificado comprometido"}' | jq .
```

### POST /services/mdqrbaseservice/api/certificates/{id}/replace

Reemplaza con un nuevo certificado. Marca el anterior como SUPERSEDED.

```bash
curl -s -X POST http://127.0.0.1:8080/services/mdqrbaseservice/api/certificates/1/replace \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "newPemContent": "-----BEGIN CERTIFICATE-----\n...",
    "changeReason": "Renovación anual"
  }' | jq .
# → 201 Created con nuevo CertificateDTO
```

---

## API Admin — Auditoría QR

### GET /services/mdqrbaseservice/api/qr/audits

Log de todas las desencriptaciones realizadas.

```bash
curl -s "http://127.0.0.1:8080/services/mdqrbaseservice/api/qr/audits?\
status=SUCCESS&fromDate=2026-06-01T00:00:00Z&page=0&size=20" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq .
```

Query params:

| Param | Descripción |
|-------|-------------|
| `keycloakClientId` | Filtrar por client ID del partner |
| `certificateCode` | Filtrar por serial del certificado |
| `entityId` | Filtrar por entidad |
| `status` | `SUCCESS` \| `ERROR` |
| `fromDate` | ISO-8601 (ej: `2026-06-01T00:00:00Z`) |
| `toDate` | ISO-8601 |
| `page` | Default: 0 |
| `size` | Default: 20, máx: 100 |
| `sort` | Default: `createdDate` |
| `order` | `asc` \| `desc` (default: `desc`) |

### GET /services/mdqrbaseservice/api/certificates/audits

Log de operaciones sobre certificados.

```bash
curl -s "http://127.0.0.1:8080/services/mdqrbaseservice/api/certificates/audits?\
action=REVOKE&fromDate=2026-06-01T00:00:00Z" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq .
```

Query params: `certificateId`, `serialNumber`, `action` (UPLOAD|VALIDATE|ACTIVATE|DEACTIVATE|REVOKE|REPLACE|VIEW|DOWNLOAD), `userId`, `success`, `fromDate`, `toDate`, `page`, `size`, `sort`.

---

## API Admin — Usuarios y Roles

### Usuarios

```bash
# Listar usuarios
curl -s "http://127.0.0.1:8080/services/mdqradminservice/admin/users?page=0&size=20" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq .

# Crear usuario
curl -s -X POST http://127.0.0.1:8080/services/mdqradminservice/admin/users \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "operador01",
    "email": "operador@sintesis.com.bo",
    "firstName": "Juan",
    "lastName": "Pérez",
    "password": "temporal123",
    "temporaryPassword": true,
    "enabled": true,
    "roles": ["OPERATOR"]
  }' | jq .
# → 201 Created con Location header

# Actualizar usuario
curl -s -X PUT http://127.0.0.1:8080/services/mdqradminservice/admin/users/{userId} \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"firstName": "Juan Carlos", "enabled": true}' | jq .

# Activar/desactivar
curl -s -X PUT http://127.0.0.1:8080/services/mdqradminservice/admin/users/{userId}/status \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"enabled": false}' \
  -w "%{http_code}"
# → 204

# Asignar roles
curl -s -X PUT http://127.0.0.1:8080/services/mdqradminservice/admin/users/{userId}/roles \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"roles": ["OPERATOR", "AUDITOR"]}' \
  -w "%{http_code}"
# → 204
```

### Roles

```bash
# Listar roles
curl -s http://127.0.0.1:8080/services/mdqradminservice/admin/roles \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq .

# Crear rol
curl -s -X POST http://127.0.0.1:8080/services/mdqradminservice/admin/roles \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name": "SUPERVISOR", "description": "Supervisor de operaciones"}' | jq .
```

### Auditoría Admin

```bash
# Log de acciones admin
curl -s "http://127.0.0.1:8080/services/mdqradminservice/admin/audit?\
fromDate=2026-06-01T00:00:00Z&modules=USUARIOS,ROLES&page=0&size=50" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq .

# Exportar CSV
curl -s "http://127.0.0.1:8080/services/mdqradminservice/admin/audit/export?\
fromDate=2026-06-01T00:00:00Z" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -o audit-export.csv
```

---

## Health Checks

```bash
# Gateway (sin auth)
curl -s http://127.0.0.1:8080/management/health | jq .

# ms-auth vía gateway (con auth)
curl -s http://127.0.0.1:8080/services/mdqradminservice/management/health \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq .status

# ms-base vía gateway (con auth)
curl -s http://127.0.0.1:8080/services/mdqrbaseservice/management/health \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq .status
```

---

## Manejo de Errores

El sistema usa el estándar RFC 7807 (Problem Details for HTTP APIs).

### Formato de error

```json
{
  "type": "https://api.sintesis.com.bo/problems/certificate-not-found",
  "title": "Certificate Not Found",
  "status": 404,
  "detail": "No se encontró certificado con id: 99",
  "timestamp": "2026-06-15T14:30:00Z",
  "errorCode": "CERTIFICATE_NOT_FOUND"
}
```

### Error de validación (400)

```json
{
  "type": "https://api.sintesis.com.bo/problems/validation-error",
  "title": "Validation Error",
  "status": 400,
  "detail": "Error de validación en los datos enviados",
  "timestamp": "2026-06-15T14:30:00Z",
  "errorCode": "VALIDATION_ERROR",
  "violations": {
    "inputType": "no debe ser nulo",
    "content": "no debe estar en blanco"
  }
}
```

### Códigos de error

| errorCode | HTTP | Descripción |
|-----------|------|-------------|
| `VALIDATION_ERROR` | 400 | Campos inválidos o faltantes |
| `INVALID_QR_FORMAT` | 400 | Formato del string QR no reconocido |
| `AUTHENTICATION_ERROR` | 401 | Token inválido, expirado o ausente |
| `ACCESS_DENIED` | 403 | Sin permisos para la operación |
| `CERTIFICATE_NOT_FOUND` | 404 | Certificado no existe |
| `DECRYPTION_ERROR` | 500 | Error al desencriptar el QR |
| `TUXEDO_API_ERROR` | 502 | Error al comunicar con servicio de certificados |
| `INTERNAL_ERROR` | 500 | Error inesperado del servidor |

### Errores del Gateway

| HTTP | Situación |
|------|-----------|
| 401 | Token ausente o inválido |
| 429 | Rate limit excedido (header `Retry-After: 60`) |
| 502 | Servicio de backend no disponible |
| 504 | Timeout del servicio de backend |

---

## Modelos TypeScript

```typescript
// ============ Autenticación ============
// El SPA usa keycloak-angular — no hay LoginRequest/TokenResponse que manejar manualmente.
// Solo se consumen los endpoints /me y /me/permissions después del login PKCE.

interface MeResponse {
  id: string;
  username: string;
  email: string;
  firstName: string;
  lastName: string;
  fullName: string;
  roles: string[];
}

interface MenuNode {
  code: string;
  name: string;
  icon: string;
  route: string;
  actions: string[];
  children: MenuNode[];
}

// ============ QR Decode ============

type InputType = 'DECODED_DATA' | 'BASE64_IMAGE';

interface DecodeQrRequest {
  inputType: InputType;
  content: string;
  entityIdRequest?: string;
  externalReference?: string;
  metadata?: Record<string, string>;
}

interface DecryptQrResponse {
  logId: string;
  decryptedData: string;
  certificateCode: string;
  entityId: string;
  qrType: string;
  processingTimeMs: number;
  decryptedAt: string;   // ISO-8601 UTC
  fromCache: boolean;
}

// ============ Certificados ============

type CertificateStatus = 'ACTIVE' | 'EXPIRING_SOON' | 'EXPIRED' | 'REVOKED' | 'SUPERSEDED';

interface CertificateDTO {
  id: number;
  serialNumber: string;
  fingerprintSha256: string;
  entityId: string;
  entityName: string;
  subjectDn: string;
  issuerDn: string;
  issuerCn: string;
  validFrom: string;   // ISO-8601 UTC
  validTo: string;     // ISO-8601 UTC
  daysRemaining: number;
  status: CertificateStatus;
  versionNumber: number;
  isCurrentVersion: boolean;
  isActive: boolean;
  isRevoked: boolean;
  revokedAt?: string;
  revokedBy?: string;
  revokedReason?: string;
  description?: string;
  tags?: string[];
  notificationEmails?: string[];
  createdDate: string;
  createdBy: string;
  lastModifiedDate: string;
  lastModifiedBy: string;
}

interface CertificateDetailDTO extends CertificateDTO {
  pemContent: string;  // Contenido PEM completo
}

interface UploadCertificateRequest {
  pemContent: string;            // mín 100, máx 10000 chars
  entityId: string;              // mín 2, máx 50 chars
  entityName?: string;
  description?: string;
  tags?: string[];
  notificationEmails?: string[];
}

interface RevokeCertificateRequest {
  reason: string;  // máx 500 chars
}

interface ReplaceCertificateRequest {
  newPemContent: string;   // mín 100, máx 10000 chars
  changeReason: string;    // máx 500 chars
}

// ============ Paginación ============

interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;  // página actual (0-based)
}

// X-Total-Count header contiene el total de elementos

// ============ Errores RFC 7807 ============

interface ProblemDetail {
  type: string;
  title: string;
  status: number;
  detail: string;
  timestamp: string;
  errorCode: string;
  violations?: Record<string, string>;  // solo en error 400
  technicalDetail?: string;             // solo en 5xx
  exceptionType?: string;               // solo en 5xx
}

// ============ Usuarios (Admin) ============

interface UserDto {
  id: string;
  username: string;
  email: string;
  firstName: string;
  lastName: string;
  enabled: boolean;
  emailVerified: boolean;
}

interface CreateUserRequest {
  username: string;
  email: string;
  firstName?: string;
  lastName?: string;
  enabled?: boolean;
  password?: string;
  temporaryPassword?: boolean;
  roles?: string[];
}

interface RoleDto {
  id: string;
  name: string;
  description: string;
  composite: boolean;
}
```

---

## Interceptor Angular

El SPA **no necesita implementar un interceptor manual**. `keycloak-angular` registra `includeBearerTokenInterceptor` automáticamente cuando se usa `provideMdqrAuth`. Este interceptor agrega el `Authorization: Bearer` a todas las peticiones HTTP cuya URL coincida con `bearerUrls`.

```typescript
// Configuración en app.config.ts — bearerUrls define a qué URLs se agrega el token
provideMdqrAuth({
  authority: cfg.keycloak.url,
  realm: cfg.keycloak.realm,
  clientId: cfg.keycloak.clientId,
  bearerUrls: ['^' + cfg.apiUrl + '/.*'],  // todas las llamadas al gateway
})
```

Si necesitas manejar errores 401/403 globalmente (ej. mostrar un toast), usa un interceptor adicional solo para errores:

```typescript
// http-error.interceptor.ts
import { inject } from '@angular/core';
import { HttpInterceptorFn, HttpErrorResponse } from '@angular/common/http';
import { catchError, throwError } from 'rxjs';
import { AuthFacade } from '@mdqr/auth';

export const httpErrorInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthFacade);

  return next(req).pipe(
    catchError((error: HttpErrorResponse) => {
      if (error.status === 401) {
        auth.login();  // keycloak-angular redirige a la pantalla de login
      }
      return throwError(() => error);
    })
  );
};
```

```typescript
// error.service.ts — manejo de errores RFC 7807
import { HttpErrorResponse } from '@angular/common/http';

export interface ApiError {
  errorCode: string;
  title: string;
  detail: string;
  status: number;
  violations?: Record<string, string>;
}

export function parseApiError(error: HttpErrorResponse): ApiError {
  if (error.error && error.error.errorCode) {
    return {
      errorCode: error.error.errorCode,
      title: error.error.title ?? 'Error',
      detail: error.error.detail ?? 'Error desconocido',
      status: error.status,
      violations: error.error.violations,
    };
  }
  return {
    errorCode: 'NETWORK_ERROR',
    title: 'Error de red',
    detail: error.message,
    status: error.status,
  };
}
```

---

## Proxy de Desarrollo Angular

```json
// proxy.conf.json
{
  "/services/**": {
    "target": "http://localhost:8080",
    "secure": false,
    "changeOrigin": true
  },
  "/oauth2/**": {
    "target": "http://localhost:8080",
    "secure": false,
    "changeOrigin": true
  },
  "/partner/**": {
    "target": "http://localhost:8080",
    "secure": false,
    "changeOrigin": true
  }
}
```

```json
// angular.json (fragmento)
{
  "serve": {
    "options": {
      "proxyConfig": "proxy.conf.json"
    }
  }
}
```

Con este proxy, el frontend en `http://localhost:4200` puede llamar directamente a `/services/...`, `/oauth2/...` y `/partner/...` sin problemas de CORS.
