# ADR-0007 — Registro declarativo de APIs (conectores) y auditoría de payloads

**Estado**: Propuesto
**Fecha**: 2026-07-03
**Autores**: Equipo Síntesis
**Relacionados**: ADR-0001 (interop hub), ADR-0005 (`ApiResponse<T>`), ADR-0006 (motor genérico inbound)

---

## 1. Contexto y problema

El ADR-0006 construyó el motor genérico inbound (`DispatcherController` +
`ContractRegistry` + `ContractValidator` + `ForwardingGateway` + `InboundPort`):
agregar una API ya no requiere un controller nuevo. Pero los **contratos y el
ruteo siguen compilados en Java**:

- `InboundAutoConfiguration` declara `CASO_PENAL/v1` y `CASO_PENAL_EDITAR/v1`
  como listas de `FieldRule` en código (`@PostConstruct`).
- `ForwardingGateway.PRODUCT_BEAN_MAP` es un `Map.of(...)` estático.

El primer release expone 2 APIs (POST y PATCH de caso). El siguiente requiere
**10 APIs más**. Con el diseño actual eso significa ~10 métodos Java nuevos,
recompilar y desplegar — inviable para una plataforma hub.

Además, el equipo definió los requisitos de **auditoría de payloads**:

1. **Inbound (lo que el hub expone)**: se puede guardar el payload de lo que el
   partner envía y lo que el hub responde.
2. **Outbound (lo que el hub consume)**: en la tabla maestra van los **hashes**;
   el contenido debe poder **verse** para auditoría mediante un mecanismo seguro
   que controle y **registre quién accede**.
3. Es una **primera versión**: el diseño debe ser sencillo, sin complejidad
   innecesaria.

> Precisión técnica sobre el punto 2: un hash es unidireccional — no permite
> "ver" el contenido. Para poder reproducir el payload bajo control de acceso,
> el contenido se guarda **cifrado con Vault Transit** (reversible, alineado con
> ADR-0001 §2) en una tabla separada; el hash queda en la tabla maestra como
> evidencia. "Hasheado" en el requisito se implementa como *hash en la maestra +
> cifrado en la tabla de payloads*.

## 2. Referencias de la industria

Se revisó cómo resuelven esto los productos de API Management de referencia
(Kong, Google Apigee, AWS API Gateway, Azure APIM, Stripe, Gravitee/WSO2).

**Registro de APIs** — patrón común: la API es **datos en el plano de control**
(fila en DB, bundle de config o manifiesto YAML) que un gateway genérico
interpreta en runtime. Todos convergen en el mismo grafo mínimo:
*ruta expuesta → backend/upstream → políticas parametrizadas → producto/plan →
consumidor + suscripción*. Y todos ofrecen una vía declarativa versionable en
Git: Kong DB-less carga toda la config desde un `kong.yml` (con `decK` para
diff/sync GitOps); Apigee despliega bundles de XML; AWS/Azure importan OpenAPI;
Gravitee usa CRDs de Kubernetes. **Agregar una API nunca es programar; es
declarar.**

**Logging de payloads** — patrón común:

| Práctica | Referencia |
|---|---|
| Default = solo metadata + tamaños/latencias; el body **nunca** se loguea por defecto | Kong HTTP-log (no soporta bodies nativo), AWS (data trace = "apagar en prod") |
| Payload persistido = opt-in por API, **truncado** (Azure: 8 KB hard limit) | Azure APIM diagnostics |
| Redacción de credenciales automática; máscara de campos **antes de persistir** (el masking de Apigee no cubre sus propios logs) | Apigee maskconfigs, AWS |
| Sí se puede guardar request/response completos si se redacta lo sensible antes (PCI tokenizado) | Stripe request logs |
| **Retención diferenciada**: corta para lecturas/debug (Stripe: GET 31 días), larga para escrituras facturables (Stripe: 15 meses) | Stripe |
| Ver payload es un **permiso distinto** (más restringido) que ver metadata, y el acceso se audita | Stripe Dashboard RBAC, patrón general |

## 3. Decisión — visión general

Separación estricta en dos planos, al estilo Kong DB-less:

- **Plano de control (configuración)** → **YAML versionado en Git**. Conectores
  (destinos HTTP) y APIs (contratos + ruteo) se declaran en configuración
  Spring (`@ConfigurationProperties`). Git es la fuente de verdad y la
  auditoría de cambios de configuración. Sin tablas de catálogo en v1; la
  Admin API de registro y el panel quedan para v2.
- **Plano de datos (runtime)** → **PostgreSQL**. Tres tablas nuevas junto a
  `hub_audit_log` (que no cambia): `hub_audit_payload` (contenido),
  `payload_access_log` (quién vio qué) y `connector_call_log` (telemetría por
  intento).

