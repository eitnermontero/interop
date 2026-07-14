# Hub de Interoperabilidad FELCN — Guía de Integración para la Fiscalía

**Versión:** 1.1
**Hub (proveedor):** FELCN — Fuerza Especial de Lucha Contra el Narcotráfico (Bolivia)
**Partner (consumidor):** Fiscalía General del Estado Plurinacional de Bolivia
**Ambiente:** Desarrollo / Pruebas
**Fecha:** 2026-06-30 (actualizado 2026-07-14 — ver aviso abajo)

---

> ⚠️ **Aviso (2026-07-14):** los endpoints de **escritura** (`POST`/`PATCH` de
> `CASO_PENAL` y sus sub-entidades — delitos, sujetos, abogados, fiscales,
> actividades, agendas, etc.) **ya no están disponibles**: el backend de la
> FELCN dejó de implementarlos y responde `404`. Si su integración registraba
> o actualizaba casos penales, **va a empezar a fallar** — ver §7.5. Hoy el Hub
> solo expone **consultas de solo lectura**: operativos, seguimientos y
> catálogos (§7.1 a §7.4).

## 1. Resumen

La **FELCN de Bolivia** expone su **Hub de Interoperabilidad**: una API REST segura
para que sistemas externos autorizados consulten información de los sistemas
internos de la FELCN.

La **Fiscalía General del Estado** (partner) consume este Hub para **consultar**
operativos, seguimientos y catálogos. La comunicación exige **doble factor de
seguridad**: certificado de cliente (**mTLS**) + token **OAuth2** ligado a ese
certificado.

**Servidor de pruebas:** `https://desarrollo.felcn.gob.bo`
**Identificador del cliente (client_id):** `fiscalia-bol-api`

> Esta guía describe **el contrato y cómo probar la integración**. La forma de
> integrar el Hub dentro de los sistemas de la Fiscalía (lenguaje, framework,
> arquitectura) la define el equipo de desarrollo de la Fiscalía.

---

## 2. Endpoints disponibles

| Operación | Método | URL |
|-----------|--------|-----|
| Obtener token | `POST` | `https://desarrollo.felcn.gob.bo/interop/oauth2/token` |
| Consultar operativos (listado) | `GET` | `https://desarrollo.felcn.gob.bo/interop/v1/inbound/OPERATIVO/v1` |
| Consultar operativo (detalle) | `GET` | `https://desarrollo.felcn.gob.bo/interop/v1/inbound/OPERATIVO_DETALLE/v1?cud=...` |
| Consultar seguimientos (listado) | `GET` | `https://desarrollo.felcn.gob.bo/interop/v1/inbound/SEGUIMIENTO/v1` |
| Consultar seguimiento (detalle) | `GET` | `https://desarrollo.felcn.gob.bo/interop/v1/inbound/SEGUIMIENTO_DETALLE/v1?cud=...` |
| Consultar catálogos (16 disponibles) | `GET` | `https://desarrollo.felcn.gob.bo/interop/v1/inbound/CATALOGO_.../v1` |
| Documentación de referencia (Swagger) | `GET` | `https://desarrollo.felcn.gob.bo/interop/docs/` |

> ~~Registrar/actualizar caso penal (`POST`/`PATCH CASO_PENAL`)~~ — **discontinuado**, ver §7.5.

---

## 3. Autenticación y Seguridad (mTLS + OAuth2)

El Hub utiliza **doble factor de seguridad**:

1. **Certificado de cliente (mTLS)** — cada request debe presentar el certificado
   digital entregado por la FELCN. Sin certificado válido, la petición se rechaza
   en el borde del Hub (no llega a la aplicación).
2. **Bearer token OAuth2** — adicionalmente, cada llamada requiere un JWT obtenido
   con `client_credentials`.

El token queda **criptográficamente ligado al certificado** (RFC 8705): un token
robado sin la clave privada del certificado es inútil.

### 3.0 Orden de validación en el servidor

Cada petición atraviesa los controles **en este orden**. El primero que falle corta la petición:

**Al obtener el token (`POST /interop/oauth2/token`):**
1. **mTLS** — se valida el certificado de cliente contra la CA de la FELCN (punto único de terminación TLS).
2. **Credenciales** — se validan `client_id` y `client_secret`.
3. **Enlace token↔certificado** — el token se emite ya ligado al certificado presentado (`cnf.x5t#S256`).

