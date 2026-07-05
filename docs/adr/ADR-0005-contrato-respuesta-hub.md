# ADR-0005 — Contrato de respuesta estándar del hub (`ApiResponse<T>`)

Estado: Aceptado · Fecha: 2026-06-22 · Revisión: 2 (2026-06-22)

> Relacionados: ADR-0001 (Hub de interoperabilidad), ficha técnica
> `Ficha_Tecnica_Interoperabilidad_MP_POL_FELCN.md`.
>
> Alcance: este ADR define **solo el contrato de las respuestas de los endpoints
> que el hub EXPONE** (inbound: POL/FELCN/MP llamando a `/partner/v1/**`). No
> define el formato de las APIs de terceros (MP, POL) — ese es contrato de ellos
> y se trata como referencia para el mapeo de nombres y para el adaptador
> outbound (ACL del ADR-0001).
>
> **Cambio clave de la revisión 2**: se elimina el modelo híbrido
> `ApiResponse` / `ProblemDetail`. El contrato externo del hub es **siempre**
> `ApiResponse<T>`, en todas las capas y sin excepción (salvo el rechazo de capa
> de transporte mTLS, que no tiene body HTTP posible — ver §6.1 y §8). Un partner
> **nunca** verá un `ProblemDetail`.

---

## Contexto

El hub está incorporando el dominio de **interoperabilidad de casos penales
(POL/FELCN ↔ MP)**. Históricamente convivían varias formas de respuesta:

1. **El hub** (módulo `hub-ms-base`, Spring Boot 4 / MVC) tiene un
   `GlobalExceptionHandler` que emitía errores con **`ProblemDetail` (RFC 7807 /
   RFC 9457)**: `type`, `title`, `status`, `detail` + propiedades extendidas
   (`timestamp`, `errorCode`, `violations`).

2. **El gateway** (`hub-gateway`, WebFlux) emitía errores de seguridad
   (`ProblemDetailAuthEntryPoint`) y de routing (`GatewayExceptionHandler`)
   también como `ProblemDetail`.

3. **El MP** (ficha técnica) usa un sobre propio y NO estándar:
   `{ "error": bool, "message": str, "response": {...}, "status": int }`, con
   nombres de campo en `camelCase` y español (`mpCasoId`, `polCasoId`,
   `creacionFechaHora`, etc.).

Problemas a resolver:

- **Inconsistencia de sobre**: un partner debía parsear hasta tres formas
  distintas (payload desnudo en éxito, `ProblemDetail` en error de
  framework/seguridad, y eventualmente un sobre de negocio). El discriminante
  "si tiene `success` es una cosa, si tiene `type` es otra" trasladaba
  complejidad al partner.
- **Trazabilidad**: cada transacción del hub genera un `hub_audit_log` con
  `correlation_id` (UUID). Ese ID no viajaba de forma garantizada a la respuesta
  en todos los caminos de error (especialmente los de seguridad/transporte en el
  edge), por lo que el partner no podía cruzar un error con nuestro log de
  auditoría al reportar incidencias.
- **Acoplamiento al contrato del MP**: si copiamos el sobre del MP perdemos los
  estándares (ISO 8601, snake_case canónico) y nos atamos a su `camelCase`.

Necesitamos **un único sobre del lado inbound del hub**, consistente para éxito
y error, en **todas las capas** (gateway y microservicio), con trazabilidad
obligatoria y nombres canónicos propios del hub.

---

## Decisión

### Resumen ejecutivo

1. Todo endpoint y **toda capa** del stack inbound del hub responde con un sobre
   único **`ApiResponse<T>`** (snake_case, JSON), tanto en éxito como en error
   (de negocio, de seguridad o de infraestructura).
2. **`correlation_id` es obligatorio en TODA respuesta** (éxito y error) y es el
   mismo valor que `hub_audit_log.correlation_id`.
3. **`ProblemDetail` queda eliminado del contrato externo.** Puede seguir
   existiendo internamente como modelo de Spring (el framework lo genera en
   ciertos paths: `ErrorResponseException`, `NoResourceFoundException`,
   `ResponseStatusException`, errores de Spring Security), pero **nunca sale como
   respuesta al partner**: cualquier `ProblemDetail` producido internamente
   **debe ser interceptado y convertido a `ApiResponse`** antes de escribirse en
   el cuerpo de la respuesta. Ver §4 y §6.
4. **Única excepción documentada**: el rechazo de **capa de transporte mTLS**
   (TLS handshake fallido) ocurre antes de que exista contexto HTTP, por lo que
   **no hay body de respuesta posible**. El cliente recibe `connection reset` /
   `alert` TLS. Es auditable desde los logs del gateway por IP/SNI. Ver §6.1 y §8.
5. Convención canónica de nombres del hub: **`snake_case` minúsculas, español**
   para el dominio, con un diccionario de mapeo desde los nombres del MP.

---

## 1. `ApiResponse<T>` — envoltorio genérico

Sobre único para los endpoints que el hub expone. Mismos campos para éxito y
error (los irrelevantes van en `null`, nunca se omiten campos del primer nivel
salvo `error`/`data` según el caso descrito abajo).

| Campo            | Tipo            | Presencia                 | Descripción |
|------------------|-----------------|---------------------------|-------------|
| `success`        | boolean         | siempre                   | `true` si la operación fue exitosa (HTTP 2xx); `false` en cualquier error (negocio, seguridad o infraestructura). Es el discriminante principal. |
| `status`         | integer         | siempre                   | Código HTTP semántico de la respuesta (200, 201, 400, 409, ...). Redundante con el status line HTTP, pero útil para clientes que solo leen el body. |
| `message`        | string          | siempre                   | Mensaje legible para humanos, en español. En éxito describe el resultado; en error describe el problema de forma orientada al consumidor. No contiene PII ni stack traces. |
| `data`           | `T` \| `null`   | siempre (puede ser `null`)| Payload de datos en caso de éxito. `null` en errores y en operaciones sin contenido (p. ej. un PATCH idempotente). |
| `error`          | objeto \| `null`| siempre (puede ser `null`)| Detalle estructurado del error. `null` cuando `success=true`. Ver sub-estructura abajo. |
| `correlation_id` | string (UUID)   | **siempre**               | UUID de la transacción. Igual a `hub_audit_log.correlation_id` y al header `X-Correlation-ID`. Permite al partner cruzar la respuesta con nuestro registro de auditoría al reportar incidencias. |
| `timestamp`      | string (ISO 8601)| siempre                  | Momento de generación de la respuesta, en formato ISO 8601 **con zona horaria** (offset). El hub emite en UTC (`Z`). Ejemplo: `2026-06-22T14:35:02.482Z`. |

### Sub-estructura `error` (solo cuando `success=false`)

| Campo        | Tipo                  | Presencia              | Descripción |
|--------------|-----------------------|------------------------|-------------|
| `code`       | string                | siempre en error       | **Código de error canónico** del hub, estable y documentado (ver catálogo en §7). En `SCREAMING_SNAKE_CASE`. Es lo que el partner debe usar para lógica de reintento/manejo, NO el `message`. |
| `detail`     | string                | siempre en error       | Descripción técnica/legible adicional. Puede coincidir con `message`. **Nunca** contiene stack traces, tipos de excepción ni internals en perfiles productivos (ver §6.2). |
| `violations` | array de objetos      | solo validación (400)  | Lista de violaciones de validación. Cada elemento: `{ "field": "<nombre_campo>", "message": "<motivo>" }`. Es **lista**, no mapa, para soportar varias violaciones del mismo campo y preservar orden. |

