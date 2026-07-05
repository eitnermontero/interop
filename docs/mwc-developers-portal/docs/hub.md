---
id: hub
sidebar_position: 2
title: HUB – QR Decrypt API
description: Guía técnica para consumir la API de desencriptación de códigos QR interoperables.
---

# HUB – Middleware de Decodificación QR

API REST para desencriptación de códigos QR interoperables y administración del sistema (usuarios, roles, certificados, auditoría).

:::tip Colección Postman
La colección importable para Postman está en `docs/HUB-Postman-QA.json` en la raíz del repositorio. Incluye todos los endpoints con ejemplos, variables de colección para QA y scripts que guardan el token automáticamente.
:::

## Servicios

| Servicio | Descripción | Puerto QA |
|----------|-------------|-----------|
| **Decrypt Service** | Desencriptación QR y gestión de certificados | `8094` |
| **Admin Service** | Usuarios, roles, menús, permisos y auditoría | `8093` |
| **Gateway** | Proxy de entrada (producción) | `8080` |

## Ambientes

| Ambiente | Decrypt Service | Admin Service | Keycloak |
|----------|----------------|---------------|----------|
| **QA** (`ssaqa001`) | `http://199.3.0.63:8094` | `http://199.3.0.63:8093` | `http://199.3.0.63:8180` |
| **Local** | `http://localhost:8081` | `http://localhost:8083` | `http://localhost:8180` |

### Swagger UI

- Admin Service: `http://199.3.0.63:8093/swagger-ui/index.html`
- Decrypt Service: `http://199.3.0.63:8094/swagger-ui/index.html`

---

## Autenticación

### Admin Service – Login con credenciales

El frontend y las herramientas admin obtienen un token JWT haciendo login con usuario y contraseña.

```http
POST http://199.3.0.63:8093/admin/auth/login
Content-Type: application/json

{
  "username": "admin@sintesis.com.bo",
  "password": "••••••••"
}
```

**Respuesta:**
```json
{
  "access_token":  "eyJhbGciOiJSUzI1NiIs...",
  "refresh_token": "eyJhbGciOiJIUzI1NiIs...",
  "expires_in":    300,
  "token_type":    "Bearer"
}
```

### Decrypt Service – Client Credentials (Keycloak)

Sistemas externos y procesos M2M obtienen un token directamente desde Keycloak.

```bash
TOKEN=$(curl -s \
  http://199.3.0.63:8180/realms/hub-admin/protocol/openid-connect/token \
  -d "grant_type=client_credentials" \
  -d "client_id=hubadminservice" \
  -d "client_secret=hubadminservice-secret" \
  | jq -r '.access_token')
```

### Uso del token

En todos los endpoints protegidos incluir el header:
```
Authorization: Bearer <access_token>
```

---

## Formato de errores (RFC 7807)

Todos los errores devuelven un objeto ProblemDetail estandarizado:

```json
{
  "type":            "https://api.sintesis.com.bo/problems/not-found",
  "title":           "Not Found",
  "status":          404,
  "detail":          "Certificado no encontrado: 42",
  "instance":        "/api/certificates/42",
  "timestamp":       "2026-06-19T13:42:09.037Z",
  "errorCode":       "NOT_FOUND",
  "technicalDetail": "Certificate not found: 42"
}
```

| Status | Significado |
|--------|-------------|
| `200` | OK |
| `201` | Creado |
| `204` | Sin contenido |
| `400` | Datos inválidos |
| `401` | No autenticado |
| `403` | Sin permisos |
| `404` | No encontrado |
| `409` | Conflicto / duplicado |
| `500` | Error interno |
| `502` | Error en servicio externo |

---

## Decrypt Service `:8094`

> **QA:** La seguridad está temporalmente abierta (`anyRequest().permitAll()`). En producción los endpoints requerirán roles específicos.

### QR – Desencriptación

Base: `/api/qr`