**Al llamar la API de negocio (`GET /interop/v1/inbound/...`):**
1. **mTLS** — se valida el certificado de cliente contra la CA (mismo punto único).
2. **JWT** — se valida el token (firma, emisor, expiración).
3. **Binding RFC 8705** — el certificado de ESTA conexión debe ser el mismo al que está ligado el token. Si no coincide → `401`.
4. **Suscripción / autorización** del partner para el producto.
5. **Registro de la petición** (auditoría) y **validación del contrato**; recién entonces se reenvía al sistema destino.

> En resumen: **primero mTLS (un solo punto), después el JWT, y recién entonces se procesa/registra la petición.**

### 3.1 Credenciales y certificados entregados

La FELCN le entrega los siguientes archivos y datos (**confidenciales**):

| Elemento | Descripción |
|----------|-------------|
| `fiscalia-bol-api.crt` | Certificado público X.509 del partner |
| `fiscalia-bol-api.key` | Clave privada (¡tratar como contraseña!) |
| `fiscalia-bol-api.p12` | Mismo cert + clave en formato PKCS#12 (password: entregado aparte) |
| `hub-ca.crt` | Certificado de la CA del Hub (para confiar en el servidor) |
| `client_id` | `fiscalia-bol-api` |
| `client_secret` | Entregado por la FELCN por canal seguro (no incluido en esta guía) |

> **Seguridad:** Guarde `fiscalia-bol-api.key`, el `.p12` y el `client_secret` con
> acceso restringido. Nunca los comparta, envíe por email ni los incluya en
> repositorios de código. Si se comprometen, notifique a la FELCN para revocarlos.

### 3.2 Obtener token (con certificado de cliente)

**Todos** los requests al Hub deben incluir el certificado de cliente. Si no lo
presenta, la conexión es rechazada.

```http
POST /interop/oauth2/token
Content-Type: application/x-www-form-urlencoded
[TLS: certificado de cliente fiscalia-bol-api]

grant_type=client_credentials&client_id=fiscalia-bol-api&client_secret=<CLIENT_SECRET>&scope=https://api.sintesis.com.bo/caso.penal
```

> ⚠️ **El `scope` es OBLIGATORIO** — está configurado como *optional scope* en
> Keycloak, no *default*. Si lo omite, el token se emite igual (200) pero sin
> el scope de negocio, y **todas** las llamadas a `/interop/v1/inbound/...`
> responden `403 SUBSCRIPTION_INACTIVE` (el token solo trae `email profile`).

**Respuesta exitosa (200):**
```json
{
    "access_token": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
    "expires_in": 300,
    "token_type": "Bearer"
}
```

> **Importante:** El token expira en **300 segundos (5 minutos)**. Renuévelo antes
> de que expire.

### 3.3 Usar el token en las APIs

Incluya el token **y** el certificado en todas las llamadas:

```http
Authorization: Bearer <access_token>
[TLS: certificado de cliente fiscalia-bol-api]
```

### 3.4 Prueba rápida con cURL (referencia)

```bash
CERT="./fiscalia-bol-api.crt"
KEY="./fiscalia-bol-api.key"
BASE="https://desarrollo.felcn.gob.bo"

# 1) Obtener token (presentando el certificado de cliente)
# El scope es OBLIGATORIO (optional scope en Keycloak) — sin él, el token
# se emite pero sin permiso de negocio y todo lo demás responde 403.
curl -s -X POST "$BASE/interop/oauth2/token" \
  --cert "$CERT" --key "$KEY" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=client_credentials&client_id=fiscalia-bol-api&client_secret=<CLIENT_SECRET>&scope=https://api.sintesis.com.bo/caso.penal'

# 2) Llamar la API (token + certificado) — consulta de solo lectura, sin body
curl -s -X GET "$BASE/interop/v1/inbound/OPERATIVO/v1?pagina=1&limite=10" \
  --cert "$CERT" --key "$KEY" \
  -H "Authorization: Bearer <ACCESS_TOKEN>"
```

> El `.crt`/`.key` PEM sirven para cURL y para la mayoría de librerías HTTP. Para
> stacks que requieran PKCS#12 (p. ej. Java), use `fiscalia-bol-api.p12`.

---

## 4. Probar la integración con Postman

La colección `Hub_Interop_Partner.postman_collection.json` ya trae las peticiones
listas. Para que funcionen, hay que **cargar el certificado de cliente en Postman**
(el certificado es TLS, no un header — se configura una sola vez).

### 4.1 Importar la colección

