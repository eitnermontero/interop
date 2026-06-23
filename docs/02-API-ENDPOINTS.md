# 02 - API Endpoints

## Convenciones

- Gateway base URL: `http://127.0.0.1:8080`
- Keycloak URL: `http://127.0.0.1:8180`
- Autenticación: Bearer token en el header `Authorization: Bearer <token>`
- Content-Type: `application/json` (salvo que se indique multipart)
- Fechas en ISO-8601
- Paginación: query params `page` (base 0), `size`, `sort`, `order`; total en header `X-Total-Count`
- Errores: RFC 7807 con `Content-Type: application/problem+json`

---

## Obtener tokens

### Token partner (vía gateway — realm mdqr-partner)

```bash
PARTNER_TOKEN=$(curl -s -X POST http://127.0.0.1:8080/oauth2/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials&client_id=unilink-api&client_secret=unilink-api-secret" \
  | jq -r '.access_token')
```

### Token admin (directo a Keycloak — realm mdqr-admin)

```bash
ADMIN_TOKEN=$(curl -s -X POST http://127.0.0.1:8180/realms/mdqr-admin/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password&client_id=mdqradminservice&client_secret=mdqradminservice-secret&username=admin&password=admin" \
  | jq -r '.access_token')
```

---

## mdqr-ms-base — Desencriptación QR y certificados

Accesible para partners a través del gateway en `/partner/v1/` (JWT realm `mdqr-partner`).
Accesible para admins a través del gateway en `/services/mdqrbaseservice/` (JWT realm `mdqr-admin`).

### POST /api/qr/decode — Desencriptar QR

Desencripta el contenido de un código QR. Acepta datos pre-procesados o imagen en base64.

**Request:**
```json
{
    "inputType": "DECODED_DATA",
    "content": "...",
    "entityIdRequest": "OPCIONAL",
    "externalReference": "OPCIONAL",
    "metadata": {}
}
```

| Campo | Tipo | Requerido | Descripción |
|---|---|---|---|
| `inputType` | string | Si | `DECODED_DATA` o `BASE64_IMAGE` |
| `content` | string | Si | Datos del QR o imagen en base64 |
| `entityIdRequest` | string | No | ID de entidad para filtrar el certificado |
| `externalReference` | string | No | Referencia externa para trazabilidad |
| `metadata` | object | No | Metadatos adicionales libres |

**Response 200:**
```json
{
    "logId": "a1b2c3d4-e5f6-...",
    "decryptedData": "...",
    "certificateCode": "CERT-001",
    "entityId": "ENTITY-001",
    "qrType": "TIPO_QR",
    "processingTimeMs": 123,
    "decryptedAt": "2026-06-15T10:00:00Z",
    "fromCache": false
}
```

**Curl ejemplo (partner):**
```bash
curl -s -X POST http://127.0.0.1:8080/partner/v1/qr/decode \
  -H "Authorization: Bearer $PARTNER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "inputType": "DECODED_DATA",
    "content": "datos-del-qr"
  }' | jq
```

---

### POST /api/qr/decode/file — Desencriptar QR desde imagen

Acepta una imagen del código QR como archivo multipart.

**Request:** `multipart/form-data`

| Param | Tipo | Requerido | Descripción |
|---|---|---|---|
| `file` | MultipartFile | Si | Imagen del código QR |
| `entityIdRequest` | string | No | ID de entidad |
| `externalReference` | string | No | Referencia externa |

**Response 200:** igual a `/api/qr/decode`

**Curl ejemplo (partner):**
```bash
curl -s -X POST http://127.0.0.1:8080/partner/v1/qr/decode/file \
  -H "Authorization: Bearer $PARTNER_TOKEN" \
  -F "file=@/ruta/imagen.png" | jq
```

---

### GET /api/qr/audits — Auditoría de desencriptaciones

**Query params:**

| Param | Tipo | Descripción |
|---|---|---|
| `keycloakClientId` | string | Filtrar por client ID del partner |
| `certificateCode` | string | Filtrar por código de certificado |
| `entityId` | string | Filtrar por entidad |
| `status` | string | `SUCCESS` o `ERROR` |
| `fromDate` | string (ISO-8601) | Fecha inicio |
| `toDate` | string (ISO-8601) | Fecha fin |
| `page` | int | Página (default 0) |
| `size` | int | Tamaño (default 20, max 100) |
| `sort` | string | Campo de ordenamiento (default `createdDate`) |
| `order` | string | `asc` o `desc` (default `desc`) |