> Decisión de forma de `violations`: la ficha del MP usa
> `validationErrors: { field: [msg1, msg2] }` (mapa de listas) y el
> `GlobalExceptionHandler` original usaba `violations: { field: msg }` (mapa
> simple, pierde violaciones múltiples). El hub adopta **array de
> `{field, message}`**: es más extensible (permite agregar `code` por violación
> a futuro), no colapsa duplicados y es trivial de serializar desde Bean
> Validation.

### Paginación: `Page<T>` dentro de `data`

Cuando el endpoint devuelve una lista, `data` contiene un objeto `Page<T>` y
**NO se promueven campos de paginación al primer nivel del sobre**.

| Campo         | Tipo            | Descripción |
|---------------|-----------------|-------------|
| `items`       | array de `T`    | Elementos de la página actual. |
| `page`        | integer         | Número de página actual, **base 0** (alineado con `Pageable` de Spring Data). |
| `page_size`   | integer         | Tamaño de página solicitado (máx. 100, ver gotcha de `/api/qr/audits`). |
| `total_items` | integer (long)  | Total de elementos en todas las páginas. |
| `total_pages` | integer         | Total de páginas. |

Justificación de meter la paginación dentro de `data` y no en el primer nivel:

- El primer nivel del sobre debe ser **idéntico** para cualquier endpoint
  (objeto único o lista) → un solo modelo de deserialización para el partner.
- Mantiene `data` como "el resultado" completo y autocontenido; los metadatos de
  paginación pertenecen al resultado, no a la transacción.
- **Alternativa descartada**: campos `page/total/...` en el primer nivel del
  sobre. Rechazada porque obliga a que el sobre tenga forma variable (campos que
  solo existen en respuestas de lista), rompiendo la consistencia, que es el
  objetivo central de este ADR.

---

## 2. Ejemplos de respuesta

### 2.1. Éxito simple (objeto único) — `200 OK`

`GET /partner/v1/casos/{cud}`

```json
{
  "success": true,
  "status": 200,
  "message": "Caso obtenido correctamente",
  "data": {
    "cud": "20240115001234",
    "id_mp_caso": 4821,
    "id_pol_caso": 1190,
    "tipo_denuncia_id": 3,
    "creacion_fecha_hora": "2024-01-15T09:12:00-04:00",
    "esta_reservado": false,
    "caso_estado_id": 2,
    "caso_etapa_id": 1,
    "tags": ["narcotrafico", "felcn"]
  },
  "error": null,
  "correlation_id": "9f3a6b2e-1c44-4d77-8b0a-2e5f1c9d8a01",
  "timestamp": "2026-06-22T14:35:02.482Z"
}
```

### 2.2. Éxito paginado (lista de casos) — `200 OK`

`GET /partner/v1/casos?page=0&page_size=2`

```json
{
  "success": true,
  "status": 200,
  "message": "Lista de casos",
  "data": {
    "items": [
      {
        "cud": "20240115001234",
        "id_mp_caso": 4821,
        "id_pol_caso": 1190,
        "caso_estado_id": 2,
        "creacion_fecha_hora": "2024-01-15T09:12:00-04:00"
      },
      {
        "cud": "20240116007781",
        "id_mp_caso": 4822,
        "id_pol_caso": 1191,
        "caso_estado_id": 1,
        "creacion_fecha_hora": "2024-01-16T11:40:00-04:00"
      }
    ],
    "page": 0,
    "page_size": 2,
    "total_items": 137,
    "total_pages": 69
  },
  "error": null,
  "correlation_id": "1a2b3c4d-5e6f-4071-8a9b-0c1d2e3f4a5b",
  "timestamp": "2026-06-22T14:36:10.114Z"
}
```

### 2.3. Error de validación — `400 Bad Request`

`POST /partner/v1/casos` (faltan campos requeridos / valor inválido)

```json
{
  "success": false,
  "status": 400,
  "message": "Error de validación en los datos enviados",
  "data": null,
  "error": {
    "code": "VALIDATION_ERROR",
    "detail": "Uno o más campos no superaron la validación",
    "violations": [
      { "field": "cud", "message": "El campo es requerido" },
      { "field": "tipo_denuncia_id", "message": "El campo es requerido" },
      { "field": "creacion_fecha_hora", "message": "Formato ISO 8601 inválido" }
    ]
  },
  "correlation_id": "7c9e0a11-2b3c-4d5e-9f00-1122334455aa",
  "timestamp": "2026-06-22T14:37:45.900Z"
}
```

### 2.4. Error de idempotencia (`idempotency_key` duplicada) — `409 Conflict`

`POST /partner/v1/casos` con un `X-Idempotency-Key` ya usado para un payload
distinto.

```json
{
  "success": false,
  "status": 409,
  "message": "La clave de idempotencia ya fue utilizada con un contenido diferente",
  "data": null,
  "error": {
    "code": "IDEMPOTENCY_CONFLICT",
    "detail": "El X-Idempotency-Key 'b8c1...e4' ya está asociado a otra operación. Use una clave nueva o reenvíe el payload original.",
    "violations": null
  },
  "correlation_id": "d4e5f6a7-8b9c-40d1-a2e3-f4a5b6c7d8e9",
  "timestamp": "2026-06-22T14:38:20.005Z"
}
```

> Nota: si el `X-Idempotency-Key` se repite con **el mismo** payload, el hub
> devuelve `200/201` con la respuesta original cacheada (replay idempotente), no
> un 409. El 409 es solo para colisión de clave con contenido distinto.

### 2.5. Fallo de autenticación — `401 Unauthorized`

Token ausente, expirado, firmado por un issuer incorrecto o con firma inválida.
Emitido por el **gateway** (chain WebFlux de partner, vía
`ServerAuthenticationEntryPoint`) — ver §6.1.b. Si el token está bien firmado
pero **expirado**, el `error.code` es `TOKEN_EXPIRED`; si simplemente no hay
token o no es válido, es `AUTHENTICATION_REQUIRED`.

```json
{
  "success": false,
  "status": 401,
  "message": "Autenticación requerida",
  "data": null,
  "error": {
    "code": "AUTHENTICATION_REQUIRED",
    "detail": "No se presentó un token de acceso válido. Obtenga un token en POST /oauth2/token y preséntelo como Bearer.",
    "violations": null
  },
  "correlation_id": "3b1f88a0-2c4d-4e7a-9f12-aa3344556677",
  "timestamp": "2026-06-22T14:39:10.220Z"
}
```

Variante con token expirado:

```json
{
  "success": false,
  "status": 401,
  "message": "El token de acceso ha expirado",
  "data": null,
  "error": {
    "code": "TOKEN_EXPIRED",
    "detail": "El access token presentado expiró. Solicite uno nuevo en POST /oauth2/token.",
    "violations": null
  },
  "correlation_id": "5c2a99b1-3d5e-4f8b-a023-bb4455667788",
  "timestamp": "2026-06-22T14:39:30.140Z"
}
```

### 2.6. Acceso denegado / scope insuficiente — `403 Forbidden`

Token válido y autenticado, pero el cliente no está autorizado para el recurso.
Subcasos:

**Caso A — scope insuficiente (`403 INSUFFICIENT_SCOPE`)**. El JWT no contiene el
scope requerido por el producto. Emitido por el gateway
(`ServerAccessDeniedHandler` o el filtro de suscripción) — ver §6.1.b.