Agregar una API nueva = **un bloque YAML** (+ scope en Keycloak). Cero clases,
cero migraciones. Exponer las 10 APIs del siguiente release son 10 bloques.

## 4. Registro declarativo de APIs

### 4.1. Esquema YAML (`hub.*`)

```yaml
hub:
  connectors:                       # destinos HTTP que el hub invoca
    backend-penal:
      base-url: http://backend-penal:8080
      auth:
        type: NONE                  # NONE | API_KEY | BEARER_CLIENT_CREDENTIALS
        vault-path: hub-base/data/backend-penal   # si aplica; nunca secretos en YAML
      timeout-ms: 5000
      resilience:                   # mismos knobs de EfxRateProperties
        retry-max-attempts: 3
        retry-wait-ms: 500
        cb-failure-rate-threshold: 50
        cb-minimum-number-of-calls: 10
        bulkhead-max-concurrent: 10

  apis:                             # contratos inbound (reemplaza InboundAutoConfiguration)
    - product: CASO_PENAL
      version: v1
      method: POST
      connector: backend-penal      # o adapter-bean: <nombre> para casos no-HTTP
      target-path: /casos
      required-scope: https://api.sintesis.com.bo/caso.crear
      payload-log: CLEAR            # NONE | CLEAR | ENCRYPTED (ver §5)
      fields:
        - { name: cud,              type: STRING,  required: true,  max-length: 50 }
        - { name: id_externo_caso,  type: INTEGER, required: true }
        # ... resto de la homologación ADR-0006 §8.2

    - product: CASO_PENAL_EDITAR
      version: v1
      method: PATCH
      resource-id-field: id_pol_caso
      connector: backend-penal
      target-path: /casos/{id_pol_caso}
      payload-log: CLEAR
      fields: [ ... ]
```

Notas:

- El YAML vive en los `application-*.yml` del servicio o en un archivo
  importado (`spring.config.import`), por lo que en `dev`/`prod` puede servirse
  desde **Consul config** — cambiar contratos sin reconstruir la imagen.
- Los secretos **nunca** van en el YAML: `auth.vault-path` es una referencia,
  igual que el patrón existente de `EfxRateClient` (ADR-0001 §5).

### 4.2. Carga y componentes

| Componente | Cambio |
|---|---|
| `HubInteropProperties` (nuevo) | `@ConfigurationProperties("hub")` que materializa `connectors` y `apis`. Valida en arranque: conector referenciado existe, `FieldRule` bien formadas, `(product, version)` únicos. **Config inválida = el contexto no arranca** (fail-fast, mismo espíritu que ADR-0006 §7.2). |
| `ContractRegistry` | Sin cambios de interfaz; se llena desde `HubInteropProperties` en vez de código. `InboundAutoConfiguration` queda solo como cargador. |
| `ForwardingGateway` | El `PRODUCT_BEAN_MAP` estático se reemplaza por resolución desde la config: si la API declara `connector` → `HttpForwardingAdapter` genérico; si declara `adapter-bean` → bean custom (válvula de escape para destinos no HTTP/JSON). |
| `HttpForwardingAdapter` (nuevo) | Único `InboundPort` genérico parametrizado por conector: `RestClient` + timeout + auth desde Vault + resiliencia programática. **Reutiliza el patrón exacto de `EfxRateClient`** (Bulkhead → CircuitBreaker → Retry), leyendo la config del conector en vez de properties compiladas. Sustituye a `StubInboundAdapter` cuando exista el backend real; el stub sigue disponible con `hub.inbound.stub-mode=true`. |
| Publicación del contrato | `OpenApiCustomizer` de springdoc genera paths y esquemas desde los `ContractDefinition` cargados (sobre `ApiResponse<T>` + catálogo de `error.code` del ADR-0005). El partner siempre ve el contrato vigente en el Swagger agregado del gateway; nadie mantiene OpenAPI a mano. |

### 4.3. Qué se hace para exponer una API nueva

1. Agregar el bloque en `hub.apis` (y el conector en `hub.connectors` si es un
   destino nuevo).
2. Crear el scope en Keycloak (realm `hub-partner`) y asociarlo a los clients
   suscritos.
3. Desplegar configuración (restart local; vía Consul config en dev/prod).

El pipeline transversal (validación, hash, cadena, outbox, medición,
`ApiResponse`, correlación) ya lo aplica el motor del ADR-0006 sin tocarlo.

## 5. Auditoría de payloads

### 5.1. Principio

`hub_audit_log` **no cambia**: sigue siendo la tabla maestra de evidencia
(1 fila por transacción, ambas direcciones, hashes + cadena + firma, nunca
contenido). El contenido va a una tabla satélite 1:1 opcional.

### 5.2. Política por dirección (decisión del equipo)