**Response 200:** Page de registros de auditoría. Header `X-Total-Count` con el total.

**Curl ejemplo (admin):**
```bash
curl -s "http://127.0.0.1:8080/services/mdqrbaseservice/api/qr/audits?status=ERROR&page=0&size=20" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq
```

---

### GET /api/certificates — Listar certificados

**Query params:** Pageable estándar (`page`, `size`, `sort`)

**Response 200:** Page de `CertificateDTO`. Header `X-Total-Count`.

**Curl ejemplo (admin):**
```bash
curl -s "http://127.0.0.1:8080/services/mdqrbaseservice/api/certificates?page=0&size=20" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq
```

---

### POST /api/certificates — Crear certificado

**Request:**
```json
{
    "pemContent": "-----BEGIN CERTIFICATE-----\n...\n-----END CERTIFICATE-----",
    "entityId": "ENTITY-001",
    "entityName": "Nombre entidad",
    "description": "Descripción opcional",
    "tags": ["tag1", "tag2"],
    "notificationEmails": ["admin@empresa.com"]
}
```

| Campo | Tipo | Requerido |
|---|---|---|
| `pemContent` | string | Si |
| `entityId` | string | Si |
| `entityName` | string | No |
| `description` | string | No |
| `tags` | string[] | No |
| `notificationEmails` | string[] | No |

**Response 201:** `CertificateDTO`

**Curl ejemplo:**
```bash
curl -s -X POST http://127.0.0.1:8080/services/mdqrbaseservice/api/certificates \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "pemContent": "-----BEGIN CERTIFICATE-----\n...",
    "entityId": "ENTITY-001"
  }' | jq
```

---

### POST /api/certificates/upload-file — Crear certificado desde archivo PEM

**Request:** `multipart/form-data`

| Param | Tipo | Requerido |
|---|---|---|
| `file` | MultipartFile | Si |
| `entityId` | string | Si |
| `entityName` | string | No |
| `description` | string | No |

**Response 201:** `CertificateDTO`

**Curl ejemplo:**
```bash
curl -s -X POST http://127.0.0.1:8080/services/mdqrbaseservice/api/certificates/upload-file \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -F "file=@/ruta/certificado.pem" \
  -F "entityId=ENTITY-001" | jq
```

---

### POST /api/certificates/validate — Validar certificado sin guardarlo

Valida el contenido PEM y retorna los metadatos del certificado sin persistirlo.

**Request:** igual a `POST /api/certificates`

**Response 200:** metadatos del certificado (sin `id`)

---

### GET /api/certificates/{id} — Detalle de certificado

**Response 200:** `CertificateDetailDTO` (incluye `pemContent`)

**Curl ejemplo:**
```bash
curl -s "http://127.0.0.1:8080/services/mdqrbaseservice/api/certificates/1" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq
```

---

### GET /api/certificates/{id}/pem — Descargar PEM

**Response 200:** texto PEM con header `Content-Disposition: attachment; filename=certificate-{id}.pem`

---

### GET /api/certificates/entity/{entityId} — Certificados por entidad

**Response 200:** `List<CertificateDTO>`

---

### GET /api/certificates/expiring/{days} — Certificados próximos a vencer

Retorna certificados que vencen en los próximos `{days}` días.

**Response 200:** `List<CertificateDTO>`

**Curl ejemplo:**
```bash
curl -s "http://127.0.0.1:8080/services/mdqrbaseservice/api/certificates/expiring/30" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq
```

---

### POST /api/certificates/{id}/activate — Activar certificado

**Response 200:** `CertificateDTO`

---

### POST /api/certificates/{id}/deactivate — Desactivar certificado

**Response 200:** `CertificateDTO`

---

### POST /api/certificates/{id}/revoke — Revocar certificado

**Request:**
```json
{
    "reason": "Motivo de revocación"
}
```

**Response 200:** `CertificateDTO`

---

### POST /api/certificates/{id}/replace — Reemplazar certificado

Crea una nueva versión del certificado. El anterior queda en estado `SUPERSEDED`.

