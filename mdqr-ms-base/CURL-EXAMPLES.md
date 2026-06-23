# MDQR - Ejemplos de API con curl

Ejemplos de curl para probar las APIs de gestión de certificados y desencriptación de QR.

## 📋 Prerequisitos

- Aplicación corriendo en `http://localhost:8081`
- `jq` instalado para formatear JSON (opcional)
- Seguridad deshabilitada en modo local (desarrollo)

## 🚀 Quick Start

```bash
# Ejecutar todos los tests
chmod +x test-api-curl.sh
./test-api-curl.sh
```

---

## 📚 Ejemplos Individuales

### 1️⃣ Health Check

```bash
curl http://localhost:8081/actuator/health | jq '.'
```

---

### 2️⃣ Listar Certificados

```bash
curl -X GET http://localhost:8081/api/certificates \
  -H "Content-Type: application/json" | jq '.'
```

**Paginación:**
```bash
curl -X GET "http://localhost:8081/api/certificates?page=0&size=20" | jq '.'
```

---

### 3️⃣ Subir Certificado

```bash
curl -X POST http://localhost:8081/api/certificates \
  -H "Content-Type: application/json" \
  -d '{
    "pemContent": "-----BEGIN CERTIFICATE-----\nMIID...\n-----END CERTIFICATE-----",
    "entityId": "1017",
    "entityName": "Banco Sol",
    "description": "Certificado para desencriptar QRs",
    "tags": ["prod", "banco-sol"],
    "notificationEmails": ["admin@bancosol.com"]
  }' | jq '.'
```

**Campos:**
- `pemContent` *(requerido)*: Certificado en formato PEM
- `entityId` *(requerido)*: ID de la entidad (ej: "1017")
- `entityName` *(opcional)*: Nombre de la entidad
- `description` *(opcional)*: Descripción del certificado
- `tags` *(opcional)*: Array de tags
- `notificationEmails` *(opcional)*: Emails para notificaciones de expiración

---

### 4️⃣ Validar Certificado (sin guardar)

```bash
curl -X POST http://localhost:8081/api/certificates/validate \
  -H "Content-Type: application/json" \
  -d '{
    "pemContent": "-----BEGIN CERTIFICATE-----\nMIID...\n-----END CERTIFICATE-----",
    "entityId": "1017"
  }' | jq '.'
```

**Respuesta:**
```json
{
  "serialNumber": "abc123...",
  "subjectDn": "CN=Banco Sol,O=...",
  "issuerDn": "CN=...",
  "validFrom": "2026-01-01T00:00:00Z",
  "validTo": "2028-01-01T00:00:00Z",
  "fingerprint": "SHA256:..."
}
```

---

### 5️⃣ Ver Detalle de Certificado

```bash
# Reemplaza {id} con el ID del certificado
curl -X GET http://localhost:8081/api/certificates/1 | jq '.'
```

---

### 6️⃣ Listar Certificados por Entidad

```bash
curl -X GET http://localhost:8081/api/certificates/entity/1017 | jq '.'
```

---

### 7️⃣ Certificados por Expirar

```bash
# Certificados que expiran en los próximos 90 días
curl -X GET http://localhost:8081/api/certificates/expiring/90 | jq '.'
```

---

### 8️⃣ Desencriptar QR - Texto Decodificado

**Formato del QR:** `{encrypted_data_base64}|{cert_code}`

```bash
curl -X POST http://localhost:8081/api/qr/decode \
  -H "Content-Type: application/json" \
  -d '{
    "inputType": "DECODED_DATA",
    "content": "dGVzdF9lbmNyeXB0ZWQ=|1017",
    "entityIdRequest": "MIDDLEWARE",
    "externalReference": "TXN-12345"
  }' | jq '.'
```

**Campos:**
- `inputType`: `"DECODED_DATA"` o `"BASE64_IMAGE"`
- `content`: Contenido del QR (texto o imagen base64)
- `entityIdRequest` *(opcional)*: ID de la entidad que solicita
- `externalReference` *(opcional)*: Referencia externa para tracking
- `metadata` *(opcional)*: Metadata adicional

