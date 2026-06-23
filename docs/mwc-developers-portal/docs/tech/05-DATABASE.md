# 05 - Modelo de Datos PostgreSQL

## Visión General

El sistema MDQR utiliza dos bases de datos PostgreSQL independientes, una por módulo:

| Base de datos | Módulo | Schema | Puerto |
|---|---|---|---|
| `mdqr_decode` | `mdqr-ms-base` | `public` | 8081 |
| `mdqr_auth` | `mdqr-ms-auth` | `admin` | 8083 |

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

## Base de datos: `mdqr_decode` (ms-base)

### Diagrama de relaciones

```
certificate (1) ──< decryption_log
certificate (1) ──< certificate_version
certificate (1) ──< certificate_audit_log (opcional, via serial_number)
trx_entity_code  (catálogo independiente)
admin_audit_log  (auditoría de acciones administrativas)
```

### Tabla `certificate`

Almacena los certificados digitales (PEM) utilizados para descifrar QRs financieros. Cada entidad financiera puede tener múltiples certificados con versionado.

| Campo | Tipo | Req | Descripción |
|---|---|---|---|
| `id` | BIGINT | Si | PK |
| `serial_number` | VARCHAR(100) | Si | UNIQUE. Número de serie del certificado |
| `fingerprint_sha256` | VARCHAR(64) | Si | Huella SHA-256 del certificado |
| `entity_id` | VARCHAR(50) | No | Identificador de la entidad financiera |
| `entity_name` | VARCHAR(200) | No | Nombre de la entidad financiera |
| `pem_content` | TEXT | Si | Contenido del certificado en formato PEM |
| `subject_dn` | VARCHAR(500) | No | Distinguished Name del sujeto |
| `issuer_dn` | VARCHAR(500) | No | Distinguished Name del emisor |
| `issuer_cn` | VARCHAR(200) | No | Common Name del emisor |
| `valid_from` | TIMESTAMPTZ | No | Inicio de validez del certificado |
| `valid_to` | TIMESTAMPTZ | No | Fin de validez del certificado |
| `status` | VARCHAR(20) | Si | Estado del certificado. Default: `ACTIVE` |
| `version_number` | INTEGER | Si | Número de versión. Default: `1` |
| `is_current_version` | BOOLEAN | Si | Indica si es la versión vigente. Default: `true` |
| `is_active` | BOOLEAN | Si | Indica si el certificado está activo. Default: `true` |
| `is_revoked` | BOOLEAN | Si | Indica si fue revocado. Default: `false` |
| `revoked_at` | TIMESTAMPTZ | No | Fecha y hora de revocación |
| `revoked_by` | VARCHAR | No | Usuario que realizó la revocación |
| `revoked_reason` | VARCHAR | No | Motivo de la revocación |
| `description` | VARCHAR | No | Descripción libre del certificado |
| `tags` | VARCHAR[] | No | Etiquetas para clasificación |
| `notification_emails` | TEXT[] | No | Emails para notificaciones de expiración |
| + campos de auditoría | | | `created_by`, `created_date`, `last_modified_by`, `last_modified_date` |

**Valores válidos para `status`:**

| Valor | Descripción |
|---|---|
| `ACTIVE` | Certificado vigente y operativo |
| `EXPIRING_SOON` | Próximo a vencer (umbral configurable) |
| `EXPIRED` | Fuera del rango de validez |
| `REVOKED` | Revocado explícitamente |
| `SUPERSEDED` | Reemplazado por una versión más nueva |

### Tabla `decryption_log`

Registro de cada operación de descifrado de QR. Por privacidad, no se almacena el QR completo sino su hash SHA-256.