**Request:**
```json
{
    "newPemContent": "-----BEGIN CERTIFICATE-----\n...",
    "changeReason": "Renovación anual"
}
```

**Response 201:** `CertificateDTO` (nueva versión)

---

### GET /api/certificates/audits — Auditoría de certificados

**Query params:**

| Param | Tipo | Descripción |
|---|---|---|
| `certificateId` | long | Filtrar por ID de certificado |
| `serialNumber` | string | Filtrar por número de serie |
| `action` | string | Acción realizada |
| `userId` | string | ID del usuario que realizó la acción |
| `success` | boolean | Si la operación fue exitosa |
| `fromDate` | string (ISO-8601) | Fecha inicio |
| `toDate` | string (ISO-8601) | Fecha fin |
| `page` | int | Página (default 0) |
| `size` | int | Tamaño (default 20) |
| `sort` | string | Campo de ordenamiento |

**Response 200:** Page con header `X-Total-Count`.

---

### CertificateDTO — Campos

| Campo | Tipo | Descripción |
|---|---|---|
| `id` | long | ID interno |
| `serialNumber` | string | Número de serie del certificado |
| `fingerprintSha256` | string | Huella SHA-256 |
| `entityId` | string | ID de la entidad propietaria |
| `entityName` | string | Nombre de la entidad |
| `subjectDn` | string | DN del sujeto |
| `issuerDn` | string | DN del emisor |
| `issuerCn` | string | CN del emisor |
| `validFrom` | string (ISO-8601) | Inicio de validez |
| `validTo` | string (ISO-8601) | Fin de validez |
| `daysRemaining` | int | Días restantes hasta vencimiento |
| `status` | string | `ACTIVE`, `EXPIRING_SOON`, `EXPIRED`, `REVOKED`, `SUPERSEDED` |
| `versionNumber` | int | Número de versión |
| `isCurrentVersion` | boolean | Si es la versión activa |
| `isActive` | boolean | Si está activo |
| `isRevoked` | boolean | Si está revocado |
| `revokedAt` | string (ISO-8601) | Fecha de revocación |
| `revokedBy` | string | Usuario que revocó |
| `revokedReason` | string | Motivo de revocación |
| `description` | string | Descripción |
| `tags` | string[] | Etiquetas |
| `notificationEmails` | string[] | Emails para notificaciones de vencimiento |
| `createdDate` | string (ISO-8601) | Fecha de creación |
| `createdBy` | string | Usuario que creó |
| `lastModifiedDate` | string (ISO-8601) | Última modificación |
| `lastModifiedBy` | string | Usuario que modificó |

`CertificateDetailDTO` extiende `CertificateDTO` e incluye además el campo `pemContent`.

---

## mdqr-ms-auth — Autenticación y RBAC

Accesible a través del gateway en `/services/mdqradminservice/` (JWT realm `mdqr-admin`).

---

### Autenticación de usuarios admin

#### POST /admin/auth/login

```bash
curl -s -X POST http://127.0.0.1:8080/services/mdqradminservice/admin/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "admin", "password": "admin"}' | jq
```

**Request:**
```json
{
    "username": "admin",
    "password": "admin"
}
```

**Response 200:**
```json
{
    "access_token": "eyJ...",
    "refresh_token": "eyJ...",
    "expires_in": 300,
    "token_type": "Bearer"
}
```

---

#### POST /admin/auth/refresh

**Request:**
```json
{
    "refresh_token": "eyJ..."
}
```

**Response 200:**
```json
{
    "access_token": "eyJ...",
    "expires_in": 300,
    "token_type": "Bearer"
}
```

---

#### POST /admin/auth/logout

**Request:**
```json
{
    "refresh_token": "eyJ..."
}
```

**Response 204:** sin body

---

#### GET /admin/auth/me — Perfil del usuario autenticado

```bash
curl -s http://127.0.0.1:8080/services/mdqradminservice/admin/auth/me \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq
```

**Response 200:**
```json
{
    "id": "uuid",
    "username": "admin",
    "email": "admin@sintesis.com.bo",
    "firstName": "Admin",
    "lastName": "Sistema",
    "fullName": "Admin Sistema",
    "roles": ["ROLE_ADMIN"]
}
```

---

#### GET /admin/auth/me/permissions — Árbol de permisos del usuario

