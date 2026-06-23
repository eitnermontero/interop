# Guía de Testing - QR Decode API

## Endpoints Disponibles

### 1. POST /api/qr/decode - Decodificar desde JSON

Este endpoint acepta dos tipos de entrada:

#### Opción A: DECODED_DATA (contenido del QR ya leído)

```bash
curl -X POST http://localhost:8081/api/qr/decode \
  -H "Content-Type: application/json" \
  -d '{
    "inputType": "DECODED_DATA",
    "content": "SGVsbG8gV29ybGQhIFRoaXMgaXMgYSB0ZXN0IERBVEF8MTQyNjAwMV92MDExNTIwMjU=|1426001_v01152025",
    "entityIdRequest": "1426001",
    "externalReference": "TEST-001"
  }'
```

**Formato del contenido**: `{ENCRYPTED_BASE64_DATA}|{CERTIFICATE_CODE}`

Ejemplo real con certificado del JKS:
```bash
curl -X POST http://localhost:8081/api/qr/decode \
  -H "Content-Type: application/json" \
  -d '{
    "inputType": "DECODED_DATA",
    "content": "ENCRYPTED_DATA_HERE|1426001_v01152025",
    "entityIdRequest": "1426001"
  }' | jq .
```

#### Opción B: BASE64_IMAGE (imagen del QR en Base64)

```bash
curl -X POST http://localhost:8081/api/qr/decode \
  -H "Content-Type: application/json" \
  -d '{
    "inputType": "BASE64_IMAGE",
    "content": "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
    "entityIdRequest": "1426001"
  }' | jq .
```

**Nota**: El Base64 puede incluir el prefijo `data:image/png;base64,` (se limpia automáticamente).

Ejemplo con imagen completa:
```bash
# Convertir imagen a Base64
IMAGE_B64=$(base64 -w 0 qr_test.png)

curl -X POST http://localhost:8081/api/qr/decode \
  -H "Content-Type: application/json" \
  -d "{
    \"inputType\": \"BASE64_IMAGE\",
    \"content\": \"$IMAGE_B64\",
    \"entityIdRequest\": \"1426001\"
  }" | jq .
```

---

### 2. POST /api/qr/decode/file - Decodificar desde archivo

Este endpoint acepta un archivo de imagen (JPG, PNG, GIF) mediante multipart/form-data.

```bash
curl -X POST http://localhost:8081/api/qr/decode/file \
  -F 'file=@/path/to/qr_image.png' \
  -F 'entityIdRequest=1426001' \
  -F 'externalReference=TEST-002'
```

Ejemplo real:
```bash
curl -X POST http://localhost:8081/api/qr/decode/file \
  -F 'file=@./testdata/qr_ganadero.jpg' \
  -F 'entityIdRequest=1426001' | jq .
```

Con formato verbose para ver headers:
```bash
curl -v -X POST http://localhost:8081/api/qr/decode/file \
  -F 'file=@./testdata/qr_ganadero.jpg' \
  -F 'entityIdRequest=1426001' 2>&1 | grep -E "(< X-|> POST|< HTTP)"
```

---

## Respuesta Exitosa

Ambos endpoints retornan el mismo formato de respuesta:

```json
{
  "logId": "663c9f8a-1234-5678-90ab-cdef12345678",
  "decryptedData": "CONTENIDO_DESENCRIPTADO_DEL_QR",
  "certificateCode": "1426001_v01152025",
  "entityId": "1426001",
  "qrType": "GANADERO",
  "processingTimeMs": 156,
  "decryptedAt": "2026-05-25T21:30:00.123456Z"
}
```

**Headers de respuesta**:
- `X-Request-Id`: UUID del request para tracking
- `X-Log-Id`: ID del log de auditoría
- `X-Source-File`: Nombre del archivo (solo en /decode/file)

---

## Errores Comunes

### 400 Bad Request - Formato inválido

```json
{
  "type": "https://api.sintesis.com.bo/problems/invalid-qr-format",
  "title": "Invalid QR Format",
  "status": 400,
  "detail": "El formato del QR es inválido. Debe ser: ENCRYPTED_DATA|CERTIFICATE_CODE",
  "timestamp": "2026-05-25T21:30:00Z",
  "errorCode": "INVALID_QR_FORMAT"
}
```

### 400 Bad Request - Imagen sin QR

```json
{
  "type": "https://api.sintesis.com.bo/problems/invalid-qr-format",
  "title": "Invalid QR Format",
  "status": 400,
  "detail": "No se pudo detectar un código QR en la imagen. Asegúrese de que la imagen contenga un QR válido y esté bien iluminada.",
  "timestamp": "2026-05-25T21:30:00Z",
  "errorCode": "INVALID_QR_FORMAT"
}
```

### 404 Not Found - Certificado no existe

```json
{
  "type": "https://api.sintesis.com.bo/problems/certificate-not-found",
  "title": "Certificate Not Found",
  "status": 404,
  "detail": "Certificado no encontrado: 9999999_INVALID",
  "timestamp": "2026-05-25T21:30:00Z",
  "errorCode": "CERTIFICATE_NOT_FOUND"
}
```

