---
id: hub-partner
sidebar_position: 3
title: Guía de Integración – Partners
description: Guía de onboarding para sistemas externos que consumen la API de desencriptación QR.
---

# Guía de Integración para Partners

Esta guía está dirigida a los equipos técnicos de entidades externas que integran la API de desencriptación de códigos QR interoperables.

:::info Acceso de partners
Los partners solo acceden a **2 endpoints** a través del gateway. No tienen acceso a las APIs de administración, certificados ni auditoría interna.
:::

---

## Antes de comenzar

Sintesis provee las siguientes credenciales al momento de la integración:

| Parámetro | Valor (QA) |
|-----------|------------|
| **Gateway URL** | `http://199.3.0.63:8080` |
| **client_id** | `unilink-api` |
| **client_secret** | *(provisto por Sintesis)* |
| **Colección Postman** | `docs/HUB-External-Postman-QA.json` |

En producción, las credenciales y la URL del gateway son distintas y se entregan por canal seguro.

---

## Autenticación

La autenticación usa el estándar **OAuth 2.0 Client Credentials** (RFC 6749). El sistema obtiene un JWT de corta duración que debe incluirse en cada request.

### Paso 1 — Obtener token

```bash
curl -X POST http://199.3.0.63:8080/oauth2/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=unilink-api" \
  -d "client_secret=unilink-api-secret" \
  -d "grant_type=client_credentials"
```

**Respuesta:**
```json
{
  "access_token": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
  "expires_in": 300,
  "token_type": "Bearer",
  "scope": "https://api.sintesis.com.bo/qr.decode"
}
```

| Campo | Descripción |
|-------|-------------|
| `access_token` | Token JWT a incluir en las peticiones |
| `expires_in` | Duración en segundos (5 minutos) |

### Paso 2 — Incluir token en cada request

```
Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...
```

:::tip Renovación automática
El token expira en 5 minutos. Recomendamos renovarlo de forma proactiva antes de que expire, o capturar el error `401 Unauthorized` y renovar en ese momento.
:::

---

## Endpoints disponibles

Base URL: `http://199.3.0.63:8080`

| Método | Endpoint | Descripción |
|--------|----------|-------------|
| `POST` | `/partner/v1/qr/decode` | Desencriptar QR por texto o imagen Base64 |
| `POST` | `/partner/v1/qr/decode/file` | Desencriptar QR desde archivo de imagen |

---

## POST /partner/v1/qr/decode

Desencripta un código QR usando el texto leído por un escáner (`DECODED_DATA`) o una imagen en Base64 (`BASE64_IMAGE`).

### Modo DECODED_DATA — texto del QR

El escáner de QR ya leyó el contenido. Se envía el texto directamente.

```bash
curl -X POST http://199.3.0.63:8080/partner/v1/qr/decode \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "inputType": "DECODED_DATA",
    "content": "qERbt4N7AL96YxZo...base64...==|1C302639F6F0A4B2",
    "entityIdRequest": "MI_ENTIDAD",
    "externalReference": "TRX-2026-001"
  }'
```

:::info Formato del campo `content`
El texto del QR debe tener la estructura:
```
{datos_encriptados_en_base64}|{codigo_de_certificado}
```
Ejemplo: `qERbt4N7AL96YxZo...==|1C302639F6F0A4B2`

Este es el texto raw que devuelve el lector/escáner de QR. No modificar ni decodificar.
:::

### Modo BASE64_IMAGE — imagen del QR

Se envía la imagen del QR codificada en Base64. El servicio detecta y decodifica el código internamente.

```bash
curl -X POST http://199.3.0.63:8080/partner/v1/qr/decode \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "inputType": "BASE64_IMAGE",
    "content": "iVBORw0KGgoAAAANSUhEUgAAAQAAAAEAAQM...",
    "entityIdRequest": "MI_ENTIDAD",
    "externalReference": "IMG-2026-001"
  }'
```

### Campos del request

| Campo | Tipo | Requerido | Descripción |
|-------|------|-----------|-------------|
| `inputType` | string | ✓ | `DECODED_DATA` o `BASE64_IMAGE` |
| `content` | string | ✓ | Texto del QR o imagen en Base64 |
| `entityIdRequest` | string | | Identificador de su entidad (para auditoría) |
| `externalReference` | string | | Referencia interna de la transacción |
| `metadata` | objeto | | Datos adicionales de contexto |

**Ejemplo con metadata:**
```json
{
  "inputType": "DECODED_DATA",
  "content": "qERbt4N7...==|1C302639F6F0A4B2",
  "entityIdRequest": "BANCO_UNION",
  "externalReference": "TRX-2026-XYZ",
  "metadata": {
    "canal": "ATM",
    "sucursal": "LPZ-001",
    "operador": "empleado123"
  }
}
```

### Respuesta exitosa (200)