Retorna el árbol de menús y acciones permitidas para el usuario autenticado.

```bash
curl -s http://127.0.0.1:8080/services/mdqradminservice/admin/auth/me/permissions \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq
```

---

### Gestión de usuarios

Base: `/admin/users`

| Método | Ruta | Descripción |
|--------|------|-------------|
| GET | `/admin/users` | Listar usuarios (Pageable) |
| GET | `/admin/users/{id}` | Obtener usuario por ID |
| POST | `/admin/users` | Crear usuario |
| PUT | `/admin/users/{id}` | Actualizar usuario |
| DELETE | `/admin/users/{id}` | Eliminar usuario |
| PUT | `/admin/users/{id}/password` | Cambiar contraseña |
| PUT | `/admin/users/{id}/status` | Activar/desactivar usuario |
| GET | `/admin/users/{id}/roles` | Obtener roles del usuario |
| PUT | `/admin/users/{id}/roles` | Asignar roles al usuario |
| POST | `/admin/users/{id}/send-reset` | Enviar email de reset de contraseña |

**PUT /admin/users/{id}/status — body:**
```json
{
    "enabled": true
}
```

**PUT /admin/users/{id}/roles — body:**
```json
{
    "roles": ["ROLE_ADMIN", "ROLE_OPERATOR"]
}
```

**Curl listar usuarios:**
```bash
curl -s "http://127.0.0.1:8080/services/mdqradminservice/admin/users?page=0&size=20" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq
```

---

### Gestión de roles

Base: `/admin/roles`

| Método | Ruta | Descripción |
|--------|------|-------------|
| GET | `/admin/roles` | Listar roles |
| GET | `/admin/roles/{name}` | Obtener rol por nombre |
| POST | `/admin/roles` | Crear rol |
| PUT | `/admin/roles/{name}` | Actualizar rol |
| DELETE | `/admin/roles/{name}` | Eliminar rol |
| GET | `/admin/roles/{name}/menus` | Menús asignados al rol |
| PUT | `/admin/roles/{name}/menus` | Asignar menús al rol |
| GET | `/admin/roles/{name}/permissions` | Permisos del rol |
| PUT | `/admin/roles/{name}/permissions` | Actualizar permisos del rol |

---

### Gestión de menús

Base: `/admin/menus`

| Método | Ruta | Descripción |
|--------|------|-------------|
| GET | `/admin/menus` | Listar menús |
| GET | `/admin/menus/{id}` | Obtener menú |
| POST | `/admin/menus` | Crear menú |
| PUT | `/admin/menus/{id}` | Actualizar menú |
| DELETE | `/admin/menus/{id}` | Eliminar menú |
| PUT | `/admin/menus/reorder` | Reordenar menús |

---

### Gestión de acciones

Base: `/admin/actions`

| Método | Ruta | Descripción |
|--------|------|-------------|
| GET | `/admin/actions` | Listar acciones |
| GET | `/admin/actions/{id}` | Obtener acción |
| POST | `/admin/actions` | Crear acción |
| PUT | `/admin/actions/{id}` | Actualizar acción |
| DELETE | `/admin/actions/{id}` | Eliminar acción |

---

### Auditoría de ms-auth

#### GET /admin/audit — Listar eventos de auditoría

**Query params:**

| Param | Tipo | Descripción |
|---|---|---|
| `from` | string (ISO-8601) | Fecha inicio |
| `to` | string (ISO-8601) | Fecha fin |
| `username` | string | Filtrar por nombre de usuario |
| `userId` | string | Filtrar por ID de usuario |
| `eventTypes` | string[] | Tipos de evento |
| `modules` | string[] | Módulos |
| `serviceName` | string | Nombre del servicio |
| `ipAddress` | string | IP del cliente |
| `responseStatuses` | int[] | Códigos HTTP de respuesta |
| `q` | string | Búsqueda de texto libre |

**Response 200:** Page con header `X-Total-Count`.

**Curl ejemplo:**
```bash
curl -s "http://127.0.0.1:8080/services/mdqradminservice/admin/audit?from=2026-06-01T00:00:00Z&page=0&size=20" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq
```

---

#### GET /admin/audit/{id} — Detalle de evento de auditoría

```bash
curl -s "http://127.0.0.1:8080/services/mdqradminservice/admin/audit/123" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq
```

---