```json
{
  "success": false,
  "status": 403,
  "message": "El token no autoriza el acceso a este recurso",
  "data": null,
  "error": {
    "code": "INSUFFICIENT_SCOPE",
    "detail": "El token presentado no incluye el scope 'https://api.sintesis.com.bo/qr.decode' requerido por este endpoint.",
    "violations": null
  },
  "correlation_id": "8a1b2c3d-4e5f-4061-9273-cc5566778899",
  "timestamp": "2026-06-22T14:40:05.011Z"
}
```

**Caso B — suscripción inactiva (`403 SUBSCRIPTION_INACTIVE`)**. El certificado/
partner no está mapeado a una suscripción activa del producto. Lo evalúa el
`PartnerSubscriptionFilter` en el gateway o el servicio de negocio en el
microservicio.

```json
{
  "success": false,
  "status": 403,
  "message": "El partner no tiene una suscripción activa para este producto",
  "data": null,
  "error": {
    "code": "SUBSCRIPTION_INACTIVE",
    "detail": "El certificado presentado (CN=felcn) no está mapeado a una suscripción activa del producto 'casos-penales'.",
    "violations": null
  },
  "correlation_id": "5566778899aa-bbcc-40dd-eeff-001122334455",
  "timestamp": "2026-06-22T14:41:12.321Z"
}
```

### 2.7. Recurso no encontrado — `404 Not Found`

Error de negocio controlado, resuelto por el microservicio.

```json
{
  "success": false,
  "status": 404,
  "message": "Caso no encontrado",
  "data": null,
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "detail": "No existe un caso con cud '99999999999999' para el partner solicitante.",
    "violations": null
  },
  "correlation_id": "abcdef01-2345-4678-9abc-def012345678",
  "timestamp": "2026-06-22T14:42:30.777Z"
}
```

### 2.8. Error interno — `500 Internal Server Error` (perfil productivo)

Error **no controlado**. Lo captura el handler de la capa correspondiente
(`@RestControllerAdvice` en el microservicio, `ErrorWebExceptionHandler` en el
gateway) y se serializa como `ApiResponse`, **ocultando** `technicalDetail` y
`exceptionType` fuera de perfiles de desarrollo (ver §6.2).

```json
{
  "success": false,
  "status": 500,
  "message": "Error interno del servidor",
  "data": null,
  "error": {
    "code": "INTERNAL_ERROR",
    "detail": "Ocurrió un error inesperado. Reporte este incidente citando el correlation_id.",
    "violations": null
  },
  "correlation_id": "0a1b2c3d-4e5f-4061-8273-8495a6b7c8d9",
  "timestamp": "2026-06-22T14:43:00.000Z"
}
```

> En perfiles **no productivos** (`local`, `dev`, `test`) el objeto `error`
> puede llevar campos extra de diagnóstico (`technical_detail`, `exception_type`),
> pero el primer nivel del sobre y `error.code` no cambian. En **prod** esos
> campos se omiten para no filtrar internals.

### 2.9. Servicio downstream caído — `503 Service Unavailable` (desde el gateway)

El gateway no puede rutear al microservicio (instancia caída, sin instancias en
Consul, circuit breaker abierto). Emitido por el `ErrorWebExceptionHandler` del
gateway — ver §6.1.c. Para timeouts del upstream se usa `UPSTREAM_TIMEOUT` (504)
y para respuestas de error del upstream `UPSTREAM_ERROR` (502).

```json
{
  "success": false,
  "status": 503,
  "message": "Servicio temporalmente no disponible",
  "data": null,
  "error": {
    "code": "SERVICE_UNAVAILABLE",
    "detail": "El servicio que atiende esta operación no está disponible en este momento. Reintente más tarde con la misma X-Idempotency-Key.",
    "violations": null
  },
  "correlation_id": "f0e1d2c3-b4a5-4968-8776-554433221100",
  "timestamp": "2026-06-22T14:44:18.640Z"
}
```

---

## 3. Diccionario de nombres (convención canónica del hub)

**Regla general**: todos los campos JSON expuestos por el hub van en
**`snake_case` minúsculas**, en **español** para términos de dominio.

### 3.1. Reglas adicionales

- **Identificadores externos estables**: se mantienen literales. `cud` se queda
  como `cud` (Código Único de Denuncia, identificador estable y conocido por
  ambas instituciones). Lo mismo aplica a códigos de catálogo nacionales.
- **IDs de las contrapartes**: se prefijan según el sistema dueño y se
  normalizan a `snake_case`: `mpCasoId` → `id_mp_caso`, `polCasoId` →
  `id_pol_caso`. Patrón: `id_<sistema>_<entidad>`.
- **IDs internos del hub**: el identificador propio del hub es **`id`** (clave
  primaria de la entidad expuesta). Cuando se necesite distinguir explícitamente
  el ID del hub de IDs externos en un mismo objeto, se usa **`hub_id`**. Nunca
  `Id`, `ID` ni `hubId`.
- **Timestamps**: se nombran en **español de dominio** cuando provienen de la
  ficha (`creacion_fecha_hora`, `hecho_fecha_hora`, `fecha_inicio`,
  `fecha_fin`). Los timestamps **técnicos/de auditoría del hub** (no de dominio)
  usan el par estándar **`created_at` / `updated_at`** en inglés. Regla:
  dominio = español; metadatos técnicos del hub = `created_at`/`updated_at`.
  Todos los valores en **ISO 8601 con offset**.
- **Booleanos**: prefijo **`es_`** para predicados de estado
  (`es_querellante`, `es_principal`, `es_tentativo`, `es_virtual`,
  `es_ciudadano_digital`) y **`esta_`** para estados de situación
  (`esta_reservado`, `esta_fallecido`, `esta_desaparecido`). No se usa `is_`
  (inglés) ni booleanos sin prefijo (`activo`). Excepción: `activo` se modela
  como booleano `esta_activo`.
- **Enums / catálogos**: los **catálogos del MP** (referencias por ID a
  `catalogos.mp.gob.bo`) se exponen como **enteros** conservando su semántica de
  ID de catálogo (`tipo_denuncia_id`, `caso_estado_id`, `caso_etapa_id`,
  `situacion_juridica_id`). Los **enums propios del hub** (estados de máquina
  interna, direcciones de tráfico, etc.) se exponen como **string en
  `SCREAMING_SNAKE_CASE`** (p. ej. `direccion: "INBOUND"`). Regla: ID de
  catálogo externo = entero (no lo poseemos, no lo traducimos); enum propio del
  hub = string. Excepción permitida: un enum del hub puede exponerse como entero
  solo si la ficha de la contraparte lo exige literalmente en el payload de
  salida hacia ella (eso es responsabilidad del **adaptador outbound**, no del
  contrato inbound).

### 3.2. Mapeo de campos clave (ficha MP → canónico del hub)