| Dirección | `payload-log` default | Almacenamiento |
|---|---|---|
| **IN** (el hub expone; partner→hub y respuesta del hub) | `CLEAR` | `jsonb` en claro — decisión de negocio: es la evidencia operativa de lo que nos enviaron y lo que respondimos. |
| **OUT** (el hub consume; hub→proveedor y su respuesta) | `ENCRYPTED` | Cifrado **Vault Transit** (`request_cipher`/`response_cipher` + `vault_key_ref`). El hash queda en la maestra; el contenido solo es visible vía el mecanismo de §5.4. |
| Cualquiera | `NONE` | Solo hashes (para APIs donde el contenido no aporta). |

La política es **por API/conector** en el YAML (`payload-log`), con esos
defaults por dirección. Operaciones de autenticación contra proveedores
(login/token) fuerzan `NONE` siempre: jamás se persisten credenciales.

### 5.3. Tabla `hub_audit_payload` (Liquibase `v2/0006`)

```sql
CREATE TABLE hub_audit_payload (
  audit_id          uuid        NOT NULL,   -- = hub_audit_log.id
  ts                timestamptz NOT NULL,   -- clave de partición (mensual, como 0001)
  direction         varchar(3)  NOT NULL CHECK (direction IN ('IN','OUT')),
  storage           varchar(10) NOT NULL CHECK (storage IN ('CLEAR','ENCRYPTED')),
  request_payload   jsonb,                  -- solo si storage = CLEAR
  response_payload  jsonb,
  request_cipher    text,                   -- ciphertext Vault Transit si ENCRYPTED
  response_cipher   text,
  vault_key_ref     varchar(200),
  truncated         boolean NOT NULL DEFAULT false,
  PRIMARY KEY (audit_id, ts)
) PARTITION BY RANGE (ts);
```

Reglas de escritura (todas antes de persistir, lección Apigee/Stripe):

- **Redacción incondicional** de headers y campos de credenciales
  (`Authorization`, api keys, secrets) — no configurable.
- **Truncado** a un máximo configurable (`hub.audit.payload-max-bytes`, default
  64 KB; Azure usa 8 KB, se amplía porque un caso penal es más grande) con
  flag `truncated=true`.
- Escritura **fuera de la transacción crítica** y tolerante a fallos (mismo
  criterio que la firma Transit, ADR-0006 §10): si falla el guardado del
  payload, la transacción de negocio y su auditoría maestra no se ven
  afectadas — el hash siempre queda.

**Retención diferenciada** (lección Stripe): particiones mensuales;
`hub_audit_payload` se poda soltando particiones (propuesta v1: 15 meses para
escrituras; configurable), mientras `hub_audit_log` conserva retención larga
(valor probatorio).

### 5.4. Acceso seguro al contenido

- Endpoint `GET /api/audits/{auditId}/payload` (ms-base), protegido con rol
  **`AUDITOR`** (requiere habilitar OAuth2 JWT en `SecurityConfiguration`,
  hoy en modo desarrollo — prerequisito de esta fase).
- Si `storage=ENCRYPTED`, descifra **on-demand** vía Vault Transit; nunca se
  materializa descifrado en disco.
- **Todo acceso se registra** en `payload_access_log`:

```sql
CREATE TABLE payload_access_log (
  id           bigint PRIMARY KEY,
  ts           timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  audit_id     uuid        NOT NULL,
  username     varchar(100) NOT NULL,   -- sub/preferred_username del JWT
  action       varchar(10) NOT NULL CHECK (action IN ('VIEW','DECRYPT')),
  reason       varchar(500)             -- motivo declarado por el auditor (opcional v1)
);
```

Ver metadata (`hub_audit_log`) y ver contenido (`hub_audit_payload`) son
permisos distintos — patrón estándar de la industria.

## 6. `connector_call_log` — telemetría por intento (Liquibase `v2/0007`)

Registro **técnico/operativo** de cada intento HTTP del hub contra un destino
(reenvío inbound al backend y llamadas outbound a proveedores). Se homologa la
propuesta original del equipo con dos ajustes: **sin columnas de payload** (el
contenido vive en `hub_audit_payload`, una sola fuente) y **particionada**.

```sql
CREATE TABLE connector_call_log (
  id                    bigint      NOT NULL,
  ts                    timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  connector_code        varchar(50) NOT NULL,     -- clave del YAML hub.connectors
  operation             varchar(100) NOT NULL,    -- product:version o código de operación outbound
  direction             varchar(3)  NOT NULL CHECK (direction IN ('IN','OUT')),
  attempt               smallint    NOT NULL DEFAULT 1,  -- reintentos R4j = 1 fila c/u
  http_method           varchar(10),
  endpoint              varchar(500),             -- URL efectiva, sin query sensible
  external_reference_id varchar(100),             -- cruce con el ID del sistema destino
  http_status           int,                      -- NULL en timeout/conexión rechazada
  success               boolean     NOT NULL,
  latency_ms            int,
  correlation_id        varchar(64),              -- cruce con hub_audit_log y payload
  error_code            varchar(40),              -- canónico ADR-0005 §7
  error_message         text,
  PRIMARY KEY (id, ts)
) PARTITION BY RANGE (ts);
-- Índices: (connector_code, ts DESC), (correlation_id), (external_reference_id) parcial
```