#### GET /admin/audit/event-types — Tipos de evento disponibles

**Response 200:** lista de strings con los tipos de evento configurados

---

#### GET /admin/audit/modules — Módulos disponibles

**Response 200:** lista de strings con los módulos configurados

---

#### GET /admin/audit/export — Exportar auditoría a CSV

Acepta los mismos query params que `GET /admin/audit`.

**Response 200:** archivo CSV con `Content-Disposition: attachment; filename=audit-export.csv`

```bash
curl -s "http://127.0.0.1:8080/services/mdqradminservice/admin/audit/export?from=2026-06-01T00:00:00Z" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -o audit-export.csv
```

---

## Tabla resumen de endpoints

| Método | Gateway path | Microservicio | Auth |
|--------|-------------|---------------|------|
| POST | `/oauth2/token` | Keycloak mdqr-partner | Sin auth |
| POST | `/partner/v1/qr/decode` | ms-base `/api/qr/decode` | JWT mdqr-partner |
| POST | `/partner/v1/qr/decode/file` | ms-base `/api/qr/decode/file` | JWT mdqr-partner |
| GET | `/services/mdqrbaseservice/api/qr/audits` | ms-base | JWT mdqr-admin |
| GET | `/services/mdqrbaseservice/api/certificates` | ms-base | JWT mdqr-admin |
| POST | `/services/mdqrbaseservice/api/certificates` | ms-base | JWT mdqr-admin |
| POST | `/services/mdqrbaseservice/api/certificates/upload-file` | ms-base | JWT mdqr-admin |
| POST | `/services/mdqrbaseservice/api/certificates/validate` | ms-base | JWT mdqr-admin |
| GET | `/services/mdqrbaseservice/api/certificates/{id}` | ms-base | JWT mdqr-admin |
| GET | `/services/mdqrbaseservice/api/certificates/{id}/pem` | ms-base | JWT mdqr-admin |
| GET | `/services/mdqrbaseservice/api/certificates/entity/{entityId}` | ms-base | JWT mdqr-admin |
| GET | `/services/mdqrbaseservice/api/certificates/expiring/{days}` | ms-base | JWT mdqr-admin |
| POST | `/services/mdqrbaseservice/api/certificates/{id}/activate` | ms-base | JWT mdqr-admin |
| POST | `/services/mdqrbaseservice/api/certificates/{id}/deactivate` | ms-base | JWT mdqr-admin |
| POST | `/services/mdqrbaseservice/api/certificates/{id}/revoke` | ms-base | JWT mdqr-admin |
| POST | `/services/mdqrbaseservice/api/certificates/{id}/replace` | ms-base | JWT mdqr-admin |
| GET | `/services/mdqrbaseservice/api/certificates/audits` | ms-base | JWT mdqr-admin |
| POST | `/services/mdqradminservice/admin/auth/login` | ms-auth | Sin auth |
| POST | `/services/mdqradminservice/admin/auth/refresh` | ms-auth | Sin auth |
| POST | `/services/mdqradminservice/admin/auth/logout` | ms-auth | JWT mdqr-admin |
| GET | `/services/mdqradminservice/admin/auth/me` | ms-auth | JWT mdqr-admin |
| GET | `/services/mdqradminservice/admin/auth/me/permissions` | ms-auth | JWT mdqr-admin |
| `*` | `/services/mdqradminservice/admin/users/**` | ms-auth | JWT mdqr-admin |
| `*` | `/services/mdqradminservice/admin/roles/**` | ms-auth | JWT mdqr-admin |
| `*` | `/services/mdqradminservice/admin/menus/**` | ms-auth | JWT mdqr-admin |
| `*` | `/services/mdqradminservice/admin/actions/**` | ms-auth | JWT mdqr-admin |
| GET | `/services/mdqradminservice/admin/audit` | ms-auth | JWT mdqr-admin |
| GET | `/services/mdqradminservice/admin/audit/{id}` | ms-auth | JWT mdqr-admin |
| GET | `/services/mdqradminservice/admin/audit/event-types` | ms-auth | JWT mdqr-admin |
| GET | `/services/mdqradminservice/admin/audit/modules` | ms-auth | JWT mdqr-admin |
| GET | `/services/mdqradminservice/admin/audit/export` | ms-auth | JWT mdqr-admin |