| Campo | Tipo | Req | Descripción |
|---|---|---|---|
| `id` | BIGINT | Si | PK |
| `log_id` | VARCHAR(60) | Si | UNIQUE. Identificador único del log |
| `keycloak_client_id` | VARCHAR(255) | No | Client ID de Keycloak que realizó la solicitud |
| `mtls_cert_cn` | VARCHAR(255) | No | Common Name del certificado mTLS del cliente |
| `certificate_id` | BIGINT | No | FK → `certificate`. Certificado usado para descifrar |
| `qr_string_hash` | VARCHAR(64) | No | SHA-256 del string QR (no se guarda el QR completo) |
| `entity_id_request` | VARCHAR(50) | No | Entidad financiera del QR |
| `external_reference` | VARCHAR(200) | No | Referencia externa del solicitante |
| `metadata` | JSONB | No | Metadatos adicionales de contexto |
| `status` | VARCHAR(20) | No | Resultado: `SUCCESS` o `ERROR` |
| `qr_type` | VARCHAR(20) | No | Tipo de QR procesado |
| `decrypted_data_json` | JSONB | No | Datos descifrados en formato JSON |
| `error_message` | TEXT | No | Mensaje de error si `status = ERROR` |
| `processing_time_ms` | BIGINT | No | Tiempo de procesamiento en milisegundos |
| `ip_address` | VARCHAR(45) | No | IP de origen de la solicitud |
| `user_agent` | VARCHAR(500) | No | User-Agent del cliente |
| + auditoría parcial | | | `created_by`, `created_date` (solo inserción) |

### Tabla `certificate_version`

Historial de versiones anteriores de un certificado. Se registra cada vez que un certificado es reemplazado.

| Campo | Tipo | Req | Descripción |
|---|---|---|---|
| `id` | BIGINT | Si | PK |
| `certificate_id` | BIGINT | Si | FK → `certificate` |
| `version_number` | INTEGER | Si | Número de versión archivada |
| `pem_content_snapshot` | TEXT | No | Copia del PEM en el momento del reemplazo |
| `fingerprint_sha256_snapshot` | VARCHAR | No | Huella SHA-256 de la versión archivada |
| `valid_from_snapshot` | TIMESTAMPTZ | No | Inicio de validez de la versión archivada |
| `valid_to_snapshot` | TIMESTAMPTZ | No | Fin de validez de la versión archivada |
| `subject_dn_snapshot` | VARCHAR | No | Subject DN de la versión archivada |
| `issuer_dn_snapshot` | VARCHAR | No | Issuer DN de la versión archivada |
| `replaced_at` | TIMESTAMPTZ | No | Fecha y hora del reemplazo |
| `replaced_by` | VARCHAR(100) | No | Usuario que realizó el reemplazo |
| `change_reason` | VARCHAR(500) | No | Motivo del reemplazo |
| `change_type` | VARCHAR(50) | No | Tipo de cambio (ej: RENEWAL, REVOCATION) |

### Tabla `certificate_audit_log`

Registro de auditoría detallado de todas las acciones realizadas sobre certificados, incluyendo operaciones de descifrado asociadas.

| Campo | Tipo | Req | Descripción |
|---|---|---|---|
| `id` | BIGINT | Si | PK |
| `certificate_id` | BIGINT | No | ID del certificado involucrado |
| `serial_number` | VARCHAR | No | Número de serie del certificado |
| `action` | VARCHAR(50) | Si | Acción realizada (ver valores abajo) |
| `user_id` | VARCHAR(100) | Si | ID del usuario en Keycloak |
| `user_email` | VARCHAR(255) | No | Email del usuario |
| `ip_address` | VARCHAR | No | IP de origen |
| `user_agent` | TEXT | No | User-Agent del cliente |
| `timestamp` | TIMESTAMPTZ | No | Fecha y hora de la acción |
| `before_state` | JSONB | No | Estado del certificado antes de la acción |
| `after_state` | JSONB | No | Estado del certificado después de la acción |
| `success` | BOOLEAN | No | Resultado de la operación |
| `error_message` | TEXT | No | Mensaje de error si aplica |
| `error_code` | VARCHAR(50) | No | Código de error |
| `request_id` | VARCHAR | No | ID del request HTTP |
| `entity_id_request` | VARCHAR | No | Entidad del QR si aplica |
| `qr_content_hash` | VARCHAR | No | Hash SHA-256 del QR si aplica |
| `processing_time_ms` | INTEGER | No | Tiempo de procesamiento |

