# 05 - Modelo de Datos PostgreSQL

> ⚠️ **Documento parcialmente desactualizado** (contiene contenido legacy pre-ADR-0004/rename 2026-07-03).
> Fuente de verdad actual: `CLAUDE.md` y `docs/adr/` (ADR-0005/0006/0007).

## Visión General

El sistema HUB utiliza dos bases de datos PostgreSQL independientes, una por módulo:

| Base de datos | Módulo | Schema | Puerto |
|---|---|---|---|
| `hub_base` | `hub-ms-base` | `public` | 8081 |
| `hub_auth` | `hub-ms-auth` | `admin` | 8083 |

## Campos de Auditoría (`AbstractAuditingEntity`)

La mayoría de entidades extienden `AbstractAuditingEntity<Long>`, que provee los siguientes campos via Spring Data JPA Auditing:

| Campo | Tipo | Req | Descripción |
|---|---|---|---|
| `created_by` | VARCHAR(50) | Si | Usuario que creó el registro. Detectado via `SpringSecurityAuditorAware` |
| `created_date` | TIMESTAMPTZ | Si | Fecha de creación. Asignado por `@CreatedDate` |
| `last_modified_by` | VARCHAR(50) | No | Último usuario que modificó el registro |
| `last_modified_date` | TIMESTAMPTZ | No | Fecha de última modificación. Asignado por `@LastModifiedDate` |

Estos campos no se repiten en las tablas individuales. Están implícitos en todas las entidades que heredan de `AbstractAuditingEntity`.

## Migraciones con Liquibase

El proyecto usa Spring Boot 4 con Liquibase 5. En esta versión **no existe `LiquibaseAutoConfiguration`**: cada módulo declara manualmente un bean `SpringLiquibase` en su clase `LiquibaseConfiguration.java`.

Los changelogs se ubican en `src/main/resources/db/changelog/` de cada módulo.

---

## Base de datos: `hub_base` (ms-base)

> **Nota histórica**: las tablas del producto QR anterior (`certificate`,
> `decryption_log`, `certificate_version`, `certificate_audit_log`,
> `trx_entity_code`, `admin_audit_log`) fueron **eliminadas** junto con ese
> negocio (ver `docs/adr/ADR-0004-eliminacion-qr.md`). El esquema actual (v2)
> corresponde al hub de interoperabilidad.

Changelogs v2 en `hub-ms-base/src/main/resources/db/changelog/v2/` (fuente de
verdad de columnas e índices):

| Tabla | Changelog | Propósito |
|---|---|---|
| `hub_audit_log` | `0001-hub-audit-log.xml` | Auditoría de toda transacción inbound/outbound. **Particionada por rango sobre `ts`** (particiones mensuales + `hub_audit_log_default`). Guarda hash SHA-256 canónico (RFC 8785) del request y del response, cadena `prev_hash` (tamper-evident), producto (p.ej. `CASO_PENAL`), partner y correlation id. Sin FK físicas hacia ella (PostgreSQL no permite FK a tablas particionadas). |
| `hub_audit_idempotency` | `0001-hub-audit-log.xml` | Control de idempotencia por `idempotency_key` (at-least-once del relay sin duplicados). |
| `outbox_event` | `0002-outbox-event.xml` | Patrón outbox: el evento de facturación/medición se escribe en la **misma transacción** que el registro de auditoría; un relay lo consume después. |
| `hub_measurement` | `0003-hub-measurement.xml` | Medición/facturación agregada por partner/producto/período (unique por partner+período). |
| `provider`, `provider_credential_ref` | `0004-provider.xml` | Catálogo de proveedores externos (outbound) y referencia a sus credenciales en Vault (nunca el secreto en DB). |

> `0005-caso.xml` existe en el repo pero **no se aplica** (no está incluido en
> `db.changelog-master.xml`): por ADR-0006 §9.3 el hub es intermediario (Modelo A)
> y no persiste campos de dominio del producto (PII en claro).

---

## Base de datos: `hub_auth` (ms-auth)

### Tabla `menu`

Estructura jerárquica del menú de la aplicación frontend. Los menús se asocian a roles de Keycloak a través de `role_menu_action`.

| Campo | Tipo | Req | Descripción |
|---|---|---|---|
| `id` | BIGINT | Si | PK. Secuencia desde 1000 |
| `code` | VARCHAR(100) | Si | UNIQUE. Código identificador del menú |
| `name` | VARCHAR(100) | No | Nombre visible del menú |
| `icon` | VARCHAR(100) | No | Clase o nombre del ícono |
| `route` | VARCHAR(255) | No | Ruta de la aplicación frontend |
| `parent_id` | BIGINT | No | FK → `menu`. Menú padre (estructura jerárquica) |
| `order_index` | INT | No | Orden de aparición. Default: `0` |
| `is_active` | BOOLEAN | No | Indica si el menú está activo. Default: `true` |
| + campos de auditoría | | | `created_by`, `created_date`, `last_modified_by`, `last_modified_date` |

