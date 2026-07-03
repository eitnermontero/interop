# Hub de Interoperabilidad FELCN — Guía de Integración para la Fiscalía

**Versión:** 1.0
**Hub (proveedor):** FELCN — Fuerza Especial de Lucha Contra el Narcotráfico (Bolivia)
**Partner (consumidor):** Fiscalía General del Estado Plurinacional de Bolivia
**Ambiente:** Desarrollo / Pruebas
**Fecha:** 2026-06-30

---

## 1. Resumen

La **FELCN de Bolivia** expone su **Hub de Interoperabilidad**: una API REST segura
para que sistemas externos autorizados registren y actualicen recursos en los
sistemas internos de la FELCN.

La **Fiscalía General del Estado** (partner) consume este Hub para registrar y
actualizar casos penales. La comunicación exige **doble factor de seguridad**:
certificado de cliente (**mTLS**) + token **OAuth2** ligado a ese certificado.

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
| Registrar caso penal | `POST` | `https://desarrollo.felcn.gob.bo/interop/v1/inbound/CASO_PENAL/v1` |
| Actualizar caso penal | `PATCH` | `https://desarrollo.felcn.gob.bo/interop/v1/inbound/CASO_PENAL/v1/{id}` |
| Documentación de referencia (Swagger) | `GET` | `https://desarrollo.felcn.gob.bo/interop/docs/` |

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

**Al llamar la API de negocio (`POST/PATCH /interop/v1/inbound/...`):**
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