Diferencias de rol (las tres tablas son complementarias, no redundantes):

| | `hub_audit_log` | `hub_audit_payload` | `connector_call_log` |
|---|---|---|---|
| Naturaleza | Evidencia legal (hash-chain, firma) | Contenido bajo control de acceso | Telemetría de soporte |
| Granularidad | 1 / transacción | 0..1 / transacción | 1 / **intento** HTTP |
| Escritura | Tx única con outbox | Fuera de tx, tolerante | Fuera de tx, tolerante |
| Retención | Larga | Media (≈15 meses) | Corta (≈90 días) |

Lo escribe un `ConnectorCallRecorder` compartido, invocado por
`HttpForwardingAdapter` (inbound) y por los clientes outbound (`EfxRateClient`
se instrumenta como primer productor).

## 7. Qué NO entra en v1 (deliberadamente)

- **Admin API CRUD de conectores + panel Angular**: v2. En v1 el registro es
  YAML + Git (auditable y suficiente para el ritmo actual de altas).
- **Máscara declarativa por JSONPath por API**: v1 solo redacta credenciales
  incondicionalmente. La máscara fina se agrega si aparece un caso real.
- **Redacción retroactiva** (estilo Stripe redaction jobs): pendiente para
  cuando exista un requisito legal de supresión.
- **`hub_exchange`** (ADR-0006 §9.3): `hub_audit_payload` cubre su caso de uso
  con un diseño más simple; queda descartada.
- **Tabla `connector` en DB**: el catálogo vive en YAML; si v2 introduce la
  Admin API, se migra a DB en ese momento. La tabla `provider` y su seed legacy
  fueron **eliminadas** de los changelogs el 2026-07-03 (DB desde cero).

## 8. Consecuencias y riesgos

**Positivas**: exponer N APIs cuesta N bloques YAML; una sola fuente de
contenido con control de acceso y rastro de accesos; alineación con ADR-0001
(hashes en la maestra, contenido cifrado donde corresponde, secretos en Vault);
patrón validado contra Kong/Apigee/Azure/Stripe.

**Riesgos**:

- YAML extenso y propenso a typos → validación fail-fast en arranque + tests de
  contrato generados desde la misma config (mitigación ya aceptada en ADR-0006 §8.4).
- Guardar payload inbound en claro incluye PII de casos penales; el equipo lo
  asume como decisión de negocio. Mitigaciones: acceso por rol `AUDITOR`,
  `payload_access_log`, retención acotada, y la opción `payload-log: ENCRYPTED`
  por API si una institución lo exige.
- Cambios de contrato requieren despliegue de config (restart) en v1; aceptable
  para el volumen actual, resuelto por Consul config en dev/prod.
- El endpoint de payloads exige habilitar por fin JWT + roles en ms-base
  (deuda conocida, CLAUDE.md gotcha #12) — se incluye como prerequisito.

## 9. Plan de implementación por fases

1. **`HubInteropProperties`** + migración de los 2 contratos actuales a YAML;
   `ContractRegistry`/`ForwardingGateway` leen de la config (se elimina el
   hardcode). Tests de contrato siguen verdes.
2. **`HttpForwardingAdapter`** genérico (RestClient + Vault + resiliencia) —
   convive con el stub por flag.
3. **Liquibase `v2/0006`** (`hub_audit_payload` + particiones) y escritura
   desde el pipeline inbound (`HubAuditInterceptor`) y outbound (adaptadores),
   con redacción + truncado.
4. **Liquibase `v2/0007`** (`connector_call_log`) + `ConnectorCallRecorder`;
   instrumentar `HttpForwardingAdapter` y `EfxRateClient`.
5. **Seguridad**: habilitar OAuth2 JWT + roles en ms-base; endpoint
   `GET /api/audits/{id}/payload` con rol `AUDITOR` + `payload_access_log`
   (Liquibase `v2/0008`).
6. **OpenAPI dinámico** desde los contratos cargados + catálogo en el Swagger
   agregado del gateway.
7. **Alta de las 10 APIs nuevas** como bloques YAML (validación del ejercicio:
   ninguna debe requerir código; si alguna lo requiere, es un `adapter-bean`
   custom y se documenta por qué).
