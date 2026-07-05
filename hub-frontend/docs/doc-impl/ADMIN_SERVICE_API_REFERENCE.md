# MWC Admin Service - Complete API Reference

**Base URL:** `http://localhost:8083`

**Authentication:** All endpoints require `Authorization: Bearer <JWT_TOKEN>` header

---

## Users Management API

### List Users
```http
GET /api/v1/admin/users?page=0&size=20&search=&enabled=
```
**Required Permission:** `USUARIOS:READ`
**Parameters:**
- `page` (int, default: 0) - Page number
- `size` (int, default: 20) - Items per page
- `search` (string) - Search by name/email
- `enabled` (boolean) - Filter by status

**Response:** `PageResponse<UserDto>`

### Get User by ID
```http
GET /api/v1/admin/users/{userId}
```
**Required Permission:** `USUARIOS:READ`
**Response:** `UserDto`

### Create User
```http
POST /api/v1/admin/users
Content-Type: application/json

{
  "username": "string",
  "email": "string@example.com",
  "firstName": "string",
  "lastName": "string"
}
```
**Required Permission:** `USUARIOS:CREATE`
**Response:** `201 Created` with `UserDto`

### Update User
```http
PUT /api/v1/admin/users/{userId}
Content-Type: application/json

{
  "email": "string@example.com",
  "firstName": "string",
  "lastName": "string"
}
```
**Required Permission:** `USUARIOS:UPDATE`

### Delete User
```http
DELETE /api/v1/admin/users/{userId}
```
**Required Permission:** `USUARIOS:DELETE`
**Response:** `204 No Content`

### Reset User Password
```http
PUT /api/v1/admin/users/{userId}/password
Content-Type: application/json

{
  "password": "newPassword123",
  "temporary": false
}
```
**Required Permission:** `USUARIOS:UPDATE`

### Update User Status
```http
PUT /api/v1/admin/users/{userId}/status
Content-Type: application/json

{
  "enabled": true
}
```
**Required Permission:** `USUARIOS:UPDATE`

### Get User Roles
```http
GET /api/v1/admin/users/{userId}/roles
```
**Required Permission:** `USUARIOS:READ`
**Response:** `List<String>` - Array of role names

### Update User Roles
```http
PUT /api/v1/admin/users/{userId}/roles
Content-Type: application/json

{
  "roles": ["ADMIN", "OPERATOR"]
}
```
**Required Permission:** `USUARIOS:UPDATE`

### Send Password Reset Email
```http
POST /api/v1/admin/users/{userId}/send-reset
```
**Required Permission:** `USUARIOS:UPDATE`
**Response:** `204 No Content`

---

## Roles Management API

### List All Roles
```http
GET /api/v1/admin/roles
```
**Required Permission:** `ROLES:READ`
**Response:** `List<RoleDto>`

### Get Role by Name
```http
GET /api/v1/admin/roles/{roleName}
```
**Required Permission:** `ROLES:READ`
**Response:** `RoleDto`

### Create Role
```http
POST /api/v1/admin/roles
Content-Type: application/json

{
  "name": "OPERATOR",
  "description": "Operator role"
}
```
**Required Permission:** `ROLES:CREATE`
**Response:** `201 Created` with `RoleDto`

### Update Role
```http
PUT /api/v1/admin/roles/{roleName}
Content-Type: application/json

{
  "description": "Updated description"
}
```
**Required Permission:** `ROLES:UPDATE`

### Delete Role
```http
DELETE /api/v1/admin/roles/{roleName}
```
**Required Permission:** `ROLES:DELETE`
**Response:** `204 No Content`

---

## Permissions API

### Get Role Permissions
```http
GET /api/v1/admin/roles/{roleName}/permissions
```
**Required Permission:** `PERMISOS:READ`
**Response:** `RolePermissionsResponse` - Matrix of menu/action grants

### Set Role Permissions
```http
PUT /api/v1/admin/roles/{roleName}/permissions
Content-Type: application/json

{
  "permissions": {
    "USUARIOS": ["READ", "CREATE", "UPDATE", "DELETE"],
    "ROLES": ["READ", "CREATE"],
    "MENUS": ["READ"]
  }
}
```
**Required Permission:** `PERMISOS:UPDATE`
**Response:** `204 No Content`

---

## Actions API

### List All Actions
```http
GET /api/v1/admin/actions
```
**Required Permission:** `ACCIONES:READ`
**Response:** `List<ActionDto>`
```json
{
  "id": 1,
  "code": "READ",
  "name": "Consultar",
  "description": "Permite consultar/visualizar el recurso"
}
```

### Get Action by ID
```http
GET /api/v1/admin/actions/{actionId}
```
**Required Permission:** `ACCIONES:READ`
**Response:** `ActionDto`

### Create Action
```http
POST /api/v1/admin/actions
Content-Type: application/json

{
  "code": "CUSTOM_ACTION",
  "name": "Custom Operation",
  "description": "Description of the action"
}
```
**Required Permission:** `ACCIONES:CREATE`
**Response:** `201 Created` with `ActionDto`

### Update Action
```http
PUT /api/v1/admin/actions/{actionId}
Content-Type: application/json

{
  "name": "Updated Name",
  "description": "Updated description"
}
```
**Required Permission:** `ACCIONES:UPDATE`

