# 10 - Admin Service (mdqr-ms-auth)

## Responsabilidad

El módulo `mdqr-ms-auth` (puerto 8083) gestiona tres dominios interrelacionados:

1. **Autenticación**: delegar login, refresh y logout a Keycloak, devolver tokens al cliente.
2. **RBAC granular**: mapeo de roles Keycloak a menús de aplicación y acciones atómicas.
3. **Gestión de usuarios**: CRUD de usuarios en Keycloak via Admin API, incluyendo asignación de roles, reset de contraseña y activación/desactivación.
4. **Auditoría**: registro de todas las acciones del panel admin.

---

## Keycloak

- **Realm**: `mdqr-admin`
- **Client**: `mdqradminservice`
- **Client secret**: `mdqradminservice-secret`
- **Tipo**: `confidential`, `service-accounts-enabled: true`
- Los usuarios viven en Keycloak, no en la base de datos local.
- El servicio obtiene un token de service account para llamar a la Keycloak Admin API.

---

## Modelo de Datos

Base de datos: `mdqr_auth`, schema: `admin`.

```
KEYCLOAK (Realm: mdqr-admin)
  User                         Role (Realm Role)
  ─────                        ─────────────────
  id (UUID)                    name (e.g. "ADMIN")
  username                     description
  email
  enabled
  credentials

        │ (role name como bridge — string, no FK)
        ▼

PostgreSQL (schema admin)
  menu                           action
  ────────────────────────       ──────────────────
  id           PK                id           PK
  code         UNIQUE            code         UNIQUE
  name                           name
  icon                           description
  route
  parent_id    FK → menu.id
  order_index
  is_active

  role_menu_action
  ─────────────────────────────────────────────────
  keycloak_role_name  VARCHAR(100)   ← nombre del rol en Keycloak
  menu_id             FK → menu.id
  action_id           FK → action.id
  is_granted          BOOLEAN
  PK (keycloak_role_name, menu_id, action_id)

  audit_log
  ─────────────────────────────────────────────────
  id, event_time, event_type, module, option_code
  user_id, username, roles[], ip_address
  http_method, endpoint, response_status, duration_ms
  details (JSONB)
```

`role_menu_action.keycloak_role_name` es un string libre que referencia el nombre del rol en Keycloak. El servicio no replica roles. Si un rol se elimina en Keycloak, queda como huerfano en el mapeo.

### Acciones base del sistema

| code | name |
|---|---|
| `READ` | Consultar |
| `CREATE` | Crear |
| `UPDATE` | Editar |
| `DELETE` | Eliminar |
| `EXPORT` | Exportar |

### Estructura de menús inicial

```
DASHBOARD             /dashboard
CERTIFICADOS          /certificados
  LISTA               /certificados/lista
  UPLOAD              /certificados/upload
DESENCRIPTACION       /desencriptacion
ADMINISTRACION        /admin
  USUARIOS            /admin/usuarios
  ROLES               /admin/roles
  MENUS               /admin/menus
  PERMISOS            /admin/permisos
AUDITORIA             /auditoria
  QR                  /auditoria/qr
  CERTIFICADOS        /auditoria/certificados
  ADMIN               /auditoria/admin
```

---

## Endpoints

Todos los endpoints se exponen vía gateway bajo `/services/mdqradminservice/admin/**` con JWT del realm `mdqr-admin`.

### Autenticacion

| Metodo | Ruta | Descripcion |
|---|---|---|
| `POST` | `/admin/auth/login` | Obtener access_token y refresh_token de Keycloak |
| `POST` | `/admin/auth/refresh` | Refrescar el token |
| `POST` | `/admin/auth/logout` | Invalidar token en Keycloak |
| `GET` | `/admin/auth/me` | Datos del usuario autenticado |
| `GET` | `/admin/auth/me/permissions` | Arbol de menus y acciones segun roles del JWT |

### Usuarios

