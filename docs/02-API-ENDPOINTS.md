# 02 - API Endpoints

> ⚠️ **Documento parcialmente desactualizado** (contiene contenido legacy pre-ADR-0004/rename 2026-07-03).
> Fuente de verdad actual: `CLAUDE.md` y `docs/adr/` (ADR-0005/0006/0007).

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

### Token partner (vía gateway — realm hub-partner)

```bash
PARTNER_TOKEN=$(curl -s -X POST http://127.0.0.1:8080/oauth2/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials&client_id=unilink-api&client_secret=unilink-api-secret" \
  | jq -r '.access_token')
```

### Token admin (directo a Keycloak — realm hub-admin)

```bash
ADMIN_TOKEN=$(curl -s -X POST http://127.0.0.1:8180/realms/hub-admin/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password&client_id=hubadminservice&client_secret=hubadminservice-secret&username=admin&password=admin" \
  | jq -r '.access_token')
```

---

## hub-ms-base — Motor inbound genérico

Accesible para partners a través del gateway en `/partner/v1/` (JWT realm `hub-partner`),
reescrito a `/api/` en el microservicio. Los endpoints de negocio QR/certificados del
producto anterior fueron **eliminados** (ver `docs/adr/ADR-0004-eliminacion-qr.md`);
hoy ms-base expone un **dispatcher genérico** que valida el payload contra el contrato
declarativo del producto y lo reenvía al sistema interno destino.

### POST /api/inbound/{product}/{version} — Crear recurso

Ejemplo de producto registrado: `CASO_PENAL` / `v1`.

**Headers relevantes:**

| Header | Requerido | Descripción |
|---|---|---|
| `Authorization` | Sí | Bearer JWT realm `hub-partner` (scope `https://api.sintesis.com.bo/caso.penal`) |
| `X-Partner-Id` | No | Identificador lógico del partner |
| `X-Correlation-ID` | No | Se genera uno (UUID) si no se envía; siempre se devuelve en la respuesta |
| `X-Idempotency-Key` | No | Clave de idempotencia de la operación |

**Request:** JSON libre validado contra el contrato del producto (`ContractRegistry`).

**Response (envelope `ApiResponse`):**
```json
{
    "success": true,
    "status": 201,
    "message": "...",
    "data": { },
    "error": null
}
```

En error, `error` contiene `{ "code", "detail", "violations": [{ "field", "message" }] }`.

**Errores:** `403 PRODUCT_NOT_AUTHORIZED` (producto/versión no registrado),
`400 VALIDATION_ERROR` (payload no cumple el contrato), `FORWARD_ERROR`
(fallo al reenviar al sistema interno; respeta el status del destino).

**Curl ejemplo (partner):**
```bash
curl -s -X POST http://127.0.0.1:8080/partner/v1/inbound/CASO_PENAL/v1 \
  -H "Authorization: Bearer $PARTNER_TOKEN" \
  -H "Content-Type: application/json" \
  -H "X-Correlation-ID: $(uuidgen)" \
  -d '{ "...payload del caso penal..." : "..." }' | jq
```

---

### PATCH /api/inbound/{product}/{version}/{id} — Editar recurso

Convención de routing: resuelve el contrato `{product}_EDITAR/{version}`
(p.ej. `CASO_PENAL_EDITAR/v1`). El `{id}` del path (entero) se inyecta en el payload
bajo el campo `resourceIdField` del contrato; si el body lo incluye, el valor del
path prevalece.

**Errores adicionales:** `400 INVALID_RESOURCE_ID` si `{id}` no es un entero.

**Curl ejemplo (partner):**
```bash
curl -s -X PATCH http://127.0.0.1:8080/partner/v1/inbound/CASO_PENAL/v1/12345 \
  -H "Authorization: Bearer $PARTNER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{ "...campos a editar..." : "..." }' | jq
```

---

Toda transacción inbound queda registrada en `hub_audit_log` (hash SHA-256 de
request/response, cadena `prev_hash`) y genera el evento de medición vía `outbox_event`
en la misma transacción (ver `11-AUDIT-LOG.md`).

---

## hub-ms-auth — Autenticación y RBAC

Accesible a través del gateway en `/services/hubadminservice/` (JWT realm `hub-admin`).

---

### Autenticación de usuarios admin

#### POST /admin/auth/login

```bash
curl -s -X POST http://127.0.0.1:8080/services/hubadminservice/admin/auth/login \
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
curl -s http://127.0.0.1:8080/services/hubadminservice/admin/auth/me \
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
curl -s http://127.0.0.1:8080/services/hubadminservice/admin/auth/me/permissions \
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
curl -s "http://127.0.0.1:8080/services/hubadminservice/admin/users?page=0&size=20" \
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
curl -s "http://127.0.0.1:8080/services/hubadminservice/admin/audit?from=2026-06-01T00:00:00Z&page=0&size=20" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq
```

---

#### GET /admin/audit/{id} — Detalle de evento de auditoría

```bash
curl -s "http://127.0.0.1:8080/services/hubadminservice/admin/audit/123" \
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
curl -s "http://127.0.0.1:8080/services/hubadminservice/admin/audit/export?from=2026-06-01T00:00:00Z" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -o audit-export.csv
```

---

## Tabla resumen de endpoints

| Método | Gateway path | Microservicio | Auth |
|--------|-------------|---------------|------|
| POST | `/oauth2/token` | Keycloak hub-partner | Sin auth |
| POST | `/partner/v1/inbound/{product}/{version}` | ms-base `/api/inbound/{product}/{version}` | JWT hub-partner |
| PATCH | `/partner/v1/inbound/{product}/{version}/{id}` | ms-base `/api/inbound/{product}/{version}/{id}` | JWT hub-partner |
| POST | `/services/hubadminservice/admin/auth/login` | ms-auth | Sin auth |
| POST | `/services/hubadminservice/admin/auth/refresh` | ms-auth | Sin auth |
| POST | `/services/hubadminservice/admin/auth/logout` | ms-auth | JWT hub-admin |
| GET | `/services/hubadminservice/admin/auth/me` | ms-auth | JWT hub-admin |
| GET | `/services/hubadminservice/admin/auth/me/permissions` | ms-auth | JWT hub-admin |
| `*` | `/services/hubadminservice/admin/users/**` | ms-auth | JWT hub-admin |
| `*` | `/services/hubadminservice/admin/roles/**` | ms-auth | JWT hub-admin |
| `*` | `/services/hubadminservice/admin/menus/**` | ms-auth | JWT hub-admin |
| `*` | `/services/hubadminservice/admin/actions/**` | ms-auth | JWT hub-admin |
| GET | `/services/hubadminservice/admin/audit` | ms-auth | JWT hub-admin |
| GET | `/services/hubadminservice/admin/audit/{id}` | ms-auth | JWT hub-admin |
| GET | `/services/hubadminservice/admin/audit/event-types` | ms-auth | JWT hub-admin |
| GET | `/services/hubadminservice/admin/audit/modules` | ms-auth | JWT hub-admin |
| GET | `/services/hubadminservice/admin/audit/export` | ms-auth | JWT hub-admin |