1. Abrir Postman → **Import** → seleccionar `Hub_Interop_Partner.postman_collection.json`.
2. La colección aparece con las carpetas **Autenticación**, **Gestión de
   Operativos**, **Gestión de Seguimiento**, **Catálogos** (16 requests) y
   **Casos Penales (DESCONTINUADO)**.

> ⚠️ La carpeta **Casos Penales** quedó **discontinuada** (§7.5) — sus 3
> requests (`Registrar`/`Actualizar`/`Validación`) van a responder `404`. Se
> conserva solo como referencia histórica del contrato.

### 4.2 Configurar el certificado de cliente (mTLS)

1. Postman → **Settings** (⚙) → pestaña **Certificates**.
2. Activar **CA Certificates** → **PEM file**: seleccionar `hub-ca.crt` (opcional pero recomendado).
3. **Client Certificates** → **Add Certificate**:
   - **Host:** `desarrollo.felcn.gob.bo`
   - **Port:** `443`
   - **CRT file:** `fiscalia-bol-api.crt`
   - **KEY file:** `fiscalia-bol-api.key`
   - **Passphrase:** (dejar vacío; la `.key` no tiene passphrase)
4. **Add** / **Save**. Postman adjuntará el certificado automáticamente a todas las
   peticiones hacia ese host.

> Alternativa con `.p12`: algunos entornos de Postman permiten cargar el PKCS#12.
> Si usa esa opción, la contraseña del `.p12` es la entregada por la FELCN.

### 4.3 Cargar las credenciales (variables de la colección)

1. Clic en la colección → pestaña **Variables**.
2. Completar:
   - `base_url` = `https://desarrollo.felcn.gob.bo` (ya viene)
   - `client_id` = `fiscalia-bol-api` (ya viene)
   - `client_secret` = **el secret entregado por la FELCN**
3. **Save**.

### 4.4 Ejecutar

1. Ejecutar **Autenticación → Obtener Token**. Si el certificado y el secret son
   correctos, responde `200` y el token se guarda automáticamente en la variable
   `{{token}}` (las demás requests ya usan `Bearer {{token}}` heredado a nivel
   de colección — no hay que tocar nada más).
2. Ejecutar cualquier request de **Gestión de Operativos**/**Gestión de
   Seguimiento**/**Catálogos**. Para "Detalle de operativo/seguimiento",
   reemplace la variable de colección `cud_ejemplo` por un `cud` real del
   entorno de pruebas (pestaña **Variables** de la colección).

### 4.5 Errores comunes en Postman

| Síntoma | Causa probable | Solución |
|---------|----------------|----------|
| `Error: socket hang up` / `SSL` al obtener token | Certificado de cliente no configurado para el host | Repetir paso 4.2 (Host exacto `desarrollo.felcn.gob.bo`, puerto 443) |
| `401` en el token | `client_secret` incorrecto | Verificar la variable `client_secret` |
| `401` en la API de negocio | Token expirado, o el certificado no coincide con el token | Volver a ejecutar **Obtener Token** con el mismo certificado |
| `403 SUBSCRIPTION_INACTIVE` | El token se pidió **sin `scope`** — Keycloak lo emite igual pero solo con `email profile` | Volver a ejecutar **Obtener Token** (ya incluye `scope=https://api.sintesis.com.bo/caso.penal` en la colección) |
| `403 PRODUCT_NOT_AUTHORIZED` | Producto/versión no autorizado | Verificar la URL (ej. `/OPERATIVO/v1`) — `CASO_PENAL` está discontinuado, ver §7.5 |

---

## 5. Cabeceras de trazabilidad

| Cabecera | Requerida | Descripción |
|----------|-----------|-------------|
| `Authorization` | ✅ Sí | `Bearer <token>` |
| `X-Correlation-ID` | Recomendada | UUID único por request. Si se omite, el hub genera uno. Se retorna en la respuesta. |

> `Content-Type`/`X-Idempotency-Key` no aplican hoy: las APIs vigentes son
> todas `GET` (sin body, idempotentes por naturaleza) — ver §7.5 para el
> contrato de escritura descontinuado que sí las exigía.

---

## 6. Formato de respuesta

Todas las respuestas siguen el mismo envelope:

**Éxito** (`GET OPERATIVO_DETALLE/v1?cud=...`):
```json
{
    "success": true,
    "status": 200,
    "message": "Aceptado por el destino 'backend-fiscalia'",
    "data": { "cud": "7854695124574", "numero_caso": "LP-A -2/26", "..." : "..." },
    "correlationId": "550e8400-e29b-41d4-a716-446655440000",
    "timestamp": "2026-07-14T10:30:05.123-04:00"
}
```