| Método | Endpoint | Descripción |
|--------|----------|-------------|
| `POST` | `/api/qr/decode` | Desencriptar QR por texto o imagen Base64 |
| `POST` | `/api/qr/decode/file` | Desencriptar QR desde archivo de imagen (multipart) |
| `GET`  | `/api/qr/audits` | Auditoría de desencriptaciones (paginado, con filtros) |

#### POST /api/qr/decode

Soporta dos modos de entrada:

**Modo `DECODED_DATA` — texto del QR ya leído**
```json
{
  "inputType":         "DECODED_DATA",
  "content":           "qERbt4N7AL96...==|1C302639F6F0...",
  "entityIdRequest":   "BANCO_UNION",
  "externalReference": "TRX-2026-001",
  "metadata":          { "canal": "ATM" }
}
```

**Modo `BASE64_IMAGE` — imagen del QR en Base64**
```json
{
  "inputType": "BASE64_IMAGE",
  "content":   "iVBORw0KGgoAAAANSUhEUgAA..."
}
```

**Campos del request:**

| Campo | Tipo | Req | Descripción |
|-------|------|-----|-------------|
| `inputType` | enum | ✓ | `DECODED_DATA` o `BASE64_IMAGE` |
| `content` | string | ✓ | Texto del QR o imagen Base64 |
| `entityIdRequest` | string | | ID de entidad solicitante (auditoría) |
| `externalReference` | string | | Referencia externa para trazabilidad |
| `metadata` | `map<string,string>` | | Metadatos adicionales |

:::info Formato del contenido QR
El contenido decodificado del QR debe tener el formato: `DATOS_ENCRIPTADOS|CODIGO_CERTIFICADO`

Ejemplo: `qERbt4N7AL96YxZo...base64...==|1C302639F6F0A4B2`
:::

**Respuesta 200:**

| Campo | Tipo | Descripción |
|-------|------|-------------|
| `logId` | string | ID del log de auditoría generado |
| `decryptedData` | string | Contenido desencriptado del QR |
| `certificateCode` | string | Código del certificado usado |
| `entityId` | string | ID de la entidad del certificado |
| `qrType` | string | Tipo inferido: `PAYMENT`, `INVOICE`, `ACCOUNT`, `UNKNOWN` |
| `processingTimeMs` | long | Tiempo de procesamiento en ms |
| `decryptedAt` | Instant | Timestamp de la operación (ISO-8601) |
| `fromCache` | boolean | Si el certificado se obtuvo desde caché Redis |

**Headers de respuesta:**

| Header | Descripción |
|--------|-------------|
| `X-Request-Id` | ID único del request |
| `X-Log-Id` | ID del log de auditoría |

#### POST /api/qr/decode/file

Enviar imagen como `multipart/form-data`:

| Parámetro | Tipo | Req | Descripción |
|-----------|------|-----|-------------|
| `file` | multipart | ✓ | Imagen del QR (JPG, PNG, GIF) |
| `entityIdRequest` | query string | | ID de entidad |
| `externalReference` | query string | | Referencia externa |

#### GET /api/qr/audits

| Parámetro | Tipo | Descripción |
|-----------|------|-------------|
| `keycloakClientId` | string | Filtrar por client ID |
| `certificateCode` | string | Filtrar por código de certificado |
| `entityId` | string | Filtrar por ID de entidad |
| `status` | string | `SUCCESS` o `ERROR` |
| `fromDate` / `toDate` | Instant | Rango de fechas (ISO-8601) |
| `page` / `size` | int | Paginación (default: 0 / 20) |
| `sort` / `order` | string | Ordenamiento (default: `createdDate` / `desc`) |

---

### Certificados

Base: `/api/certificates` · Requiere roles `ADMIN` u `OPERATOR`.