| Ficha MP (`camelCase`)          | Canónico hub (`snake_case`)            | Notas |
|---------------------------------|----------------------------------------|-------|
| `cud`                           | `cud`                                  | Identificador externo estable. |
| `mpCasoId`                      | `id_mp_caso`                           | |
| `polCasoId`                     | `id_pol_caso`                          | |
| `mpCasoPadreId` / `casoPadreIdExterno` | `id_caso_padre`                 | El sistema dueño se infiere por dirección; se normaliza a un solo nombre. |
| `tipoDenunciaId`                | `tipo_denuncia_id`                     | Catálogo MP (entero). |
| `creacionFechaHora`            | `creacion_fecha_hora`                  | ISO 8601 con offset. |
| `estaReservado`                | `esta_reservado`                       | Booleano. |
| `oficinaComunId`               | `oficina_comun_id`                     | Catálogo (entero). |
| `oficinaIdExterno`             | `id_oficina_externa`                   | |
| `hechoMunicipioId`             | `hecho_municipio_id`                   | |
| `hechoZona`                    | `hecho_zona`                           | |
| `hechoDireccion`               | `hecho_direccion`                      | |
| `hechoLatitud` / `hechoLongitud` | `hecho_latitud` / `hecho_longitud`   | String (la ficha los define como string). |
| `hechoReferenciaLugar`         | `hecho_referencia_lugar`               | |
| `hechoRelato`                  | `hecho_relato`                         | |
| `hechoFechaHora` / `hechoFechaHoraFin` | `hecho_fecha_hora` / `hecho_fecha_hora_fin` | ISO 8601. |
| `hechoFechaHoraAproximada`     | `hecho_fecha_hora_aproximada`          | String (texto aproximado). |
| `casoEstadoId`                 | `caso_estado_id`                       | Catálogo (entero). |
| `casoEtapaId`                  | `caso_etapa_id`                        | Catálogo (entero). |
| `denominacionCaso`             | `denominacion_caso`                    | |
| `tags`                         | `tags`                                 | Array de string. |
| `mpCasoDelitoId` / `polCasoDelitoId` | `id_mp_caso_delito` / `id_pol_caso_delito` | |
| `delitoId`                     | `delito_id`                            | Catálogo (entero). |
| `esPrincipal` / `esTentativo`  | `es_principal` / `es_tentativo`        | Booleanos. |
| `mpCasoPersonaId` / `polCasoPersonaId` | `id_mp_caso_persona` / `id_pol_caso_persona` | |
| `extCasoPersonaId`             | `id_caso_persona_externo`              | |
| `tipoSujetoId`                 | `tipo_sujeto_id`                       | Catálogo (entero o array según endpoint). |
| `parentescoVictimaId`          | `parentesco_victima_id`                | |
| `personaNatural` / `personaJuridica` | `persona_natural` / `persona_juridica` | Objetos anidados (todos sus campos siguen las mismas reglas). |
| `reservaIdentidad`             | `reserva_identidad`                     | Booleano (`reserva_identidad` o `esta_identidad_reservada`; se adopta `reserva_identidad` por fidelidad de dominio). |
| `esQuerellante`                | `es_querellante`                       | Booleano. |
| `tipoIdentidadId` / `tipoDocumentoId` | `tipo_identidad_id` / `tipo_documento_id` | Catálogo. |
| `numeroDocumento`              | `numero_documento`                     | |
| `nombres`                      | `nombres`                              | |
| `primerApellido` / `segundoApellido` | `primer_apellido` / `segundo_apellido` | |
| `fechaNacimiento`              | `fecha_nacimiento`                     | ISO 8601 (fecha). |
| `sexo`                         | `sexo`                                 | La ficha lo define como bool; se conserva semántica de la contraparte. |
| `razonSocial` / `nit`          | `razon_social` / `nit`                 | Persona jurídica. |
| `polCasoFuncionarioId` / `mpCasoFuncionarioId` | `id_pol_caso_funcionario` / `id_mp_caso_funcionario` | Investigadores/fiscales. |
| `tipoResponsableId`            | `tipo_responsable_id`                  | Catálogo. |
| `situacionJuridicaId`          | `situacion_juridica_id`                | Catálogo. |
| `fechaInicio` / `fechaFin`     | `fecha_inicio` / `fecha_fin`           | ISO 8601. |
| `polCasoPersonaSituacionJuridicaId` / `mpCasoPersonaSituacionJuridicaId` | `id_pol_caso_persona_situacion_juridica` / `id_mp_caso_persona_situacion_juridica` | |
| `polCasoPersonaAbogadoId` / `mpCasoPersonaAbogadoId` | `id_pol_caso_persona_abogado` / `id_mp_caso_persona_abogado` | |
| `codigoRPA`                    | `codigo_rpa`                           | |
| `ci`                           | `ci`                                   | Identificador estable (cédula). |
| `motivoBaja`                   | `motivo_baja`                          | |
| `mpPersonaResidenciaId` / `polPersonaResidenciaId` | `id_mp_persona_residencia` / `id_pol_persona_residencia` | |
| `paisId` / `municipioId`       | `pais_id` / `municipio_id`             | Catálogo. |
| `direccion` / `latitud` / `longitud` | `direccion` / `latitud` / `longitud` | |
| `extCasoActividadId`           | `id_caso_actividad_externo`            | |
| `actividadId`                  | `actividad_id`                         | Catálogo. |
| `referencia`                   | `referencia`                           | |
| `archivoExtension` / `archivoLink` / `archivoTamano` / `archivoPaginas` / `archivoHash` | `archivo_extension` / `archivo_link` / `archivo_tamano` / `archivo_paginas` / `archivo_hash` | |
| `metaData`                     | `metadata`                             | |
| `tipoSolicitudId`              | `tipo_solicitud_id`                    | Catálogo. |
| `tiempoDias` / `tiempoPenaDias` / `tiempoMultaDias` / `tiempoTrabajoDias` | `tiempo_dias` / `tiempo_pena_dias` / `tiempo_multa_dias` / `tiempo_trabajo_dias` | Enteros. |
| `riesgosProcesalesId` / `medidasProteccionId` | `riesgos_procesales_id` / `medidas_proteccion_id` | Arrays de ID de catálogo. |
| `fechaFinReserva`              | `fecha_fin_reserva`                    | ISO 8601. |
| `tipoEventoId` / `tipoSubEventoId` / `juzgadoId` | `tipo_evento_id` / `tipo_sub_evento_id` / `juzgado_id` | Catálogo. |
| `esVirtual`                    | `es_virtual`                           | Booleano. |
| `fechaHoraInicio` / `fechaHoraFin` | `fecha_hora_inicio` / `fecha_hora_fin` | ISO 8601. |
| `estado` (tinyint baja/alta)   | `estado`                               | Se conserva como entero de catálogo de estado de fila; ver nota. |

> Nota sobre `estado` (tinyint): la ficha lo usa como bandera de baja lógica
> (`estado: 0/1`). El hub lo expone como entero por fidelidad con la contraparte,
> pero **internamente** se modela como `esta_activo` (booleano) más una máquina
> de estados propia. La traducción tinyint ↔ booleano vive en el **adaptador
> outbound**, no en el contrato inbound.

> Los **objetos anidados** (`persona_natural`, `persona_juridica`, `metadata`,
> `relatos`, elementos de `delitos`, `sujetos`, `actividades`, etc.) aplican
> exactamente las mismas reglas de nombres recursivamente.

---

## 4. `ApiResponse<T>` es el único contrato externo — `ProblemDetail` interno

Esta sección reemplaza el modelo híbrido de la revisión 1. La nueva regla es
binaria y sin excepciones (salvo capa de transporte, §6.1.a):

### Regla

1. **Todo endpoint y toda capa del stack expone `ApiResponse<T>` al exterior.**
   Sin excepción: éxito de negocio, error de negocio, error de validación, error
   de autenticación/autorización (401/403), error de framework (404/405/415),
   error de routing del gateway (502/503/504) y error interno (500). Todos son
   `ApiResponse` con `success` (`true`/`false`), `correlation_id` y `timestamp`.

2. **`ProblemDetail` puede usarse internamente** como modelo de Spring: el
   framework lo genera de forma nativa en varios paths
   (`ErrorResponseException`, `NoResourceFoundException`,
   `ResponseStatusException`, fallos de Spring Security antes del controlador).
   **Pero nunca sale como respuesta al partner.** Cualquier `ProblemDetail`
   producido internamente debe ser **interceptado y convertido a `ApiResponse`**
   antes de escribir el body. El mapeo es: `ProblemDetail.status` →
   `ApiResponse.status`, `ProblemDetail.detail` → `error.detail`, y un
   `error.code` canónico (§7) derivado del status o de la excepción.