**Error** (falta el query param requerido `cud`):
```json
{
    "success": false,
    "status": 400,
    "message": "Error de validación",
    "error": {
        "code": "VALIDATION_ERROR",
        "message": "El payload no cumple el contrato OPERATIVO_DETALLE/v1",
        "violations": [
            {"field": "cud", "message": "El campo es requerido"}
        ]
    },
    "correlationId": "550e8400-e29b-41d4-a716-446655440000",
    "timestamp": "2026-07-14T10:30:05.123-04:00"
}
```

### Códigos de respuesta

| Código | Significado | Acción recomendada |
|--------|-------------|-------------------|
| 200 | Operación exitosa | — |
| 400 | Query param inválido/ausente | Revisar `error.violations` y corregir el parámetro |
| 401 | Token ausente/expirado, o certificado no coincide con el token | Renovar token con el mismo certificado y reintentar |
| 403 | Producto no autorizado | Verificar `product` y `version` en la URL |
| 503 | Servicio no disponible | Reintentar con backoff exponencial |

---

## 7. Contrato de las APIs vigentes

Todo lo de esta sección es **`GET`, de solo lectura** — sin body, sin
`X-Idempotency-Key` (GET es idempotente por naturaleza).

### 7.1 Consultar operativos — listado (`GET /interop/v1/inbound/OPERATIVO/v1`)

Los parámetros van como **query string**, todos opcionales:

| Parámetro | Tipo | Requerido | Descripción |
|-----------|------|-----------|-------------|
| `pagina` | string | — | Número de página (≥1) |
| `limite` | string | — | Resultados por página (10 a 50) |
| `filtro` | string | — | Texto libre de búsqueda |
| `orden` | string | — | Criterio de orden |

```bash
curl -s --cert "$CERT" --key "$KEY" -H "Authorization: Bearer $TOKEN" \
  "$BASE/interop/v1/inbound/OPERATIVO/v1?pagina=1&limite=10"
```

### 7.2 Consultar operativo — detalle (`GET /interop/v1/inbound/OPERATIVO_DETALLE/v1?cud=...`)

`cud` va como **query param**, no como segmento de la URL:

| Parámetro | Tipo | Requerido | Descripción |
|-----------|------|-----------|-------------|
| `cud` | string | ✅ | Código Único de Denuncia del operativo a consultar |

```bash
curl -s --cert "$CERT" --key "$KEY" -H "Authorization: Bearer $TOKEN" \
  "$BASE/interop/v1/inbound/OPERATIVO_DETALLE/v1?cud=7854695124574"
```

Si falta `cud`, responde `400 VALIDATION_ERROR`.

### 7.3 Consultar seguimientos — listado y detalle

Mismo contrato que operativos (§7.1/§7.2), reemplazando el producto:

- Listado: `GET /interop/v1/inbound/SEGUIMIENTO/v1` (mismos params `pagina`/`limite`/`filtro`/`orden`)
- Detalle: `GET /interop/v1/inbound/SEGUIMIENTO_DETALLE/v1?cud=...`

### 7.4 Catálogos (sin cambios)

Los 16 catálogos de solo lectura (`CATALOGO_UNIDADES`, `CATALOGO_ESTADOS`, etc.)
siguen igual: `GET /interop/v1/inbound/CATALOGO_.../v1`, sin parámetros. Ver el
Swagger (`/interop/docs/`) para el listado completo.

### 7.5 Descontinuado — Contrato CASO_PENAL/v1 (escritura)

> ⚠️ **Ya no está disponible** (verificado 2026-07-14: el backend responde
> `404 Cannot POST/PATCH`). Se documenta el contrato histórico por si su
> integración lo tenía implementado y necesita entender por qué dejó de
> funcionar. **No lo use para integraciones nuevas.**

Antes se podía **registrar** un caso penal con `POST /interop/v1/inbound/CASO_PENAL/v1`
(campos `cud`, `id_externo_caso`, `id_tipo_denuncia`, `id_oficina`, `id_estado`,
`id_etapa` requeridos, más datos del hecho opcionales) y **actualizarlo** con
`PATCH /interop/v1/inbound/CASO_PENAL/v1/{id}` (`{id}` = `id_pol_caso` asignado
al registrar). Consulte el historial de este archivo (versión 1.0) para el
detalle completo de campos si lo necesita como referencia.