| Método | Endpoint | Rol mínimo | Descripción |
|--------|----------|------------|-------------|
| `GET` | `/api/certificates` | OPERATOR | Listar certificados activos (paginado) |
| `POST` | `/api/certificates` | ADMIN | Subir nuevo certificado (JSON) |
| `POST` | `/api/certificates/upload-file` | ADMIN | Subir certificado (multipart) |
| `POST` | `/api/certificates/validate` | OPERATOR | Validar PEM sin guardar |
| `GET` | `/api/certificates/{id}` | OPERATOR | Detalle del certificado |
| `GET` | `/api/certificates/{id}/pem` | OPERATOR | Descargar contenido PEM |
| `GET` | `/api/certificates/entity/{entityId}` | OPERATOR | Certificados de una entidad |
| `GET` | `/api/certificates/expiring/{days}` | OPERATOR | Certificados que expiran en X días |
| `POST` | `/api/certificates/{id}/activate` | ADMIN | Activar certificado |
| `POST` | `/api/certificates/{id}/deactivate` | ADMIN | Desactivar certificado |
| `POST` | `/api/certificates/{id}/revoke` | ADMIN | Revocar (irreversible) |
| `POST` | `/api/certificates/{id}/replace` | ADMIN | Reemplazar con nueva versión |
| ~~`GET`~~ | ~~`/api/certificates/audits`~~ | ~~AUDITOR~~ | ~~Auditoría de certificados~~ *(pendiente — siempre devuelve `[]`)* |

#### POST /api/certificates

```json
{
  "pemContent":         "-----BEGIN CERTIFICATE-----\nMIIDxT....\n-----END CERTIFICATE-----",
  "entityId":           "BANCO_UNION",
  "entityName":         "Banco Unión S.A.",
  "description":        "Certificado producción 2026",
  "tags":               ["produccion", "rsa2048"],
  "notificationEmails": ["ops@banco.com.bo"]
}
```

#### POST /api/certificates/{id}/revoke

:::danger Operación irreversible
Un certificado revocado queda en estado `REVOKED` y no puede reactivarse. Use `/replace` si necesita actualizar a una nueva versión.
:::

```json
{
  "reason": "Compromiso de clave privada detectado"
}
```

#### POST /api/certificates/{id}/replace

```json
{
  "newPemContent": "-----BEGIN CERTIFICATE-----\n...",
  "changeReason":  "Renovación anual de certificado"
}
```

---

## Admin Service `:8093`

### Auth / Sesión

Base: `/admin/auth` · Los endpoints de login, refresh y logout son **públicos** (no requieren token).

| Método | Endpoint | Auth | Descripción |
|--------|----------|------|-------------|
| `POST` | `/admin/auth/login` | pública | Login con usuario y contraseña |
| `POST` | `/admin/auth/refresh` | pública | Renovar token con refresh_token |
| `POST` | `/admin/auth/logout` | pública | Invalidar sesión |
| `GET` | `/admin/auth/me` | JWT | Perfil del usuario autenticado |
| `GET` | `/admin/auth/me/permissions` | JWT | Árbol de permisos del usuario actual |

#### POST /admin/auth/refresh

```json
{ "refreshToken": "eyJhbGciOiJIUzI1NiIs..." }
```

#### POST /admin/auth/logout

```json
{ "refreshToken": "eyJhbGciOiJIUzI1NiIs..." }
```

---

### Usuarios

Base: `/admin/users` · Gestión de usuarios en Keycloak.

| Método | Endpoint | Permiso | Descripción |
|--------|----------|---------|-------------|
| `GET` | `/admin/users` | USUARIOS·READ | Listar (params: `search`, `enabled`, `page`, `size`) |
| `GET` | `/admin/users/{userId}` | USUARIOS·READ | Detalle |
| `POST` | `/admin/users` | USUARIOS·CREATE | Crear usuario |
| `PUT` | `/admin/users/{userId}` | USUARIOS·UPDATE | Actualizar |
| `DELETE` | `/admin/users/{userId}` | USUARIOS·DELETE | Eliminar |
| `PUT` | `/admin/users/{userId}/password` | USUARIOS·UPDATE | Cambiar contraseña |
| `PUT` | `/admin/users/{userId}/status` | USUARIOS·UPDATE | Activar / desactivar |
| `GET` | `/admin/users/{userId}/roles` | USUARIOS·READ | Roles del usuario |
| `PUT` | `/admin/users/{userId}/roles` | USUARIOS·UPDATE | Reemplazar roles |
| `POST` | `/admin/users/{userId}/send-reset` | USUARIOS·UPDATE | Enviar email de reset |