grant_type=client_credentials&client_id=fiscalia-bol-api&client_secret=<CLIENT_SECRET>
```

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
curl -s -X POST "$BASE/interop/oauth2/token" \
  --cert "$CERT" --key "$KEY" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=client_credentials&client_id=fiscalia-bol-api&client_secret=<CLIENT_SECRET>'

# 2) Llamar la API (token + certificado)
curl -s -X POST "$BASE/interop/v1/inbound/CASO_PENAL/v1" \
  --cert "$CERT" --key "$KEY" \
  -H "Authorization: Bearer <ACCESS_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{ "cud": "...", "id_externo_caso": 1, "id_tipo_denuncia": 1, "id_oficina": 1, "id_estado": 1, "id_etapa": 1 }'
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
2. La colección aparece con las carpetas **Autenticación** y **Casos Penales**.

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
   `{{token}}`.
2. Ejecutar **Casos Penales → Registrar Caso Penal**. Debe responder `201` con el
   `id_pol_caso` asignado (se guarda en `{{ultimo_id_pol_caso}}`).
3. Ejecutar **Actualizar Caso Penal** — usa el `id` del caso creado.

### 4.5 Errores comunes en Postman

| Síntoma | Causa probable | Solución |
|---------|----------------|----------|
| `Error: socket hang up` / `SSL` al obtener token | Certificado de cliente no configurado para el host | Repetir paso 4.2 (Host exacto `desarrollo.felcn.gob.bo`, puerto 443) |
| `401` en el token | `client_secret` incorrecto | Verificar la variable `client_secret` |
| `401` en la API de negocio | Token expirado, o el certificado no coincide con el token | Volver a ejecutar **Obtener Token** con el mismo certificado |
| `403` | Producto/versión no autorizado | Verificar la URL `/CASO_PENAL/v1` |

---

## 5. Cabeceras de trazabilidad

| Cabecera | Requerida | Descripción |
|----------|-----------|-------------|
| `Authorization` | ✅ Sí | `Bearer <token>` |
| `Content-Type` | ✅ Sí | `application/json` |
| `X-Correlation-ID` | Recomendada | UUID único por request. Si se omite, el hub genera uno. Se retorna en la respuesta. |
| `X-Idempotency-Key` | Recomendada | UUID único por operación de negocio. Permite reintentar de forma segura sin duplicar. |

---

## 6. Formato de respuesta

Todas las respuestas siguen el mismo envelope:

**Éxito:**
```json
{
    "success": true,
    "status": 201,
    "message": "Caso aceptado",
    "data": { "id_pol_caso": 12345 },
    "correlationId": "550e8400-e29b-41d4-a716-446655440000",
    "timestamp": "2026-06-30T10:30:05.123-04:00"
}
```

**Error:**
```json
{
    "success": false,
    "status": 400,
    "message": "Error de validación",
    "error": {
        "code": "VALIDATION_ERROR",
        "message": "El payload no cumple el contrato CASO_PENAL/v1",
        "violations": [
            {"field": "cud", "message": "El campo es requerido"},
            {"field": "id_oficina", "message": "El campo es requerido"}
        ]
    },
    "correlationId": "550e8400-e29b-41d4-a716-446655440000",
    "timestamp": "2026-06-30T10:30:05.123-04:00"
}
```

### Códigos de respuesta

| Código | Significado | Acción recomendada |
|--------|-------------|-------------------|
| 201 | Recurso creado correctamente | Guardar `id_pol_caso` retornado |
| 200 | Operación exitosa | — |
| 400 | Payload inválido | Revisar `error.violations` y corregir campos |
| 401 | Token ausente/expirado, o certificado no coincide con el token | Renovar token con el mismo certificado y reintentar |
| 403 | Producto no autorizado | Verificar `product` y `version` en la URL |
| 503 | Servicio no disponible | Reintentar con backoff exponencial |

---

## 7. Contrato CASO_PENAL/v1

### 7.1 Registrar caso — `POST /interop/v1/inbound/CASO_PENAL/v1`

| Campo | Tipo | Requerido | Máx. longitud | Descripción |
|-------|------|-----------|---------------|-------------|
| `cud` | string | ✅ | 50 | Código Único de Denuncia |
| `id_externo_caso` | integer | ✅ | — | ID del caso en el sistema de la Fiscalía |
| `id_tipo_denuncia` | integer | ✅ | — | Tipo de denuncia (catálogo) |
| `id_oficina` | integer | ✅ | — | ID de la oficina registrante |
| `id_estado` | integer | ✅ | — | Estado del caso (catálogo) |
| `id_etapa` | integer | ✅ | — | Etapa procesal (catálogo) |
| `id_externo_caso_referencia` | integer | — | — | ID caso padre/referencia |
| `es_reservado` | boolean | — | — | Si el caso es reservado |
| `id_municipio` | integer | — | — | ID del municipio |
| `zona` | string | — | 255 | Zona geográfica |
| `direccion` | string | — | — | Dirección del hecho |
| `latitud` | string | — | 30 | Latitud decimal (ej. `-16.500000`) |
| `longitud` | string | — | 30 | Longitud decimal (ej. `-68.150000`) |
| `referencia` | string | — | — | Referencia de ubicación |
| `relato` | string | — | — | Relato del hecho |
| `fecha_caso` | string (ISO 8601) | — | — | Fecha y hora del hecho (ej. `2026-06-30T10:30:00-04:00`) |
| `fecha_fin` | string (ISO 8601) | — | — | Fecha y hora de fin |
| `fecha_aproximada` | string | — | 255 | Descripción textual si la fecha es aproximada |
| `denominacion_caso` | string | — | 500 | Nombre/denominación del caso |
| `tags` | array | — | — | Etiquetas asociadas |

**Ejemplo de request mínimo:**
```json
{
    "cud": "120100240000012345",
    "id_externo_caso": 98765,
    "id_tipo_denuncia": 1,
    "id_oficina": 42,
    "id_estado": 1,
    "id_etapa": 2
}
```

**Ejemplo de request completo:**
```json
{
    "cud": "120100240000012345",
    "id_externo_caso": 98765,
    "id_tipo_denuncia": 1,
    "id_oficina": 42,
    "id_estado": 1,
    "id_etapa": 2,
    "id_municipio": 11,
    "zona": "Norte",
    "direccion": "Av. 6 de Agosto s/n",
    "latitud": "-16.500000",
    "longitud": "-68.150000",
    "fecha_caso": "2026-06-30T10:30:00-04:00",
    "denominacion_caso": "Caso de prueba integración",
    "relato": "Descripción del hecho delictivo",
    "es_reservado": false,
    "tags": []
}
```

### 7.2 Actualizar caso — `PATCH /interop/v1/inbound/CASO_PENAL/v1/{id}`

`{id}` = `id_pol_caso` retornado al registrar el caso.

| Campo | Tipo | Requerido | Descripción |
|-------|------|-----------|-------------|
| `id_tipo_denuncia` | integer | ✅ | Tipo de denuncia |
| `id_externo_caso_referencia` | integer | — | ID caso padre |
| `id_municipio` | integer | — | ID del municipio |
| `zona` | string (max 255) | — | Zona |
| `direccion` | string | — | Dirección |
| `latitud` | string (max 30) | — | Latitud |
| `longitud` | string (max 30) | — | Longitud |
| `referencia` | string | — | Referencia |
| `relato` | string | — | Relato actualizado |
| `fecha_caso` | string (ISO 8601) | — | Fecha del hecho |
| `fecha_fin` | string (ISO 8601) | — | Fecha de fin |
| `fecha_aproximada` | string (max 255) | — | Fecha aproximada |
| `denominacion_caso` | string (max 500) | — | Denominación |
| `tags` | array | — | Etiquetas (IDs) |

> **Nota:** No incluir `id_pol_caso` en el body — se toma automáticamente del path `{id}`.

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