---

## 8. Buenas prácticas de integración

- **Gestión del token:** reutilice el token mientras esté vigente y renuévelo unos
  segundos antes de `expires_in`. No solicite un token nuevo por cada request.
- **Manejo de errores:**
  - `400` → leer `error.violations` para identificar los campos incorrectos.
  - `401` → obtener un nuevo token (con el mismo certificado) y reintentar **una vez**.
  - `503` → reintentar con backoff exponencial (1s, 2s, 4s), máximo 3 intentos.
- **Idempotencia:** use un UUID fijo por operación de negocio en `X-Idempotency-Key`.
  En un reintento por timeout, use **el mismo UUID** para evitar duplicados.
- **Certificado:** mantenga la clave privada protegida; monitoree la fecha de
  expiración del certificado y coordine su renovación con la FELCN con antelación.

> La elección de lenguaje, librerías y arquitectura de integración es
> responsabilidad del equipo de desarrollo de la Fiscalía. Esta guía define
> únicamente el contrato, la seguridad y cómo probar.

---

## 9. Gestión y seguridad de las credenciales

El partner posee **dos credenciales** con las que se autentica ante el Hub: el
**certificado de cliente** (`.crt`/`.key`/`.p12`) y el **`client_secret`**. Ambas
son responsabilidad de la Fiscalía mientras estén en su poder.

### 9.1 Si un certificado o el client_secret se ve comprometido

Se considera compromiso: la clave privada (`.key`/`.p12`) o el `client_secret` se
filtran, se pierden, o hay sospecha de uso no autorizado.

1. **Notifique de inmediato** a la FELCN (contacto técnico) por canal seguro.
2. **Deje de usar** la credencial comprometida.
3. La FELCN **revocará el certificado y/o rotará el `client_secret`**. A partir de
   ese momento la credencial comprometida deja de funcionar (las conexiones son
   rechazadas y no se emiten nuevos tokens).
4. La FELCN le entregará la credencial de reemplazo por canal seguro.

> Un token robado por sí solo es inútil sin la clave privada del certificado
> (RFC 8705); aun así, ante cualquier sospecha, notifique y rote.

### 9.2 Rotación del client_secret

- La FELCN puede rotar el `client_secret` **periódicamente** (recomendado cada
  ~90 días) y **obligatoriamente** tras un incidente o cambio del personal con
  acceso.
- Cuando se rote, la FELCN entrega el nuevo secret por canal seguro; actualice la
  variable `client_secret` en su configuración/Postman. Si se requiere continuidad,
  se coordina una ventana de transición.

### 9.3 Vigencia y renovación del certificado

- El certificado tiene una vigencia limitada (**825 días**). Coordine su renovación
  con la FELCN **antes** de que expire para evitar cortes de servicio.

### 9.4 Condiciones por las que la FELCN puede suspender el acceso

La FELCN puede **suspender o dar de baja** la interoperabilidad de la Fiscalía
cuando ocurra alguno de estos supuestos:

- Fin del convenio o del propósito de la integración.
- Uso indebido, exceso de límites o actividad anómala/abusiva.
- Incidente de seguridad no remediado o compromiso reiterado.
- Requerimiento legal o instrucción de autoridad competente.
- Certificado expirado sin renovación.

El corte puede aplicarse revocando el certificado (mTLS), deshabilitando el cliente
(no se emiten tokens) y/o suspendiendo la suscripción (`403`). Toda acción queda
auditada.

---

## 10. Recursos

| Recurso | URL / Archivo |
|---------|-----|
| Documentación de referencia (Swagger) — **solo lectura** | https://desarrollo.felcn.gob.bo/interop/docs/ |
| Colección Postman | `Hub_Interop_Partner.postman_collection.json` |
| Certificado del partner | `fiscalia-bol-api.crt`, `fiscalia-bol-api.key`, `fiscalia-bol-api.p12` |
| CA del Hub (FELCN) | `hub-ca.crt` |
| Contacto técnico | Equipo Hub de Interoperabilidad — FELCN |

> **Swagger no puede ejecutar peticiones**: como toda la API exige certificado de
> cliente (mTLS) y el navegador no lo adjunta en "Try it out", cualquier petición
> desde Swagger fallará. Úselo para consultar contratos y campos; **ejecute las
> pruebas con Postman** (sección 4) o cURL.