**Valores válidos para `action`:**

| Valor | Descripción |
|---|---|
| `UPLOAD` | Carga de nuevo certificado |
| `VALIDATE` | Validación del certificado |
| `ACTIVATE` | Activación del certificado |
| `DEACTIVATE` | Desactivación del certificado |
| `REVOKE` | Revocación del certificado |
| `REPLACE` | Reemplazo por versión nueva |
| `VIEW` | Consulta del certificado |
| `DOWNLOAD` | Descarga del certificado |
| `DECRYPT_QR` | Uso del certificado para descifrar un QR |

### Tabla `trx_entity_code`

Catálogo de entidades financieras participantes del sistema QR en Bolivia. Es una tabla de referencia, no tiene relaciones FK activas pero los registros de `decryption_log` referencian sus códigos en `entity_id_request`.

| Campo | Tipo | Req | Descripción |
|---|---|---|---|
| `code` | VARCHAR(20) | Si | PK. Código único de la entidad (ej: `MLD1017`, `ACCL1009`) |
| `participant` | VARCHAR(255) | No | Nombre completo del banco o cooperativa |
| `entity_group` | VARCHAR(10) | No | Grupo al que pertenece |

**Grupos de entidades:**

| Grupo | Descripción |
|---|---|
| `MLD` | Moneda y Liquidación Diferida |
| `ACCL` | Cámara de Compensación y Liquidación |
| `UNI` | Unilink |

El catálogo incluye 70 o más bancos y cooperativas de Bolivia. Se carga mediante Liquibase con datos iniciales.

### Tabla `admin_audit_log`

Auditoría de acciones administrativas realizadas sobre certificados desde el módulo ms-base (distinto de `certificate_audit_log` que registra todas las operaciones, incluyendo las del sistema).

| Campo | Tipo | Req | Descripción |
|---|---|---|---|
| `id` | BIGINT | Si | PK |
| `keycloak_user_id` | VARCHAR | No | ID del usuario en Keycloak |
| `keycloak_username` | VARCHAR | No | Username del usuario en Keycloak |
| `action` | VARCHAR(100) | Si | Acción administrativa ejecutada |
| `resource_type` | VARCHAR(100) | No | Tipo de recurso afectado |
| `resource_id` | VARCHAR(100) | No | ID del recurso afectado |
| `old_value` | JSONB | No | Valor anterior del recurso |
| `new_value` | JSONB | No | Valor nuevo del recurso |
| `ip_address` | VARCHAR(45) | No | IP de origen |
| `created_at` | TIMESTAMPTZ | No | Fecha y hora del evento |

---

## Base de datos: `mdqr_auth` (ms-auth)

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

`CERTIFICATES`, `QR`, `AUTH`, `USUARIOS`, `ROLES`, `MENUS`, `PERMISOS`

El campo `details` tiene un índice GIN para optimizar búsquedas sobre su contenido JSONB.

---

## Consideraciones

- **Retención de datos:** `decryption_log` y `certificate_audit_log` pueden crecer significativamente en producción. Se recomienda implementar una política de retención (ej: 90 días) o particionamiento por rango de fecha en `decryption_log`.
- **Seguridad del contenido QR:** El campo `qr_string_hash` almacena solo el SHA-256 del QR original. El contenido descifrado en `decrypted_data_json` debe evaluarse según clasificación de datos en cada despliegue.
- **Índice GIN en `audit_log.details`:** Habilitar para consultas de filtrado sobre el JSONB en entornos con alto volumen de logs.
- **Liquibase sin autoconfiguración:** Al usar Spring Boot 4 / Liquibase 5, la configuración del bean `SpringLiquibase` debe ser explícita en `LiquibaseConfiguration.java`. No se puede confiar en la autoconfiguración de Spring Boot.