3. **El discriminante para el partner es trivial y único**: el body **siempre**
   tiene `success`. No hay que distinguir formas: si llega algo con `type`/
   `title` (RFC 7807), es un bug a corregir, no un contrato.

### Qué se elimina respecto de la revisión 1

- Se elimina la categoría "errores no controlados / de infraestructura →
  `ProblemDetail`". Ahora también se envuelven en `ApiResponse`.
- Se elimina el requisito de "`correlation_id` en ambos formatos": **solo hay un
  formato** (`ApiResponse`), y `correlation_id` es obligatorio en él.
- El `GlobalExceptionHandler` (microservicio) deja de **emitir** `ProblemDetail`:
  o construye `ApiResponse` directamente, o (si recibe un `ErrorResponseException`
  con un `ProblemDetail` del framework) lo **traduce** a `ApiResponse`.
- Los entry points del gateway (`ProblemDetailAuthEntryPoint`) y el
  `GatewayExceptionHandler` dejan de escribir `application/problem+json`: emiten
  `ApiResponse` en `application/json`. (El nombre de clase
  `ProblemDetailAuthEntryPoint` es heredado; su comportamiento cambia — ver §9.)

### Justificación

- **Un solo modelo de deserialización para el partner** en todo el ciclo de
  vida: éxito, error de negocio, error de plataforma. Reduce el costo de
  integración y los bugs de cliente (no más "a veces es `ProblemDetail`").
- **Trazabilidad uniforme**: `correlation_id` está garantizado en todos los
  caminos porque hay un solo serializador de respuesta por capa.
- **El partner es una institución del Estado** (MP/POL/FELCN) integrando vía un
  proveedor; la simplicidad del contrato pesa más que la adhesión a RFC 7807
  para errores genéricos. RFC 7807 sigue disponible como modelo interno de
  Spring, solo que no se expone.

### Alternativas descartadas

- **Modelo híbrido `ApiResponse` + `ProblemDetail` (revisión 1)**: descartado por
  decisión del equipo. Obligaba al partner a manejar dos formas y dejaba huecos
  de trazabilidad en los caminos de seguridad/transporte del edge.
- **Todo en `ProblemDetail`**: `ProblemDetail` no tiene lugar natural para el
  payload de éxito; forzaría payload desnudo en éxito → vuelve la inconsistencia.
- **Copiar el sobre del MP** (`error/message/response/status`): nos ataría a su
  `camelCase` y a un formato no estándar cuyo `response` cambia de forma entre
  endpoints.

---

## 5. Propagación del `correlation_id` y manejo de errores por capa

El hub tiene dos capas de software en el camino inbound (gateway WebFlux →
microservicio MVC) más la capa de transporte (TLS/Netty) por debajo del gateway.
Cada una debe garantizar `ApiResponse` con `correlation_id` en sus errores.

### 5.1. Orden de filtros (invariante de plataforma)

En **ambas** capas (gateway y microservicio) el orden de ejecución debe ser:

1. **Filtro de correlación** (`HIGHEST_PRECEDENCE`, el primero): lee
   `X-Correlation-ID` del request o **genera uno nuevo** (UUID). Lo almacena en
   **`MDC`** (para logging, patrón `%X{correlationId}`) y en el contexto de la
   petición (atributo del `ServerWebExchange` en el gateway; `request attribute`
   / `MDC` en el microservicio). Escribe el header `X-Correlation-ID` en la
   respuesta. **Debe correr antes de cualquier filtro de seguridad.**
2. **Filtros de seguridad**: mTLS (binding RFC 8705), autenticación OAuth2 (JWT),
   autorización de scope/suscripción.
3. **Filtros de negocio**: rate limit, IP whitelist, domain whitelist.
4. **Routing / controlador**.

Garantía clave: como el filtro de correlación corre **antes** que los filtros de
seguridad, cuando un rechazo de seguridad ocurra dentro del software (HTTP), el
`correlation_id` **ya existe** y puede incluirse en el `ApiResponse` de error.

> Nota sobre el filtro actual: hoy el gateway tiene `RequestIdFilter`
> (`HIGHEST_PRECEDENCE`) que maneja `X-Request-Id`. El **filtro de correlación**
> de este ADR es una responsabilidad distinta (`X-Correlation-ID` como ID maestro
> ligado a `hub_audit_log`). Puede implementarse extendiendo `RequestIdFilter`
> para que también resuelva/genere `X-Correlation-ID` y lo ponga en MDC, o como
> un filtro nuevo con la misma precedencia. Ver §9 (plan).

### 5.2. Excepción: rechazo antes del filtro de correlación

Si el rechazo ocurre **antes** de que el filtro de correlación pueda correr,
hay dos subcasos:

- **Rechazo HTTP muy temprano** (p. ej. un `WebExceptionHandler` con
  `HIGHEST_PRECEDENCE` que captura algo antes de que el filtro de correlación
  haya corrido para esa petición): el handler **genera un `correlation_id` nuevo
  en ese momento** (UUID), lo escribe en el body `ApiResponse` y en el header
  `X-Correlation-ID`, y lo registra en los logs/auditoría. Nunca se emite un
  error sin `correlation_id`.
- **Rechazo de capa de transporte** (TLS handshake): no hay contexto HTTP. Es la
  única excepción al contrato — ver §6.1.a y §8.

---

## 6. Manejo de errores por capa (detalle)

### 6.1. Capa 1 — Gateway (`hub-gateway`, WebFlux)

El gateway es el primer punto de contacto. Tres subcasos.

#### a) Rechazo mTLS (capa de transporte)

El TLS handshake falla cuando el partner **no presenta** un certificado de
cliente o presenta uno **no emitido por la PKI de Vault** (no confiable por el
truststore del gateway). Esto ocurre a nivel de **Netty/SSL**, en el handshake
TLS, **antes** de que exista un `ServerWebExchange`, un filtro HTTP o un request
HTTP parseado.

**Decisión (realista)**: en este punto **es arquitectónicamente imposible emitir
un `ApiResponse`**. No hay request HTTP, no hay response HTTP, no hay body
posible. El cliente recibe un **`connection reset` o un alert TLS**
(`bad_certificate` / `certificate_required` / `handshake_failure`), no un 4xx con
cuerpo JSON.

- Esta es **la única excepción documentada** al contrato "todo es `ApiResponse`".
- **No existe `correlation_id`** para este rechazo (no hubo transacción HTTP).
- El rechazo **sí es auditable** desde los logs del gateway / del listener TLS:
  se registra por **IP de origen y SNI** (y, si el handshake avanzó lo
  suficiente, por el `issuer`/`subject` del cert ofrecido). Esa es la evidencia
  para conciliación/forense de este caso.
- **Manejo esperado del cliente**: tratar `connection reset` / alert TLS en
  `/partner/**` como "certificado de cliente ausente o no confiable"; revisar que
  presenta el cert emitido por la PKI de Vault. Documentar este comportamiento en
  el onboarding del partner (no es un error de aplicación, es de transporte).

> Distinción importante: el **binding RFC 8705** (que el thumbprint del cert
> coincida con `cnf.x5t#S256` del JWT) NO es capa de transporte. Eso ocurre en el
> `MtlsCertBindingFilter` (filtro HTTP, ya hay request) → se emite `ApiResponse`
> 401 normal (ver §6.1.b). Solo el **handshake TLS fallido** cae en este caso (a).