### Tabla `action`

Catálogo de acciones disponibles en el sistema (operaciones que un usuario puede realizar en un menú).

| Campo | Tipo | Req | Descripción |
|---|---|---|---|
| `id` | BIGINT | Si | PK. Secuencia desde 1000 |
| `code` | VARCHAR(50) | Si | UNIQUE. Código de la acción (ej: `READ`, `CREATE`, `DELETE`) |
| `name` | VARCHAR(100) | No | Nombre descriptivo de la acción |
| `description` | VARCHAR(255) | No | Descripción detallada |
| + campos de auditoría | | | `created_by`, `created_date`, `last_modified_by`, `last_modified_date` |

### Tabla `role_menu_action`

Tabla de permisos: define qué acciones puede ejecutar cada rol de Keycloak sobre cada menú. Es la fuente de verdad para el control de acceso basado en roles en la UI.

| Campo | Tipo | Req | Descripción |
|---|---|---|---|
| `keycloak_role_name` | VARCHAR(100) | Si | PK compuesta. Nombre del rol en Keycloak |
| `menu_id` | BIGINT | Si | PK compuesta. FK → `menu` |
| `action_id` | BIGINT | Si | PK compuesta. FK → `action` |
| `is_granted` | BOOLEAN | No | Indica si el permiso está concedido. Default: `true` |
| + campos de auditoría | | | `created_by`, `created_date`, `last_modified_by`, `last_modified_date` |

La PK compuesta `(keycloak_role_name, menu_id, action_id)` garantiza que no existan duplicados para la misma combinación rol-menú-acción.

### Tabla `audit_log`

Registro de auditoría centralizado de todas las acciones de usuarios en el sistema. Cubre autenticación, operaciones CRUD y eventos de exportación.

| Campo | Tipo | Req | Descripción |
|---|---|---|---|
| `id` | BIGINT | Si | PK. Secuencia con incremento 50 |
| `event_time` | TIMESTAMPTZ | No | Fecha y hora del evento |
| `event_type` | VARCHAR(50) | No | Tipo de evento (ver valores abajo) |
| `module` | VARCHAR(100) | No | Módulo del sistema donde ocurrió el evento |
| `option_code` | VARCHAR(100) | No | Código de opción o función dentro del módulo |
| `user_id` | VARCHAR | No | ID del usuario en Keycloak |
| `username` | VARCHAR | No | Username del usuario |
| `full_name` | VARCHAR | No | Nombre completo del usuario |
| `roles` | TEXT[] | No | Lista de roles del usuario en el momento del evento |
| `ip_address` | VARCHAR | No | IP de origen |
| `user_agent` | VARCHAR | No | User-Agent del cliente |
| `service_name` | VARCHAR(50) | No | Nombre del servicio que generó el evento |
| `http_method` | VARCHAR | No | Método HTTP de la solicitud |
| `endpoint` | VARCHAR | No | Endpoint invocado |
| `response_status` | INTEGER | No | Código HTTP de la respuesta |
| `duration_ms` | BIGINT | No | Duración del request en milisegundos |
| `details` | JSONB | No | Detalles adicionales del evento. Indexado con GIN |
| `created_date` | TIMESTAMPTZ | No | Fecha de registro. Default: `NOW()` |

**Valores válidos para `event_type`:**

| Valor | Descripción |
|---|---|
| `LOGIN` | Inicio de sesión |
| `LOGOUT` | Cierre de sesión |
| `CREATE` | Creación de recurso |
| `UPDATE` | Actualización de recurso |
| `DELETE` | Eliminación de recurso |
| `EXPORT` | Exportación de datos |

**Valores válidos para `module`:**

`AUTH`, `USUARIOS`, `ROLES`, `MENUS`, `PERMISOS` (los módulos `CERTIFICATES` y `QR` del producto anterior fueron eliminados)

El campo `details` tiene un índice GIN para optimizar búsquedas sobre su contenido JSONB.

---

## Consideraciones

- **Retención de datos:** `hub_audit_log` está particionada por mes (rango sobre `ts`); la política de retención se implementa desacoplando/eliminando particiones antiguas, no con `DELETE` masivos.
- **Payloads nunca en claro:** en `hub_audit_log` solo se persisten los hashes SHA-256 canónicos (RFC 8785) del request/response. Si se requiere almacenar el payload, va cifrado con Vault Transit (ADR-0006).
- **Índice GIN en `audit_log.details`:** Habilitar para consultas de filtrado sobre el JSONB en entornos con alto volumen de logs.
- **Liquibase sin autoconfiguración:** Al usar Spring Boot 4 / Liquibase 5, la configuración del bean `SpringLiquibase` debe ser explícita en `LiquibaseConfiguration.java`. No se puede confiar en la autoconfiguración de Spring Boot.