**POST /admin/users:**
```json
{
  "username":  "jperez",
  "email":     "j.perez@banco.com.bo",
  "firstName": "Juan",
  "lastName":  "Pérez",
  "password":  "Temp1234!",
  "temporary": true,
  "enabled":   true,
  "roles":     ["ADMIN"]
}
```

---

### Roles

Base: `/admin/roles`

| Método | Endpoint | Permiso | Descripción |
|--------|----------|---------|-------------|
| `GET` | `/admin/roles` | ROLES·READ | Listar todos |
| `GET` | `/admin/roles/{name}` | ROLES·READ | Detalle |
| `POST` | `/admin/roles` | ROLES·CREATE | Crear rol |
| `PUT` | `/admin/roles/{name}` | ROLES·UPDATE | Actualizar |
| `DELETE` | `/admin/roles/{name}` | ROLES·DELETE | Eliminar |
| `GET` | `/admin/roles/{name}/menus` | autenticado | Menús del rol |
| `PUT` | `/admin/roles/{name}/menus` | autenticado | Actualizar menús |
| `GET` | `/admin/roles/{name}/permissions` | PERMISOS·READ | Permisos del rol |
| `PUT` | `/admin/roles/{name}/permissions` | PERMISOS·UPDATE | Asignar permisos |

---

### Menús

Base: `/admin/menus`

| Método | Endpoint | Permiso | Descripción |
|--------|----------|---------|-------------|
| `GET` | `/admin/menus` | MENUS·READ | Árbol completo |
| `GET` | `/admin/menus/{id}` | MENUS·READ | Detalle |
| `POST` | `/admin/menus` | MENUS·CREATE | Crear ítem |
| `PUT` | `/admin/menus/{id}` | MENUS·UPDATE | Actualizar |
| `DELETE` | `/admin/menus/{id}` | MENUS·DELETE | Eliminar |
| `PUT` | `/admin/menus/reorder` | MENUS·UPDATE | Reordenar ítems |

---

### Acciones

Base: `/admin/actions` · Permisos granulares por módulo.

| Método | Endpoint | Permiso | Descripción |
|--------|----------|---------|-------------|
| `GET` | `/admin/actions` | ACCIONES·READ | Listar acciones |
| `GET` | `/admin/actions/{id}` | ACCIONES·READ | Detalle |
| `POST` | `/admin/actions` | ACCIONES·CREATE | Crear |
| `PUT` | `/admin/actions/{id}` | ACCIONES·UPDATE | Actualizar |
| `DELETE` | `/admin/actions/{id}` | ACCIONES·DELETE | Eliminar |

---

### Auditoría Admin

Base: `/admin/audit`

| Método | Endpoint | Permiso | Descripción |
|--------|----------|---------|-------------|
| `GET` | `/admin/audit` | AUDITORIA·READ | Buscar logs (paginado) |
| `GET` | `/admin/audit/{id}` | AUDITORIA·READ | Detalle de un log |
| `GET` | `/admin/audit/event-types` | AUDITORIA·READ | Tipos de evento disponibles |
| `GET` | `/admin/audit/modules` | AUDITORIA·READ | Módulos disponibles |
| `GET` | `/admin/audit/export` | AUDITORIA·EXPORT | Exportar CSV (máx. 50 000 filas) |

**GET /admin/audit — Filtros:**

| Parámetro | Tipo | Descripción |
|-----------|------|-------------|
| `from` / `to` | Instant | Rango de fechas (ISO-8601) |
| `username` | string | Filtrar por nombre de usuario |
| `userId` | string | ID de usuario Keycloak |
| `eventTypes` | string[] | CREATE, UPDATE, DELETE, EXPORT… |
| `modules` | string[] | USUARIOS, ROLES, MENUS, AUDITORIA… |
| `q` | string | Búsqueda full-text |
| `page` / `size` | int | Paginación (default: 0 / 50) |