### Delete Action
```http
DELETE /api/v1/admin/actions/{actionId}
```
**Required Permission:** `ACCIONES:DELETE`
**Response:** `204 No Content`

---

## Menu API

### Get Menu Tree
```http
GET /api/v1/admin/menus
```
**Required Permission:** `MENUS:READ`
**Response:** `List<MenuDto>` - Hierarchical menu structure

### Get Menu Item by ID
```http
GET /api/v1/admin/menus/{menuId}
```
**Required Permission:** `MENUS:READ`
**Response:** `MenuDto`

### Create Menu Item
```http
POST /api/v1/admin/menus
Content-Type: application/json

{
  "code": "NEW_MENU",
  "name": "New Menu Item",
  "icon": "dashboard",
  "route": "/new-menu",
  "parentId": null,
  "orderIndex": 10
}
```
**Required Permission:** `MENUS:CREATE`
**Response:** `201 Created` with `MenuDto`

### Update Menu Item
```http
PUT /api/v1/admin/menus/{menuId}
Content-Type: application/json

{
  "name": "Updated Name",
  "icon": "updated-icon",
  "route": "/updated-route"
}
```
**Required Permission:** `MENUS:UPDATE`

### Delete Menu Item
```http
DELETE /api/v1/admin/menus/{menuId}
```
**Required Permission:** `MENUS:DELETE`
**Response:** `204 No Content`

### Reorder Menu Items
```http
PUT /api/v1/admin/menus/reorder
Content-Type: application/json

{
  "menuOrders": [
    {"id": 1, "orderIndex": 10},
    {"id": 2, "orderIndex": 20},
    {"id": 3, "orderIndex": 30}
  ]
}
```
**Required Permission:** `MENUS:UPDATE`
**Response:** `204 No Content`

---

## Audit Log API

### Search Audit Logs
```http
GET /api/v1/admin/audit?from=2026-01-01T00:00:00Z&to=2026-12-31T23:59:59Z&username=&userId=&eventTypes=&modules=&serviceName=&ipAddress=&responseStatuses=&page=0&size=50
```
**Required Permission:** `AUDITORIA:READ`
**Parameters:**
- `from` (Instant) - Start date
- `to` (Instant) - End date  
- `username` (string) - Filter by username
- `userId` (string) - Filter by user ID
- `eventTypes` (List<String>) - Event types (READ, CREATE, UPDATE, DELETE, etc.)
- `modules` (List<String>) - Module names
- `serviceName` (string) - Service name
- `ipAddress` (string) - Client IP address
- `responseStatuses` (List<Integer>) - HTTP status codes
- `page` (int, default: 0)
- `size` (int, default: 50)

**Response:** `Page<AuditLogDto>`

### Get Audit Log by ID
```http
GET /api/v1/admin/audit/{auditLogId}
```
**Required Permission:** `AUDITORIA:READ`
**Response:** `AuditLogDto`

### Get Distinct Event Types
```http
GET /api/v1/admin/audit/event-types
```
**Required Permission:** `AUDITORIA:READ`
**Response:** `List<String>` - Unique event types in database

### Get Distinct Modules
```http
GET /api/v1/admin/audit/modules
```
**Required Permission:** `AUDITORIA:READ`
**Response:** `List<String>` - Unique module names in database

### Export Audit Logs to CSV
```http
GET /api/v1/admin/audit/export?from=...&to=...&username=...
```
**Required Permission:** `AUDITORIA:EXPORT`
**Response:** CSV file download
**Headers:**
```
Content-Type: text/csv;charset=UTF-8
Content-Disposition: attachment; filename="audit-2026-05-19T17-50-00Z.csv"
```

---

## Response Formats

### Success Response
```json
{
  "id": 123,
  "code": "VALUE",
  "name": "Display Name",
  ...additional fields...
}
```

### Error Response
```json
{
  "type": "https://middleware-core.sintesis.com.bo/problems/...",
  "title": "Error Type",
  "status": 400,
  "detail": "Error description",
  "errorCode": "ERROR_CODE",
  "timestamp": "2026-05-19T17:50:00Z"
}
```

### Paginated Response
```json
{
  "content": [
    {...},
    {...}
  ],
  "page": 0,
  "size": 20,
  "totalElements": 100,
  "totalPages": 5
}
```

---

## HTTP Status Codes

| Code | Meaning |
|------|---------|
| 200 | OK - Successful GET/PUT |
| 201 | Created - Successful POST |
| 204 | No Content - Successful DELETE/PUT with no response body |
| 400 | Bad Request - Invalid input |
| 401 | Unauthorized - Missing/invalid token |
| 403 | Forbidden - Insufficient permissions |
| 404 | Not Found - Resource doesn't exist |
| 409 | Conflict - Duplicate resource |
| 500 | Internal Server Error |
| 502 | Bad Gateway - Keycloak unavailable |

---

## Common Issues

### 401 Unauthorized
- Token is missing or invalid
- Token has expired (get a new one)
- Service not running

### 403 Forbidden / Access Denied
- User doesn't have required role/permission
- Check `@PreAuthorize` on the endpoint
- Verify SUPER_ADMIN role is assigned

### 502 Bad Gateway
- Keycloak service is down
- Check: `http://localhost:8180/admin/realms/middleware-core`

### 404 Not Found
- Resource ID is incorrect
- Check entity exists in database

---

**Last Updated:** 2026-05-19  
**Version:** 1.0.0