```json
{
  "logId": "6831c3a2f4e1b20012ab3f7d",
  "decryptedData": "{\"monto\":\"1500.00\",\"moneda\":\"BOB\",\"beneficiario\":\"Juan Pérez\"}",
  "certificateCode": "1C302639F6F0A4B2",
  "entityId": "BANCO_UNION",
  "qrType": "PAYMENT",
  "processingTimeMs": 42,
  "decryptedAt": "2026-06-19T15:30:00.000Z",
  "fromCache": true
}
```

| Campo | Tipo | Descripción |
|-------|------|-------------|
| `logId` | string | ID del registro de auditoría |
| `decryptedData` | string | Contenido desencriptado del QR |
| `certificateCode` | string | Código del certificado usado |
| `entityId` | string | ID de la entidad emisora del QR |
| `qrType` | string | `PAYMENT`, `INVOICE`, `ACCOUNT`, `UNKNOWN` |
| `processingTimeMs` | number | Tiempo de procesamiento en ms |
| `decryptedAt` | string | Timestamp ISO-8601 de la operación |
| `fromCache` | boolean | `true` si el certificado vino de caché |

---

## POST /partner/v1/qr/decode/file

Envía directamente el archivo de imagen. No requiere convertir a Base64.

```bash
curl -X POST http://199.3.0.63:8080/partner/v1/qr/decode/file \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/ruta/al/qr-code.png" \
  -F "entityIdRequest=MI_ENTIDAD" \
  -F "externalReference=FILE-2026-001"
```

| Parámetro | Tipo | Requerido | Descripción |
|-----------|------|-----------|-------------|
| `file` | archivo | ✓ | Imagen del QR (JPG, PNG, GIF) |
| `entityIdRequest` | form field | | Identificador de su entidad |
| `externalReference` | form field | | Referencia interna |

---

## Manejo de errores

Todos los errores siguen el estándar **RFC 7807 Problem Details**:

```json
{
  "type": "https://api.sintesis.com.bo/problems/not-found",
  "title": "Not Found",
  "status": 404,
  "detail": "Certificado no encontrado para el código: 1C302639F6F0A4B2",
  "timestamp": "2026-06-19T15:30:00.000Z",
  "errorCode": "CERTIFICATE_NOT_FOUND"
}
```

### Códigos de error comunes

| Status | errorCode | Causa y acción |
|--------|-----------|----------------|
| `400` | `INVALID_QR_FORMAT` | El formato del QR no es válido. Verificar que el contenido tenga el separador `\|` y ambas partes no estén vacías. |
| `400` | `VALIDATION_ERROR` | Campos requeridos faltantes o tipo de dato incorrecto. Revisar el campo `violations` en la respuesta. |
| `401` | — | Token ausente, expirado o inválido. Obtener un nuevo token. |
| `403` | `ACCESS_DENIED` | El cliente no tiene permiso para este endpoint. Contactar a Sintesis. |
| `404` | `CERTIFICATE_NOT_FOUND` | El certificado referenciado en el QR no existe o no está activo. El QR puede ser de una entidad no registrada. |
| `500` | `DECRYPTION_ERROR` | Error al desencriptar. El QR puede estar corrupto o usar un certificado incompatible. |
| `502` | `TUXEDO_API_ERROR` | Error de comunicación con el servicio de certificados. Reintentar en unos segundos. |
| `429` | — | Límite de requests por minuto alcanzado. Reducir la frecuencia de llamadas. |

---

## Límites y consideraciones

| Límite | Valor |
|--------|-------|
| Requests por minuto | 100 |
| Tamaño máximo de imagen | 2 MB |
| Duración del token | 5 minutos |
| Tiempo de respuesta esperado | < 200 ms (con caché activo) |

---

## Ejemplo de integración completa

```bash
#!/bin/bash
GATEWAY="http://199.3.0.63:8080"
CLIENT_ID="unilink-api"
CLIENT_SECRET="unilink-api-secret"

# 1. Obtener token
TOKEN=$(curl -s -X POST "$GATEWAY/oauth2/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=$CLIENT_ID&client_secret=$CLIENT_SECRET&grant_type=client_credentials" \
  | jq -r '.access_token')

echo "Token obtenido: ${TOKEN:0:40}..."

# 2. Desencriptar QR
curl -s -X POST "$GATEWAY/partner/v1/qr/decode" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "inputType": "DECODED_DATA",
    "content": "qERbt4N7AL96...==|1C302639F6F0A4B2",
    "entityIdRequest": "MI_ENTIDAD",
    "externalReference": "TRX-001"
  }' | jq .
```

---

## Soporte

Para dudas técnicas o problemas de integración:

| Canal | Contacto |
|-------|---------|
| Email | soporte@sintesis.com.bo |
| Web | https://sintesis.com.bo |