### 500 Internal Server Error - Error de desencriptación

```json
{
  "type": "https://api.sintesis.com.bo/problems/decryption-error",
  "title": "Decryption Error",
  "status": 500,
  "detail": "Error al desencriptar el QR. Verifique el formato y el certificado.",
  "timestamp": "2026-05-25T21:30:00Z",
  "errorCode": "DECRYPTION_ERROR",
  "technicalDetail": "javax.crypto.BadPaddingException: Decryption error"
}
```

---

## Certificados Disponibles en el JKS

Certificados válidos para testing (del endpoint /api/certificates):

1. **1426001_v01152025** - Pre Producción ACCL
   - Válido desde: 2020-08-19
   - Válido hasta: 2030-08-17
   - Subject: achblade.preproduccion.accl.net

2. **1426001_v012024** - Certificado Thawte EV 2024
   - Válido desde: 2024-01-30
   - Válido hasta: 2025-01-29
   - Subject: certach.accl.bo

3. **1426001_v012026** - Certificado Wildcard 2026
   - Válido desde: 2026-01-08
   - Válido hasta: 2027-01-21
   - Subject: *.accl.bo

---

## Generar Datos de Prueba

### 1. Generar QR con texto simple

Usando Python (con biblioteca qrcode):

```python
import qrcode

# Formato: ENCRYPTED_DATA|CERTIFICATE_CODE
qr_content = "U29tZUVuY3J5cHRlZERhdGFIZXJl|1426001_v01152025"

qr = qrcode.QRCode(version=1, box_size=10, border=5)
qr.add_data(qr_content)
qr.make(fit=True)

img = qr.make_image(fill_color="black", back_color="white")
img.save("testdata/qr_test.png")
```

### 2. Convertir imagen a Base64

Linux/Mac:
```bash
base64 -w 0 testdata/qr_test.png > testdata/qr_test_b64.txt
```

Windows (PowerShell):
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("testdata\qr_test.png")) | Out-File testdata\qr_test_b64.txt
```

### 3. Leer QR de imagen existente

Si tienes una imagen de QR y quieres extraer el contenido:

```bash
# Usando zbarimg (instalar: apt-get install zbar-tools)
zbarimg testdata/qr_ganadero.jpg

# Salida: QR-Code:ENCRYPTED_DATA|1426001_v01152025
```

---

## Auditorías

Consultar auditorías de desencriptaciones:

```bash
# Todas las auditorías (últimas 20)
curl http://localhost:8081/api/qr/audits | jq .

# Filtrar por entityId
curl "http://localhost:8081/api/qr/audits?entityId=1426001&size=10" | jq .

# Filtrar por status
curl "http://localhost:8081/api/qr/audits?status=SUCCESS" | jq .

# Filtrar por rango de fechas
curl "http://localhost:8081/api/qr/audits?fromDate=2026-05-01T00:00:00Z&toDate=2026-05-31T23:59:59Z" | jq .
```

---

## Testing End-to-End

Script completo de testing:

```bash
#!/bin/bash

echo "=== Testing QR Decode API ==="

# 1. Test con DECODED_DATA
echo -e "\n1. Testing DECODED_DATA..."
curl -s -X POST http://localhost:8081/api/qr/decode \
  -H "Content-Type: application/json" \
  -d '{
    "inputType": "DECODED_DATA",
    "content": "TEST_DATA|1426001_v01152025",
    "entityIdRequest": "1426001"
  }' | jq .

# 2. Test con archivo (si existe)
if [ -f "testdata/qr_test.png" ]; then
  echo -e "\n2. Testing con archivo..."
  curl -s -X POST http://localhost:8081/api/qr/decode/file \
    -F 'file=@testdata/qr_test.png' \
    -F 'entityIdRequest=1426001' | jq .
fi

# 3. Ver certificados disponibles
echo -e "\n3. Certificados disponibles..."
curl -s http://localhost:8081/api/certificates | jq '.[0:3]'

# 4. Ver auditorías
echo -e "\n4. Últimas auditorías..."
curl -s "http://localhost:8081/api/qr/audits?size=5" | jq '.content[] | {logId, status, certificateCode, createdDate}'

echo -e "\n=== Testing completado ==="
```

Guardar como `test_qr_api.sh`, dar permisos (`chmod +x test_qr_api.sh`) y ejecutar:
```bash
./test_qr_api.sh
```

---

## Notas Importantes

1. **Seguridad deshabilitada temporalmente**: Los endpoints no requieren autenticación para testing local. En producción, se debe habilitar OAuth2 JWT.

2. **Formato del QR**: El contenido del QR debe seguir el formato `{ENCRYPTED_DATA}|{CERTIFICATE_CODE}`. El `ENCRYPTED_DATA` debe ser Base64 del texto encriptado con RSA.

3. **Tamaño de imágenes**: El endpoint `/decode/file` acepta imágenes de hasta 10MB (configurable en `application.yml`).

4. **Timeout**: Las operaciones tienen un timeout de 60 segundos (configurable).

5. **Caché**: Los certificados se cachean en Redis por 24 horas para mejorar el rendimiento.