#### b) Rechazo de autenticación/autorización OAuth2 (filtro de seguridad WebFlux)

Token ausente, expirado, firmado por issuer incorrecto, firma inválida, o scope/
binding insuficiente. Ocurre en la **chain de seguridad reactiva** del gateway
(`partnerSecurityChain`, Order=1). En Spring Security Reactive los mecanismos son:

- **`ServerAuthenticationEntryPoint`** → para fallos de **autenticación** (401):
  token ausente/inválido/expirado. Hoy esta responsabilidad la tiene
  `ProblemDetailAuthEntryPoint`; debe **cambiar su salida a `ApiResponse`** (no
  `application/problem+json`). El `error.code` se deriva del motivo:
  - sin token / token inválido → `AUTHENTICATION_REQUIRED` (401).
  - token bien firmado pero expirado → `TOKEN_EXPIRED` (401). (Spring expone el
    motivo en el `OAuth2AuthenticationException` / `BearerTokenError`; el entry
    point inspecciona el `error code` `invalid_token` con descripción de
    expiración para mapearlo.)
- **`ServerAccessDeniedHandler`** → para fallos de **autorización** (403): token
  válido pero scope insuficiente. Debe configurarse en la chain
  (`.exceptionHandling().accessDeniedHandler(...)`) y emitir `ApiResponse` con
  `error.code = INSUFFICIENT_SCOPE` (403). Hoy la chain solo configura
  `authenticationEntryPoint`; **falta** el `accessDeniedHandler` → agregarlo.

**`correlation_id` en estos handlers**: el filtro de correlación
(`HIGHEST_PRECEDENCE`) corre antes que la chain de seguridad, por lo que el ID
**ya está** en el atributo del `ServerWebExchange` / MDC. El entry point y el
access denied handler lo leen de ahí y lo ponen en el `ApiResponse` y en el
header `X-Correlation-ID`. **Si no estuviera** (defensa en profundidad), el
handler genera un UUID nuevo en el momento, lo escribe en body+header y lo logea.

Los filtros de negocio de seguridad propios (`MtlsCertBindingFilter` orden 10,
`PartnerSubscriptionFilter` orden 11) hoy escriben bodies ad-hoc
(`{"error":"..."}`); deben **migrar a emitir `ApiResponse`** con los códigos
canónicos correspondientes:
- `MtlsCertBindingFilter`: cert binding mismatch / cnf ausente / cert ausente →
  `ApiResponse` 401 con `error.code = AUTHENTICATION_REQUIRED` (binding RFC 8705
  fallido es un fallo de autenticación del token enlazado).
- `PartnerSubscriptionFilter`: suscripción/scope no válido → `ApiResponse` 403
  con `error.code = INSUFFICIENT_SCOPE` o `SUBSCRIPTION_INACTIVE` según el caso.

#### c) Errores de routing del gateway (downstream caído, 503, timeout)

Cuando el gateway no puede completar el ruteo (instancia downstream caída, sin
instancias en Consul, timeout, error de red), Spring Cloud Gateway propaga
excepciones del pipeline WebFlux. **`@ControllerAdvice` NO funciona en WebFlux**;
el mecanismo correcto es un **`ErrorWebExceptionHandler`** (o `WebExceptionHandler`)
registrado con alta precedencia. Hoy existe `GatewayExceptionHandler`
(`implements WebExceptionHandler`, `HIGHEST_PRECEDENCE`) que **escribe
`ProblemDetail`**; debe **cambiar su salida a `ApiResponse`**.

Mapeo de excepción → `error.code` / status:

| Excepción / situación                                   | status | `error.code`          |
|---------------------------------------------------------|--------|-----------------------|
| `ConnectException` / `ConnectTimeoutException` / sin instancias en Consul / circuit breaker abierto | 503 | `SERVICE_UNAVAILABLE` |
| Respuesta de error del upstream ya alcanzado (5xx del backend) | 502 | `UPSTREAM_ERROR` |
| `TimeoutException` (el upstream no respondió a tiempo)   | 504    | `UPSTREAM_TIMEOUT`    |
| `IOException` / fallo de red genérico                   | 502    | `UPSTREAM_ERROR`      |
| `ResponseStatusException` con status propio             | (su status) | derivado del status (§7) |
| cualquier otro no mapeado                               | 500    | `INTERNAL_ERROR`      |