**Respuesta exitosa:**
```json
{
  "success": true,
  "decryptedData": "datos desencriptados aquí",
  "certificateUsed": {
    "id": 1,
    "serialNumber": "abc123",
    "entityId": "1017",
    "entityName": "Banco Sol"
  },
  "processingTimeMs": 150
}
```

---

### 9️⃣ Desencriptar QR - Imagen Base64

```bash
curl -X POST http://localhost:8081/api/qr/decode \
  -H "Content-Type: application/json" \
  -d '{
    "inputType": "BASE64_IMAGE",
    "content": "iVBORw0KGgoAAAANSUhEUg...",
    "entityIdRequest": "MIDDLEWARE"
  }' | jq '.'
```

**Nota:** La imagen debe ser un QR code válido en formato Base64.

---

### 🔟 Activar Certificado

```bash
curl -X POST http://localhost:8081/api/certificates/1/activate \
  -H "Content-Type: application/json" | jq '.'
```

---

### 1️⃣1️⃣ Desactivar Certificado

```bash
curl -X POST http://localhost:8081/api/certificates/1/deactivate \
  -H "Content-Type: application/json" | jq '.'
```

---

### 1️⃣2️⃣ Revocar Certificado

```bash
curl -X POST http://localhost:8081/api/certificates/1/revoke \
  -H "Content-Type: application/json" \
  -d '{
    "reason": "Certificado comprometido"
  }' | jq '.'
```

**⚠️ Importante:** La revocación es **irreversible**.

---

### 1️⃣3️⃣ Reemplazar Certificado

```bash
curl -X POST http://localhost:8081/api/certificates/1/replace \
  -H "Content-Type: application/json" \
  -d '{
    "newPemContent": "-----BEGIN CERTIFICATE-----\nNEW_CERT...\n-----END CERTIFICATE-----",
    "changeReason": "Renovación periódica"
  }' | jq '.'
```

**Resultado:**
- Certificado anterior: status → `SUPERSEDED`, `is_current_version` → `false`
- Nuevo certificado: status → `ACTIVE`, `is_current_version` → `true`

---

### 1️⃣4️⃣ Descargar Certificado PEM

```bash
curl -X GET http://localhost:8081/api/certificates/1/pem \
  -H "Accept: text/plain" > certificate.pem
```

---

### 1️⃣5️⃣ Ver Auditorías de Desencriptación

```bash
curl -X GET "http://localhost:8081/api/qr/audits?page=0&size=10" | jq '.'
```

**Filtros disponibles:**
```bash
# Por entidad
curl -X GET "http://localhost:8081/api/qr/audits?entityId=1017&page=0&size=10"

# Por rango de fechas
curl -X GET "http://localhost:8081/api/qr/audits?from=2026-06-01&to=2026-06-30&page=0&size=10"

# Por estado (success/error)
curl -X GET "http://localhost:8081/api/qr/audits?status=success&page=0&size=10"
```

---

## 🔐 Autenticación (Producción)

En producción, necesitarás un token JWT:

```bash
TOKEN="eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."

curl -X GET http://localhost:8081/api/certificates \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json"
```

---

## 📊 Swagger UI

Accede a la documentación interactiva en:

```
http://localhost:8081/swagger-ui.html
```

---

## ❌ Manejo de Errores

### Certificado Duplicado (409)
```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Certificate with serial ABC123 already exists"
}
```

### Certificado No Encontrado (404)
```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Certificate not found: 999"
}
```

### Certificado Inválido (400)
```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Invalid PEM format"
}
```

### Error de Desencriptación (500)
```json
{
  "type": "about:blank",
  "title": "Internal Server Error",
  "status": 500,
  "detail": "Decryption failed: Invalid encrypted data"
}
```

---

## 📝 Notas

1. **Formato PEM**: El certificado debe incluir `-----BEGIN CERTIFICATE-----` y `-----END CERTIFICATE-----`
2. **Serial Number**: Debe ser único en el sistema
3. **Entity ID**: Identificador de la entidad bancaria (ej: "1017" = Banco Sol)
4. **Certificate Code**: Código corto usado en el QR (puede ser diferente al entityId)
5. **QR Format**: `{encrypted_base64}|{certificate_code}`

---

## 🔗 Referencias

- Swagger UI: http://localhost:8081/swagger-ui.html
- API Docs: http://localhost:8081/v3/api-docs
- Health: http://localhost:8081/actuator/health