| Metodo | Ruta | Descripcion |
|---|---|---|
| `GET` | `/admin/users` | Listar usuarios (params: search, page, size, enabled) |
| `POST` | `/admin/users` | Crear usuario en Keycloak |
| `GET` | `/admin/users/{id}` | Detalle de usuario |
| `PUT` | `/admin/users/{id}` | Actualizar atributos |
| `DELETE` | `/admin/users/{id}` | Eliminar usuario |
| `PUT` | `/admin/users/{id}/roles` | Reemplazar roles asignados |
| `PUT` | `/admin/users/{id}/password` | Reset de contrasena |
| `PUT` | `/admin/users/{id}/status` | Activar o desactivar usuario |

### Roles

| Metodo | Ruta | Descripcion |
|---|---|---|
| `GET` | `/admin/roles` | Listar realm roles de Keycloak |
| `POST` | `/admin/roles` | Crear rol en Keycloak |
| `GET` | `/admin/roles/{name}` | Detalle de rol |
| `PUT` | `/admin/roles/{name}` | Actualizar descripcion |
| `DELETE` | `/admin/roles/{name}` | Eliminar rol (limpia role_menu_action local) |
| `GET` | `/admin/roles/{name}/menus` | Menus asignados al rol |
| `GET` | `/admin/roles/{name}/permissions` | Matriz de permisos del rol |
| `PUT` | `/admin/roles/{name}/permissions` | Reemplazar permisos del rol |

### Menus

| Metodo | Ruta | Descripcion |
|---|---|---|
| `GET` | `/admin/menus` | Arbol completo de menus |
| `POST` | `/admin/menus` | Crear menu |
| `GET` | `/admin/menus/{id}` | Detalle de menu |
| `PUT` | `/admin/menus/{id}` | Actualizar menu |
| `DELETE` | `/admin/menus/{id}` | Eliminar menu |
| `PUT` | `/admin/menus/reorder` | Reordenar nodos |

### Acciones

| Metodo | Ruta | Descripcion |
|---|---|---|
| `GET` | `/admin/actions` | Listar acciones |
| `POST` | `/admin/actions` | Crear accion |
| `GET` | `/admin/actions/{id}` | Detalle de accion |
| `PUT` | `/admin/actions/{id}` | Actualizar accion |
| `DELETE` | `/admin/actions/{id}` | Eliminar accion |

### Auditoria

| Metodo | Ruta | Descripcion |
|---|---|---|
| `GET` | `/admin/audit` | Consultar logs con filtros (from, to, username, eventTypes[], modules[], q) |
| `GET` | `/admin/audit/export` | Exportar resultado filtrado como CSV |

---

## Formatos de Respuesta

### LoginResponse

```json
{
  "access_token": "eyJh...",
  "refresh_token": "eyJh...",
  "expires_in": 300,
  "token_type": "Bearer"
}
```

### MeResponse

```json
{
  "id": "uuid",
  "username": "admin",
  "email": "admin@example.com",
  "firstName": "Admin",
  "lastName": "User",
  "fullName": "Admin User",
  "roles": ["ADMIN", "OPERATOR"]
}
```

### PermissionsResponse (GET /admin/auth/me/permissions)

```json
{
  "user": {
    "id": "a3f8b1c4-...",
    "username": "operador",
    "email": "operador@sintesis.com.bo",
    "roles": ["OPERATOR"]
  },
  "menus": [
    {
      "code": "DASHBOARD",
      "name": "Dashboard",
      "route": "/dashboard",
      "icon": "dashboard",
      "actions": ["READ"],
      "children": []
    },
    {
      "code": "CERTIFICADOS",
      "name": "Certificados",
      "route": "/certificados",
      "icon": "verified",
      "actions": ["READ"],
      "children": [
        {
          "code": "LISTA",
          "name": "Lista",
          "route": "/certificados/lista",
          "icon": "list",
          "actions": ["READ", "EXPORT"],
          "children": []
        }
      ]
    }
  ]
}
```

La consulta que alimenta el arbol:

```sql
SELECT m.code AS menu_code, a.code AS action_code
FROM admin.role_menu_action rma
JOIN admin.menu   m ON m.id = rma.menu_id
JOIN admin.action a ON a.id = rma.action_id
WHERE rma.keycloak_role_name IN (:rolesFromJwt)
  AND rma.is_granted = TRUE
  AND m.is_active = TRUE;
```

El resultado se agrupa por menu, se hace union de acciones para usuarios con multiples roles y se construye el arbol parent/child.

---

## Reglas de Acceso RBAC

Cada endpoint verifica que el usuario autenticado tenga la accion necesaria en el modulo correspondiente via `@PreAuthorize`:

```java
@GetMapping
@PreAuthorize("@permissionService.hasAction('USUARIOS', 'READ')")
public List<UserDto> listUsers(...) { ... }

@PostMapping
@PreAuthorize("@permissionService.hasAction('USUARIOS', 'CREATE')")
public ResponseEntity<Map<String, String>> createUser(...) { ... }
```

El `PermissionService` extrae los roles del claim `realm_access.roles` del JWT y consulta `role_menu_action`.

---

## Flujo de Login y Carga de Permisos

```
Cliente (navegador)
    │
    ├─ 1. POST /admin/auth/login { username, password }
    │       └─► mdqr-gateway
    │               └─► mdqr-ms-auth
    │                       └─► Keycloak token endpoint (grant_type=password)
    │                              realm mdqr-admin
    │                       ←── { access_token, refresh_token, expires_in }
    │
    ├─ 2. GET /admin/auth/me/permissions
    │   Authorization: Bearer {access_token}
    │       └─► mdqr-ms-auth
    │               ├─► Extrae realm_access.roles del JWT
    │               ├─► SELECT desde role_menu_action WHERE keycloak_role_name IN (...)
    │               └─► Construye arbol de menus + acciones
    │
    └─ El frontend usa el arbol para:
       - Renderizar solo los menus permitidos
       - Habilitar/deshabilitar botones segun las acciones de cada menu
```

---

## Gestion de Usuarios via Keycloak Admin API

Cuando se crea un usuario, el servicio ejecuta en secuencia:

1. `POST /admin/realms/mdqr-admin/users` — crear el usuario
2. `PUT /admin/realms/mdqr-admin/users/{id}/reset-password` — establecer contrasena temporal
3. `POST /admin/realms/mdqr-admin/users/{id}/role-mappings/realm` — asignar roles

Cuando se reasignan roles:

1. Lee los role mappings actuales del usuario
2. Calcula el diff (a remover, a agregar)
3. Llama `DELETE` y `POST` sobre `/role-mappings/realm`

El service account del cliente `mdqradminservice` requiere los siguientes roles de `realm-management` en Keycloak:

- `manage-users`
- `view-users`
- `query-users`
- `manage-realm`
- `view-realm`

---

## Configuracion Spring (perfil local)

```yaml
server:
  port: 8083

spring:
  application:
    name: mdqradminservice
  datasource:
    url: jdbc:postgresql://${DB_HOST:127.0.0.1}:${DB_PORT:5432}/mdqr_auth
  jpa:
    properties:
      hibernate:
        default_schema: admin
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://${KEYCLOAK_HOST:127.0.0.1}:${KEYCLOAK_PORT:8180}/realms/mdqr-admin

keycloak:
  admin:
    server-url: http://${KEYCLOAK_HOST:127.0.0.1}:${KEYCLOAK_PORT:8180}
    realm: mdqr-admin
    client-id: mdqradminservice
    client-secret: mdqradminservice-secret
```

---

## Acceso via Gateway

El gateway expone el servicio bajo `/services/mdqradminservice/**` (StripPrefix=2) usando discovery de Consul. El nombre registrado en Consul es `mdqradminservice`.

```bash
# Ejemplo de llamada via gateway con token admin
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  http://127.0.0.1:8080/services/mdqradminservice/admin/auth/me
```