> Nota: el `GatewayExceptionHandler` actual mapea `ConnectException` a 502
> `BAD_GATEWAY`. Para alinear con el catálogo de este ADR (donde "downstream
> caído / no disponible" = 503 `SERVICE_UNAVAILABLE` y "upstream alcanzado pero
> falló" = 502 `UPSTREAM_ERROR`), el handler debe reclasificar: backend
> inalcanzable/sin instancias → 503 `SERVICE_UNAVAILABLE`; respuesta de error de
> un backend ya alcanzado → 502 `UPSTREAM_ERROR`. El `correlation_id` se lee del
> exchange (filtro de correlación) o se genera si falta.

El handler debe verificar `response.isCommitted()` (como ya hace) antes de
escribir; si la respuesta ya fue comprometida, no puede reescribir el body.

### 6.2. Capa 2 — Microservicio base (`hub-ms-base`, MVC)

Los requests que llegan aquí ya pasaron el gateway. Dos subcasos.

#### a) `@RestControllerAdvice` (`GlobalExceptionHandler`)

Ya existe (hoy emite `ProblemDetail`). Debe **emitir `ApiResponse`** en todos los
caminos. Responsabilidades:

- **Errores de negocio controlados** (excepciones de dominio del hub) →
  `ApiResponse` con `success=false`, status y `error.code` canónicos:
  `VALIDATION_ERROR` (400, desde `MethodArgumentNotValidException` →
  `violations`), `RESOURCE_NOT_FOUND` (404), `IDEMPOTENCY_CONFLICT` (409),
  `INVALID_STATE_TRANSITION` (409), `SUBSCRIPTION_INACTIVE` /
  `PRODUCT_NOT_AUTHORIZED` (403), `UPSTREAM_ERROR` (502), `UPSTREAM_TIMEOUT`
  (504) cuando el error proviene del adaptador outbound.
- **Errores no controlados (500)** → `ApiResponse` con `success=false`,
  `error.code = INTERNAL_ERROR`. **Ocultar `technical_detail` y `exception_type`
  en perfiles no-dev**: solo se incluyen bajo `local`/`dev`/`test`; en `prod` el
  `error.detail` es un mensaje genérico sin internals. (Hoy el handler los pone
  siempre con un TODO — debe condicionarse al profile.)
- **`ProblemDetail` producido por el framework**: cuando llega un
  `ErrorResponseException` / `NoResourceFoundException` / `ResponseStatusException`
  (que en Spring traen un `ProblemDetail` interno), el advice **lo intercepta y lo
  convierte a `ApiResponse`** (status → `status`, `detail` → `error.detail`,
  `error.code` derivado del status según §7: 404 → `RESOURCE_NOT_FOUND`, 405/415/
  406 → `VALIDATION_ERROR` o el código que corresponda, otros → por status). El
  `ProblemDetail` no se serializa nunca tal cual.
- **`correlation_id`** se obtiene del contexto del request (MDC / request
  attribute puesto por el filtro de correlación del microservicio, que alimenta
  `hub_audit_log`) y se incluye en todo `ApiResponse` de error, junto con
  `timestamp` ISO 8601 con offset.

#### b) Filtro de seguridad del microservicio (Spring Security MVC)

Aunque la autenticación principal la hace el gateway, el microservicio tiene su
propia `SecurityConfiguration` (hoy en MODO DESARROLLO: `permitAll`, JWT
comentado — ver CLAUDE.md). **Antes de prod** debe habilitarse OAuth2 JWT, y en
ese caso, si un request llega con token inválido **directamente** al
microservicio (bypass del gateway en un entorno mal configurado), los 401/403 que
genera Spring Security **no pasan por el `@RestControllerAdvice`** (se producen en
el filter chain, antes del `DispatcherServlet`).

Para garantizar `ApiResponse` también en ese path, el microservicio debe definir:

- **`AuthenticationEntryPoint`** (MVC) → 401 `ApiResponse` con `error.code =
  AUTHENTICATION_REQUIRED` / `TOKEN_EXPIRED`. Se registra en la chain con
  `.exceptionHandling().authenticationEntryPoint(...)`.
- **`AccessDeniedHandler`** (MVC) → 403 `ApiResponse` con `error.code =
  INSUFFICIENT_SCOPE`. Se registra con `.exceptionHandling().accessDeniedHandler(...)`.

Ambos leen el `correlation_id` del MDC/request (filtro de correlación del
microservicio) o generan uno si falta, y lo escriben en body+header. Esto es
**defensa en profundidad**: en operación normal el gateway ya rechazó; este path
solo se ejercita si alguien expone el microservicio directamente.

---

## 7. Catálogo de `error.code` (códigos canónicos del hub)

Códigos estables, en `SCREAMING_SNAKE_CASE`. El partner debe ramificar su lógica
por `error.code`, **no** por `message`. "Origen" indica qué capa puede emitirlo.

| `error.code`              | HTTP | Origen              | Cuándo ocurre |
|---------------------------|------|---------------------|---------------|
| `VALIDATION_ERROR`        | 400  | microservicio       | Bean Validation fallida en el body/params. Lleva `violations`. |
| `AUTHENTICATION_REQUIRED` | 401  | gateway · microservicio | Token ausente, inválido, firma incorrecta, issuer incorrecto, o binding RFC 8705 (cert ↔ `cnf`) fallido. |
| `TOKEN_EXPIRED`           | 401  | gateway · microservicio | Token bien firmado pero expirado. Subcaso de 401 separado para que el cliente sepa que basta renovar el token. |
| `INSUFFICIENT_SCOPE`      | 403  | gateway · microservicio | Token válido y autenticado, pero sin el scope requerido por el producto/endpoint. |
| `SUBSCRIPTION_INACTIVE`   | 403  | gateway · microservicio | El partner/certificado no está mapeado a una suscripción activa del producto. |
| `PRODUCT_NOT_AUTHORIZED`  | 403  | microservicio       | El partner tiene suscripción pero no está autorizado para ese producto/operación específica. |
| `RESOURCE_NOT_FOUND`      | 404  | microservicio       | El recurso de dominio solicitado no existe (o no es visible para el partner). También cubre `NoResourceFoundException` del framework. |
| `IDEMPOTENCY_CONFLICT`    | 409  | microservicio       | `X-Idempotency-Key` reutilizada con un payload distinto. |
| `INVALID_STATE_TRANSITION`| 409  | microservicio       | La operación viola la máquina de estados del dominio (transición no permitida). |
| `UPSTREAM_ERROR`          | 502  | gateway · microservicio | Un upstream **alcanzado** respondió con error (5xx del backend interno desde el gateway, o un proveedor externo desde el adaptador outbound). |
| `SERVICE_UNAVAILABLE`     | 503  | gateway · microservicio | El servicio que atiende la operación no está disponible: instancia caída, sin instancias en Consul, circuit breaker abierto. Reintentable. |
| `UPSTREAM_TIMEOUT`        | 504  | gateway · microservicio | El upstream (backend interno o proveedor externo) no respondió dentro del timeout. |
| `INTERNAL_ERROR`          | 500  | gateway · microservicio | Error no controlado / inesperado. En prod sin internals; reportar citando `correlation_id`. |

Notas:

- La distinción **502 `UPSTREAM_ERROR`** vs **503 `SERVICE_UNAVAILABLE`** vs
  **504 `UPSTREAM_TIMEOUT`** es: *alcanzado pero falló* (502) / *no se pudo
  alcanzar* (503) / *no respondió a tiempo* (504). Importa para la lógica de
  reintento del cliente.
- Para escrituras (`POST/PATCH/PUT/DELETE`), los códigos reintentables
  (`SERVICE_UNAVAILABLE`, `UPSTREAM_TIMEOUT`, a veces `UPSTREAM_ERROR`) deben
  reintentarse **con la misma `X-Idempotency-Key`** para no duplicar efectos
  (ADR-0001 §3, garantía at-least-once + idempotencia).
- El rechazo de capa de transporte mTLS (§6.1.a) **no tiene `error.code`** porque
  no tiene body — el cliente solo ve el fallo de handshake TLS.

---

## 8. Excepción de capa de transporte (rechazo mTLS sin body)

Documentación explícita de la **única** excepción al contrato "todo es
`ApiResponse`":

- **Qué**: el TLS handshake en `/partner/**` falla porque el cliente no presenta
  certificado o presenta uno no confiable (no emitido por la PKI de Vault del
  ADR-0001).
- **Por qué no hay `ApiResponse`**: el fallo ocurre en Netty/SSL **antes** de
  que exista cualquier contexto HTTP (no hay request, no hay response, no hay
  body). No es posible escribir JSON.
- **Qué recibe el cliente**: `connection reset` o un **alert TLS**
  (`bad_certificate`, `certificate_required`, `handshake_failure`). No es un
  status HTTP 4xx con cuerpo.
- **`correlation_id`**: **no existe** para este caso. No hubo transacción HTTP que
  registrar en `hub_audit_log` con un correlation_id.
- **Trazabilidad/auditoría**: el rechazo **sí queda registrado** en los logs del
  listener TLS / gateway por **IP de origen y SNI** (y, si el handshake avanzó,
  por subject/issuer del cert ofrecido). Esa es la evidencia forense de este
  caso, no el body de respuesta.
- **Cómo debe manejarlo el cliente**: en el onboarding del partner se documenta
  que un `connection reset`/alert TLS contra `/partner/**` significa "tu
  certificado de cliente está ausente o no es confiable"; el cliente debe
  verificar que presenta el cert emitido por la PKI de Vault y su clave privada.
  No es un error de aplicación reintentable a nivel HTTP.

Todo lo demás —incluyendo el binding RFC 8705 fallido, que ocurre a nivel de
filtro HTTP cuando ya hay request— **sí** emite `ApiResponse`.

---

## 9. Headers de request/response

Headers HTTP estándar del hub (inbound). Nombres en `X-`-`Kebab-Case`.

| Header              | Dirección        | Obligatorio | Descripción |
|---------------------|------------------|-------------|-------------|
| `X-Correlation-ID`  | request → response | No (request) / **Sí (response)** | UUID de trazabilidad, ID maestro del hub. Si el cliente lo envía, el hub lo **respeta y propaga** (y lo registra en `hub_audit_log.correlation_id`). Si no lo envía o es inválido, el hub **genera uno** y lo devuelve. Siempre presente en la respuesta y siempre igual a `ApiResponse.correlation_id`. Lo resuelve/genera el **filtro de correlación** (`HIGHEST_PRECEDENCE`), antes de cualquier filtro de seguridad. |
| `X-Idempotency-Key` | request          | Sí en operaciones de escritura (POST/PATCH/PUT/DELETE con efecto) | Clave de idempotencia provista por el cliente. El hub la usa para deduplicar y para el `idempotency_key` del outbox (ADR-0001 §3). Reenvío con misma clave + mismo payload → replay de la respuesta original; misma clave + payload distinto → `409 IDEMPOTENCY_CONFLICT`. Para GET es ignorada. |
| `X-Partner-ID`      | response (informativo) | response | Identificador del partner resuelto por el hub a partir del **CN del certificado mTLS** / `sub` del JWT enlazado (lo propaga `MtlsCertBindingFilter` como `X-Partner-Id` downstream). El hub lo **deriva del certificado/token, no confía en un valor enviado por el cliente**: si el cliente lo envía, se ignora y se sobreescribe. Útil para que el partner confirme bajo qué identidad fue atendido. |
| `X-Request-ID`      | request → response | No | ID de request del cliente para sus propios logs (hoy lo maneja `RequestIdFilter`). Si viene, se hace eco en la respuesta. No reemplaza a `X-Correlation-ID` (ID maestro del hub). |
| `Idempotency-Key`   | request (alias) | No | Alias sin prefijo `X-` aceptado por compatibilidad con clientes que sigan el draft IETF; si ambos vienen, prevalece `X-Idempotency-Key`. |

Notas de seguridad y trazabilidad:

- `X-Correlation-ID` y `X-Idempotency-Key` se incorporan al registro de auditoría
  (hash-chain del ADR-0001) para reproducibilidad y conciliación.
- El `correlation_id` del **body** (`ApiResponse.correlation_id`) y el header
  `X-Correlation-ID` **siempre coinciden**; el body es la fuente que el partner
  debe citar en incidencias (sobrevive a logs que no capturan headers).
- `X-Partner-ID` derivado del certificado evita que un cliente suplante su
  identidad por header; la identidad real es la del CN/SAN del cert mTLS enlazado
  al token (RFC 8705).
- En el rechazo de capa de transporte (§8) **ninguno** de estos headers existe en
  la respuesta porque no hay respuesta HTTP.

---

## Consecuencias

**Positivas:**

- **Un solo modelo de respuesta** (`ApiResponse<T>`) en todo el ciclo de vida y
  en todas las capas: el partner deserializa un único tipo para éxito, error de
  negocio, error de seguridad y error de plataforma. Discriminante trivial:
  siempre hay `success`.
- **Trazabilidad garantizada extremo a extremo** vía `correlation_id` en body y
  header en todos los caminos de software, cruzable con `hub_audit_log`.
- Nombres canónicos `snake_case` desacoplan el contrato del hub del `camelCase`
  del MP; el adaptador outbound absorbe la traducción.
- Catálogos del MP se exponen como ID sin reinterpretarlos, evitando deuda de
  mantener mapeos de catálogos que no poseemos.
- Catálogo de `error.code` estable y documentado → el cliente puede automatizar
  lógica de reintento (códigos reintentables vs. definitivos).

**Negativas / costos:**

- Hay que **modificar todos los serializadores de error existentes** para que
  emitan `ApiResponse` en vez de `ProblemDetail`: `GlobalExceptionHandler`
  (microservicio), `ProblemDetailAuthEntryPoint` (gateway, nombre heredado pero
  comportamiento cambia), `GatewayExceptionHandler` (gateway), y los bodies
  ad-hoc de `MtlsCertBindingFilter` / `PartnerSubscriptionFilter`.
- Se **renuncia a RFC 7807** como contrato externo (tooling estándar que espera
  `application/problem+json` no aplica). Aceptado: el partner es una institución
  integrando vía proveedor; prima la simplicidad de un solo sobre.
- El rechazo de capa de transporte mTLS **no puede** dar un body `ApiResponse`;
  hay que documentarlo en el onboarding del partner como excepción conocida.
- Mantener el **diccionario de mapeo** y la traducción en el adaptador es trabajo
  recurrente cada vez que la ficha del MP cambie.
- Convención `snake_case` exige configurar la serialización JSON del hub
  (estrategia de nombres) de forma consistente para no mezclar estilos.

---

## Plan de implementación por fases

1. **Modelo del sobre**: definir `ApiResponse<T>`, sub-objeto `error`,
   `Violation` (`field`/`message`) y `Page<T>` como tipos del hub (idealmente en
   un módulo/paquete compartido reusable por gateway y microservicio); fijar la
   estrategia de serialización JSON a `snake_case` para los DTOs inbound.
2. **Catálogo de `error.code`**: materializar el catálogo de §7 como
   enum/constantes con su HTTP status asociado, compartido entre capas.
3. **Filtro de correlación**: implementar/extender el filtro
   `HIGHEST_PRECEDENCE` que resuelve/genera `X-Correlation-ID`, lo pone en MDC y
   en el contexto de la petición, y lo escribe en el header de respuesta — en
   **gateway** (WebFlux `WebFilter`) y en **microservicio** (servlet `Filter` /
   `OncePerRequestFilter`). Debe correr antes de seguridad.
4. **Gateway — errores de seguridad**: reescribir `ProblemDetailAuthEntryPoint`
   (`ServerAuthenticationEntryPoint`) para emitir `ApiResponse`
   (`AUTHENTICATION_REQUIRED`/`TOKEN_EXPIRED`); agregar un
   `ServerAccessDeniedHandler` que emita `ApiResponse` (`INSUFFICIENT_SCOPE`) y
   registrarlo en `partnerSecurityChain` (y en `adminSecurityChain` la parte API).
   Migrar los bodies ad-hoc de `MtlsCertBindingFilter` y
   `PartnerSubscriptionFilter` a `ApiResponse`.
5. **Gateway — errores de routing**: reescribir `GatewayExceptionHandler`
   (`WebExceptionHandler`/`ErrorWebExceptionHandler`) para emitir `ApiResponse`
   con el mapeo de §6.1.c (reclasificar 502/503/504), leyendo `correlation_id`
   del exchange o generándolo si falta.
6. **Microservicio — `GlobalExceptionHandler`**: reescribir para emitir
   `ApiResponse` en todos los caminos; interceptar y convertir `ProblemDetail`
   del framework (`ErrorResponseException`, `NoResourceFoundException`); ocultar
   `technical_detail`/`exception_type` fuera de `local/dev/test`; añadir
   `correlation_id` y `timestamp` ISO 8601 con offset.
7. **Microservicio — seguridad MVC**: al habilitar OAuth2 JWT (hoy en MODO
   DESARROLLO), definir `AuthenticationEntryPoint` y `AccessDeniedHandler` que
   emitan `ApiResponse` (defensa en profundidad para bypass del gateway).
8. **Excepciones de dominio**: introducir excepciones de negocio del hub que el
   advice traduzca a `ApiResponse` con `success=false` y el `error.code`
   canónico correcto.
9. **Idempotencia + outbox**: conectar `X-Idempotency-Key` con el outbox
   (ADR-0001) y el replay idempotente; emitir `IDEMPOTENCY_CONFLICT` en colisión.
10. **DTOs de dominio penal**: crear los DTOs inbound (caso, delitos, sujetos,
    etc.) con los nombres canónicos del diccionario; concentrar la traducción
    hacia/desde el MP en el adaptador outbound (ACL).
11. **OpenAPI**: publicar el contrato (`ApiResponse<T>`, `Page<T>`, catálogo de
    `error.code`, headers) versionado por producto en springdoc. `ProblemDetail`
    **no** debe aparecer en el contrato publicado.
12. **Onboarding del partner**: documentar el caso de rechazo de transporte mTLS
    (§8) como excepción conocida sin body, y la guía de reintento por
    `error.code`.
