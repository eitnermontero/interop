# 11 - Audit Logs

## Descripcion General

El sistema HUB implementa auditoría en dos módulos con propósitos distintos:

1. **`hub-ms-auth`** — audita las acciones del panel administrativo: logins, CRUD de usuarios, roles, menús y permisos.
2. **`hub-ms-base`** — audita las operaciones de negocio: desencriptaciones QR y operaciones sobre certificados digitales.

---

## Auditoría en hub-ms-auth

### Tabla audit_log

Base de datos: `hub_auth`, schema: `admin`.

```sql
CREATE TABLE admin.audit_log (
    id              BIGSERIAL PRIMARY KEY,
    event_time      TIMESTAMP WITH TIME ZONE NOT NULL,
    event_type      VARCHAR(50)  NOT NULL,
    module          VARCHAR(100) NOT NULL,
    option_code     VARCHAR(100),
    user_id         VARCHAR(100),
    username        VARCHAR(100),
    roles           TEXT[],
    ip_address      VARCHAR(50),
    http_method     VARCHAR(10),
    endpoint        VARCHAR(255),
    response_status INTEGER,
    duration_ms     INTEGER,
    details         JSONB,
    created_date    TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_audit_event_time ON admin.audit_log(event_time DESC);
CREATE INDEX idx_audit_user       ON admin.audit_log(username);
CREATE INDEX idx_audit_event_type ON admin.audit_log(event_type);
CREATE INDEX idx_audit_module     ON admin.audit_log(module);
CREATE INDEX idx_audit_details    ON admin.audit_log USING GIN (details);
```

El indice GIN sobre `details` permite busqueda full-text dentro del JSONB.

### Tipos de evento (event_type)

| Codigo | Descripcion |
|---|---|
| `LOGIN` | Inicio de sesion exitoso |
| `LOGOUT` | Cierre de sesion voluntario |
| `CREATE` | Creacion de recurso |
| `UPDATE` | Modificacion de recurso |
| `DELETE` | Eliminacion de recurso |
| `EXPORT` | Exportacion como CSV u otro formato |

### Modulos (module)

| Codigo | Descripcion |
|---|---|
| `AUTH` | Autenticacion y sesion |
| `USUARIOS` | Administracion de usuarios |
| `ROLES` | Administracion de roles |
| `MENUS` | Administracion de menus |
| `PERMISOS` | Asignacion de permisos |
| `ACCIONES` | Administracion de acciones |
| `AUDITORIA` | Consulta del propio log de auditoria |

### Endpoints de consulta

```
GET  /admin/audit
     Filtros disponibles:
       from=2026-01-01T00:00:00Z
       to=2026-12-31T23:59:59Z
       username=operador
       eventTypes[]=CREATE,UPDATE
       modules[]=USUARIOS,ROLES
       q=<texto para busqueda en details>
       page=0&size=50

GET  /admin/audit/export
     Genera CSV con el resultado filtrado.
     Registra un evento meta-audit (module=AUDITORIA, event_type=EXPORT).
```

### Permisos sobre el modulo AUDITORIA

La consulta y exportacion del log requieren las acciones `READ` y `EXPORT` respectivamente sobre el modulo `AUDITORIA` en el RBAC del sistema.

---

## Auditoría en hub-ms-base

### Tabla decryption_log

Base de datos: `hub_base`, schema: `public`.

Registra cada llamada al endpoint de desencriptacion.

Campos principales:

| Campo | Tipo | Descripcion |
|---|---|---|
| `id` | UUID | Identificador del log |
| `qr_hash` | VARCHAR | Hash SHA-256 del QR recibido (no se guarda el QR completo) |
| `decrypted_data_json` | JSONB | Datos desencriptados |
| `certificate_id` | BIGINT | FK al certificado usado |
| `entity_id` | VARCHAR | Identificador de la entidad emisora |
| `qr_type` | VARCHAR | Tipo de QR procesado |
| `status` | VARCHAR | `SUCCESS` o `ERROR` |
| `error_message` | TEXT | Detalle del error si status = ERROR |
| `processing_time_ms` | INTEGER | Tiempo de procesamiento |
| `from_cache` | BOOLEAN | Si el resultado vino de cache Redis |
| `client_ip` | VARCHAR | IP del cliente que realizo la peticion |
| `created_at` | TIMESTAMP | Fecha y hora del evento |

El QR no se almacena completo — solo su hash SHA-256. Esto garantiza que los datos del usuario no quedan persistidos en el sistema.

### Tabla certificate_audit_log

Base de datos: `hub_base`, schema: `public`.

Registra operaciones sobre certificados digitales.

| Campo | Tipo | Descripcion |
|---|---|---|
| `id` | BIGINT | Identificador del log |
| `certificate_id` | BIGINT | FK al certificado afectado |
| `operation` | VARCHAR | Tipo de operacion (ver catalogo) |
| `performed_by` | VARCHAR | Username del operador |
| `performed_at` | TIMESTAMP | Fecha y hora de la operacion |
| `before_state` | JSONB | Estado del certificado antes de la operacion |
| `after_state` | JSONB | Estado del certificado despues de la operacion |
| `details` | JSONB | Informacion adicional segun la operacion |

### Operaciones de certificado (operation)

| Codigo | Descripcion |
|---|---|
| `UPLOAD` | Carga inicial de certificado PEM |
| `VALIDATE` | Validacion de firma y vigencia |
| `ACTIVATE` | Activacion del certificado |
| `DEACTIVATE` | Desactivacion temporal |
| `REVOKE` | Revocacion permanente |
| `REPLACE` | Reemplazo por nueva version |
| `VIEW` | Consulta del detalle |
| `DOWNLOAD` | Descarga del certificado |

### Endpoints de consulta en ms-base

```
GET  /api/qr/audits
     Filtros: from, to, entityId, status, certificateId, page, size

GET  /api/certificates/audits
     Filtros: from, to, certificateId, operation, performedBy, page, size
```

---

## Acceso via Gateway

Los tres tipos de log se consultan usando un JWT del realm `hub-admin`:

```bash
# Log de desencriptaciones QR
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  "http://127.0.0.1:8080/services/hubbaseservice/api/qr/audits?from=2026-01-01T00:00:00Z"

# Log de operaciones de certificados
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  "http://127.0.0.1:8080/services/hubbaseservice/api/certificates/audits"

# Log de acciones del panel admin
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  "http://127.0.0.1:8080/services/hubadminservice/admin/audit"
```

---

## Reglas de Privacidad

- El QR completo nunca se persiste. Solo se almacena el hash SHA-256.
- Passwords, tokens y secrets nunca se incluyen en `details`.
- Los datos desencriptados se almacenan en `decrypted_data_json` bajo control de acceso — consulta requiere JWT admin.

---

## Retencion

No existe politica automatica de retencion implementada en la version actual. Para volumenes elevados se recomienda:

- Particionar `audit_log` y `decryption_log` por mes usando `pg_partman`.
- Definir politica de archivado a object storage a partir de 12 meses.
- Para `decryption_log` en entornos de alto volumen, considerar particionado desde el inicio del proyecto.
