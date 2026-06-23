> **[OBSOLETO]** Este documento corresponde al sistema MWC/ULQR (pagos) anterior.
> No aplica al sistema MDQR actual. Conservado solo como referencia histórica.
> Ver [docs/00-INDEX.md](00-INDEX.md) para documentación vigente.

---

# API Documentation - ULQR System
## Complete API Specification for Frontend Development

**Base URL**: `http://localhost:8081`
**Authentication**: Bearer JWT Token
**API Version**: 1.0.0

---

## Table of Contents

1. [Certificate Management APIs](#1-certificate-management-apis)
2. [QR Decryption APIs](#2-qr-decryption-apis)
3. [Audit & Monitoring APIs](#3-audit--monitoring-apis)
4. [Data Models](#4-data-models)
5. [Error Responses](#5-error-responses)

---

# 1. Certificate Management APIs

## 1.1 List Certificates (Paginated)

**Endpoint**: `GET /api/certificates`
**Auth**: Required (ADMIN or OPERATOR)
**Description**: Lists all active certificates with pagination

### Request Parameters
```
Query Parameters:
- page: int (default: 0) - Page number (0-indexed)
- size: int (default: 20) - Items per page (max: 100)
- sort: string (default: "createdDate,desc") - Sort field and direction
```

### Example Request
```bash
curl -X GET 'http://localhost:8081/api/certificates?page=0&size=20&sort=createdDate,desc' \
  -H 'Authorization: Bearer YOUR_TOKEN'
```

### Response (200 OK)
```json
{
  "content": [
    {
      "id": 4,
      "serialNumber": "7a90f76300030004b78a",
      "fingerprintSha256": "a2d6ad09e96604d9cb413a7414354a84c21a05a16a72723df6d18009ea7b902a",
      "entityId": "1017",
      "entityName": "Banco Solidario S.A.",
      "subjectDn": "CN=vachx1pre.bsol.com.bo,OU=Preproduccion,O=Banco Solidario S.A.",
      "issuerDn": "CN=BSOL-VADLPZSRV-CA,DC=bsol,DC=com,DC=bo",
      "issuerCn": "BSOL-VADLPZSRV-CA",
      "validFrom": "2026-05-11T19:36:49.000Z",
      "validTo": "2028-05-10T19:36:49.000Z",
      "daysRemaining": 709,
      "status": "ACTIVE",
      "versionNumber": 1,
      "isCurrentVersion": true,
      "isActive": true,
      "isRevoked": false,
      "revokedAt": null,
      "revokedBy": null,
      "revokedReason": null,
      "description": "Certificado Banco Sol - Producción 2026-2028",
      "tags": ["bancosol", "produccion", "2026-2028"],
      "notificationEmails": null,
      "createdDate": "2026-06-01T15:19:28.327Z",
      "createdBy": "system",
      "lastModifiedDate": "2026-06-01T15:19:28.327Z",
      "lastModifiedBy": "system"
    }
  ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 20,
    "offset": 0
  },
  "totalElements": 1,
  "totalPages": 1,
  "size": 20,
  "number": 0,
  "first": true,
  "last": true,
  "empty": false
}
```

---

## 1.2 Upload Certificate (From PEM Content)

**Endpoint**: `POST /api/certificates`
**Auth**: Required (ADMIN only)
**Content-Type**: `application/json`
**Description**: Uploads a new certificate from PEM content

### Request Body
```json
{
  "pemContent": "-----BEGIN CERTIFICATE-----\nMIIFo...\n-----END CERTIFICATE-----",
  "entityId": "1017",
  "entityName": "Banco Solidario S.A.",
  "description": "Certificado de producción 2026-2028",
  "tags": ["produccion", "bancosol", "2026-2028"],
  "notificationEmails": ["admin@bank.com", "security@bank.com"]
}
```

### Example Request
```bash
curl -X POST 'http://localhost:8081/api/certificates' \
  -H 'Authorization: Bearer YOUR_TOKEN' \
  -H 'Content-Type: application/json' \
  -d '{
    "pemContent": "-----BEGIN CERTIFICATE-----\nMIIFo...\n-----END CERTIFICATE-----",
    "entityId": "1017",
    "entityName": "Banco Solidario S.A.",
    "description": "Certificado de producción",
    "tags": ["produccion"]
  }'
```

### Response (201 Created)
```json
{
  "id": 5,
  "serialNumber": "7a90f76300030004b78a",
  "fingerprintSha256": "a2d6ad09e96604d9cb413a7414354a84c21a05a16a72723df6d18009ea7b902a",
  "entityId": "1017",
  "entityName": "Banco Solidario S.A.",
  "status": "ACTIVE",
  "validFrom": "2026-05-11T19:36:49.000Z",
  "validTo": "2028-05-10T19:36:49.000Z",
  "daysRemaining": 709,
  "createdDate": "2026-06-01T15:30:00.000Z",
  "createdBy": "admin@unilink.com"
}
```

### Error Responses
- **400 Bad Request**: Invalid PEM format
- **409 Conflict**: Duplicate certificate (already exists)

---

## 1.3 Upload Certificate (From File) ⭐ NEW

**Endpoint**: `POST /api/certificates/upload-file`
**Auth**: Required (ADMIN only)
**Content-Type**: `multipart/form-data`
**Description**: Uploads a certificate from a file (.crt, .pem, .cer)

### Request (Multipart Form)
```
Form Fields:
- file: File (required) - Certificate file (.crt, .pem, .cer, .txt)
- entityId: string (required) - Bank entity ID
- entityName: string (required) - Bank name
- description: string (optional) - Description
- tags: string[] (optional) - Tags (comma-separated)
- notificationEmails: string[] (optional) - Emails (comma-separated)
```

### Example Request
```bash
curl -X POST 'http://localhost:8081/api/certificates/upload-file' \
  -H 'Authorization: Bearer YOUR_TOKEN' \
  -F 'file=@/path/to/certificate.crt' \
  -F 'entityId=1017' \
  -F 'entityName=Banco Solidario S.A.' \
  -F 'description=Certificado de producción' \
  -F 'tags=produccion,bancosol'
```

### Response (201 Created)
Same as 1.2

---

## 1.4 Bulk Upload Certificates ⭐ NEW

**Endpoint**: `POST /api/certificates/bulk-upload`
**Auth**: Required (ADMIN only)
**Content-Type**: `multipart/form-data`
**Description**: Uploads multiple certificates from a ZIP file or directory

### Request (Multipart Form)
```
Form Fields:
- files: File[] (required) - Multiple certificate files
- entityMapping: JSON string (optional) - Maps filenames to entity info
```

### Entity Mapping Format
```json
{
  "1017_BcoSol_2028.crt": {
    "entityId": "1017",
    "entityName": "Banco Solidario S.A.",
    "tags": ["produccion", "2026-2028"]
  },
  "1009_BISA_TEST.crt": {
    "entityId": "1009",
    "entityName": "Banco BISA",
    "tags": ["test"]
  }
}
```

### Example Request
```bash
curl -X POST 'http://localhost:8081/api/certificates/bulk-upload' \
  -H 'Authorization: Bearer YOUR_TOKEN' \
  -F 'files=@cert1.crt' \
  -F 'files=@cert2.crt' \
  -F 'files=@cert3.pem' \
  -F 'entityMapping={"cert1.crt":{"entityId":"1017","entityName":"Banco Sol"}}'
```

### Response (200 OK)
```json
{
  "totalUploaded": 3,
  "successful": 2,
  "failed": 1,
  "results": [
    {
      "filename": "cert1.crt",
      "status": "SUCCESS",
      "certificateId": 5,
      "serialNumber": "7a90f76300030004b78a"
    },
    {
      "filename": "cert2.crt",
      "status": "SUCCESS",
      "certificateId": 6,
      "serialNumber": "61588e94f33e79b24df5ec4de6ea1114"
    },
    {
      "filename": "cert3.pem",
      "status": "FAILED",
      "error": "Duplicate certificate"
    }
  ]
}
```

---

## 1.5 Get Certificate Details

**Endpoint**: `GET /api/certificates/{id}`
**Auth**: Required (ADMIN or OPERATOR)
**Description**: Gets complete details of a certificate including PEM content

### Example Request
```bash
curl -X GET 'http://localhost:8081/api/certificates/5' \
  -H 'Authorization: Bearer YOUR_TOKEN'
```

### Response (200 OK)
```json
{
  "id": 5,
  "serialNumber": "7a90f76300030004b78a",
  "fingerprintSha256": "a2d6ad09e96604d9cb413a7414354a84c21a05a16a72723df6d18009ea7b902a",
  "entityId": "1017",
  "entityName": "Banco Solidario S.A.",
  "pemContent": "-----BEGIN CERTIFICATE-----\nMIIFo...\n-----END CERTIFICATE-----",
  "subjectDn": "CN=vachx1pre.bsol.com.bo,OU=Preproduccion,O=Banco Solidario S.A.",
  "issuerDn": "CN=BSOL-VADLPZSRV-CA,DC=bsol,DC=com,DC=bo",
  "issuerCn": "BSOL-VADLPZSRV-CA",
  "validFrom": "2026-05-11T19:36:49.000Z",
  "validTo": "2028-05-10T19:36:49.000Z",
  "daysRemaining": 709,
  "status": "ACTIVE",
  "versionNumber": 1,
  "isCurrentVersion": true,
  "isActive": true,
  "isRevoked": false,
  "description": "Certificado Banco Sol - Producción 2026-2028",
  "tags": ["bancosol", "produccion", "2026-2028"],
  "createdDate": "2026-06-01T15:19:28.327Z",
  "createdBy": "admin@unilink.com",
  "lastModifiedDate": "2026-06-01T15:19:28.327Z",
  "lastModifiedBy": "admin@unilink.com"
}
```

---

## 1.6 Download Certificate PEM

**Endpoint**: `GET /api/certificates/{id}/pem`
**Auth**: Required (ADMIN or OPERATOR)
**Description**: Downloads the certificate PEM file

### Example Request
```bash
curl -X GET 'http://localhost:8081/api/certificates/5/pem' \
  -H 'Authorization: Bearer YOUR_TOKEN' \
  -o certificate.pem
```

### Response (200 OK)
```
Content-Type: text/plain
Content-Disposition: attachment; filename="certificate_7a90f76300030004b78a.pem"

-----BEGIN CERTIFICATE-----
MIIFo...
-----END CERTIFICATE-----
```

---

## 1.7 List Certificates by Entity

**Endpoint**: `GET /api/certificates/entity/{entityId}`
**Auth**: Required (ADMIN or OPERATOR)
**Description**: Lists all certificates for a specific bank entity

### Example Request
```bash
curl -X GET 'http://localhost:8081/api/certificates/entity/1017' \
  -H 'Authorization: Bearer YOUR_TOKEN'
```

### Response (200 OK)
```json
[
  {
    "id": 4,
    "serialNumber": "7a90f76300030004b78a",
    "entityId": "1017",
    "entityName": "Banco Solidario S.A.",
    "status": "ACTIVE",
    "validFrom": "2026-05-11T19:36:49.000Z",
    "validTo": "2028-05-10T19:36:49.000Z",
    "daysRemaining": 709
  },
  {
    "id": 3,
    "serialNumber": "1fcc8d89000300036748",
    "entityId": "1017",
    "entityName": "Banco Solidario S.A.",
    "status": "SUPERSEDED",
    "validFrom": "2024-01-15T00:00:00.000Z",
    "validTo": "2026-01-14T23:59:59.000Z",
    "daysRemaining": -138
  }
]
```

---

## 1.8 Get Expiring Certificates

**Endpoint**: `GET /api/certificates/expiring/{days}`
**Auth**: Required (ADMIN or OPERATOR)
**Description**: Lists certificates expiring in the next X days

### Example Request
```bash
# Get certificates expiring in next 30 days
curl -X GET 'http://localhost:8081/api/certificates/expiring/30' \
  -H 'Authorization: Bearer YOUR_TOKEN'
```

### Response (200 OK)
```json
[
  {
    "id": 8,
    "serialNumber": "abc123def456",
    "entityId": "1009",
    "entityName": "Banco BISA",
    "status": "EXPIRING_SOON",
    "validFrom": "2024-01-01T00:00:00.000Z",
    "validTo": "2026-06-15T23:59:59.000Z",
    "daysRemaining": 14,
    "notificationEmails": ["admin@bisa.com"]
  }
]
```

---

## 1.9 Validate Certificate (Without Saving)

**Endpoint**: `POST /api/certificates/validate`
**Auth**: Required (ADMIN or OPERATOR)
**Content-Type**: `application/json`
**Description**: Validates a PEM certificate without saving it

### Request Body
```json
{
  "pemContent": "-----BEGIN CERTIFICATE-----\nMIIFo...\n-----END CERTIFICATE-----"
}
```

### Response (200 OK)
```json
{
  "serialNumber": "7a90f76300030004b78a",
  "subject": "CN=vachx1pre.bsol.com.bo,OU=Preproduccion",
  "issuer": "CN=BSOL-VADLPZSRV-CA,DC=bsol,DC=com,DC=bo",
  "validFrom": "2026-05-11",
  "validTo": "2028-05-10",
  "isValid": true,
  "daysUntilExpiry": 709
}
```

---

## 1.10 Activate Certificate

**Endpoint**: `POST /api/certificates/{id}/activate`
**Auth**: Required (ADMIN only)
**Description**: Activates a deactivated certificate

### Example Request
```bash
curl -X POST 'http://localhost:8081/api/certificates/5/activate' \
  -H 'Authorization: Bearer YOUR_TOKEN'
```

### Response (200 OK)
```json
{
  "id": 5,
  "serialNumber": "7a90f76300030004b78a",
  "status": "ACTIVE",
  "isActive": true,
  "lastModifiedBy": "admin@unilink.com",
  "lastModifiedDate": "2026-06-01T16:00:00.000Z"
}
```

---

## 1.11 Deactivate Certificate

**Endpoint**: `POST /api/certificates/{id}/deactivate`
**Auth**: Required (ADMIN only)
**Description**: Deactivates a certificate (stops using it for QR decryption)

### Example Request
```bash
curl -X POST 'http://localhost:8081/api/certificates/5/deactivate' \
  -H 'Authorization: Bearer YOUR_TOKEN'
```

### Response (200 OK)
```json
{
  "id": 5,
  "serialNumber": "7a90f76300030004b78a",
  "status": "ACTIVE",
  "isActive": false,
  "lastModifiedBy": "admin@unilink.com",
  "lastModifiedDate": "2026-06-01T16:05:00.000Z"
}
```

---

## 1.12 Revoke Certificate

**Endpoint**: `POST /api/certificates/{id}/revoke`
**Auth**: Required (ADMIN only)
**Content-Type**: `application/json`
**Description**: Permanently revokes a certificate (irreversible)

### Request Body
```json
{
  "reason": "Certificate compromised - security breach detected"
}
```

### Example Request
```bash
curl -X POST 'http://localhost:8081/api/certificates/5/revoke' \
  -H 'Authorization: Bearer YOUR_TOKEN' \
  -H 'Content-Type: application/json' \
  -d '{"reason": "Certificate compromised"}'
```

### Response (200 OK)
```json
{
  "id": 5,
  "serialNumber": "7a90f76300030004b78a",
  "status": "REVOKED",
  "isRevoked": true,
  "isActive": false,
  "revokedAt": "2026-06-01T16:10:00.000Z",
  "revokedBy": "admin@unilink.com",
  "revokedReason": "Certificate compromised - security breach detected"
}
```

---

## 1.13 Replace Certificate

**Endpoint**: `POST /api/certificates/{id}/replace`
**Auth**: Required (ADMIN only)
**Content-Type**: `application/json`
**Description**: Replaces a certificate with a new version (old one becomes SUPERSEDED)

### Request Body
```json
{
  "newPemContent": "-----BEGIN CERTIFICATE-----\nNEW_CERT...\n-----END CERTIFICATE-----",
  "changeReason": "Certificate renewal - old certificate expiring soon"
}
```

### Response (201 Created)
```json
{
  "id": 9,
  "serialNumber": "new123serial456",
  "entityId": "1017",
  "entityName": "Banco Solidario S.A.",
  "status": "ACTIVE",
  "versionNumber": 2,
  "isCurrentVersion": true,
  "isActive": true,
  "previousCertificateId": 5,
  "createdDate": "2026-06-01T16:15:00.000Z",
  "createdBy": "admin@unilink.com"
}
```

---

# 2. QR Decryption APIs

## 2.1 Decrypt QR (From Decoded Data)

**Endpoint**: `POST /api/qr/decode`
**Auth**: Optional (disabled for testing)
**Content-Type**: `application/json`
**Description**: Decodes and decrypts a QR code from decoded data or Base64 image

### Request Body
```json
{
  "inputType": "DECODED_DATA",
  "content": "Bxd77WrOkqC58dbUPLK1vlkjOJiN...==|7A90F76300030004B78A",
  "entityIdRequest": "1017",
  "externalReference": "ORDER-12345",
  "metadata": {
    "source": "mobile_app",
    "version": "2.1.0"
  }
}
```

### Input Types
- `DECODED_DATA`: QR string already decoded (format: `{encrypted_base64}|{serial}`)
- `BASE64_IMAGE`: Base64-encoded image of QR code (will be decoded with ZXing)

### Example Request
```bash
curl -X POST 'http://localhost:8081/api/qr/decode' \
  -H 'Content-Type: application/json' \
  -d '{
    "inputType": "DECODED_DATA",
    "content": "Bxd77WrOkqC58dbUPLK1vlkjOJiN...==|7A90F76300030004B78A",
    "entityIdRequest": "1017"
  }'
```

### Response (200 OK)
```json
{
  "logId": "6a1da8d17867abafb06c67fa",
  "decryptedData": "{\"amount\":1500.50,\"account\":\"1234567890\",\"reference\":\"PAY-001\"}",
  "certificateCode": "7a90f76300030004b78a",
  "entityId": "1017",
  "qrType": "PAYMENT",
  "processingTimeMs": 156,
  "decryptedAt": "2026-06-01T16:20:00.000Z"
}
```

### Response Headers
```
X-Request-Id: 5f75d50e-068c-4958-9c98-ce1fdee88c4b
X-Log-Id: 6a1da8d17867abafb06c67fa
```

---

## 2.2 Decrypt QR (From Image File)

**Endpoint**: `POST /api/qr/decode/file`
**Auth**: Optional (disabled for testing)
**Content-Type**: `multipart/form-data`
**Description**: Decodes and decrypts a QR code from an image file

### Request (Multipart Form)
```
Form Fields:
- file: File (required) - QR image file (JPG, PNG, GIF)
- entityIdRequest: string (optional) - Entity ID
- externalReference: string (optional) - External reference
```

### Example Request
```bash
curl -X POST 'http://localhost:8081/api/qr/decode/file' \
  -F 'file=@qr-code.jpeg' \
  -F 'entityIdRequest=1017' \
  -F 'externalReference=ORDER-12345'
```

### Response (200 OK)
Same as 2.1, with additional header:
```
X-Source-File: qr-code.jpeg
```

---

# 3. Audit & Monitoring APIs

## 3.1 Get QR Decryption Audit Logs

**Endpoint**: `GET /api/qr/audits`
**Auth**: Optional (disabled for testing, should be ADMIN/AUDITOR)
**Description**: Queries decryption audit logs with filters

### Query Parameters
```
- keycloakClientId: string (optional) - Filter by client ID
- certificateCode: string (optional) - Filter by certificate serial
- entityId: string (optional) - Filter by entity ID
- status: string (optional) - SUCCESS or ERROR
- fromDate: datetime (optional) - ISO-8601 format
- toDate: datetime (optional) - ISO-8601 format
- page: int (default: 0)
- size: int (default: 20, max: 100)
- sort: string (default: "createdDate")
- order: string (default: "desc") - asc or desc
```

### Example Request
```bash
curl -X GET 'http://localhost:8081/api/qr/audits?status=SUCCESS&entityId=1017&page=0&size=20' \
  -H 'Authorization: Bearer YOUR_TOKEN'
```

### Response (200 OK)
```json
{
  "content": [
    {
      "id": 123,
      "logId": "6a1da8d17867abafb06c67fa",
      "keycloakClientId": "mobile-app-client",
      "mtlsCertCn": "app.unilink.com",
      "certificate": {
        "id": 4,
        "serialNumber": "7a90f76300030004b78a",
        "entityName": "Banco Solidario S.A."
      },
      "qrStringHash": "abc123def456...",
      "entityIdRequest": "1017",
      "externalReference": "ORDER-12345",
      "metadata": "{\"source\":\"mobile_app\"}",
      "status": "SUCCESS",
      "qrType": "PAYMENT",
      "decryptedDataJson": "{\"amount\":1500.50}",
      "errorMessage": null,
      "processingTimeMs": 156,
      "ipAddress": "192.168.1.100",
      "userAgent": "Mozilla/5.0...",
      "createdBy": "UNKNOWN",
      "createdDate": "2026-06-01T16:20:00.000Z"
    }
  ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 20
  },
  "totalElements": 150,
  "totalPages": 8
}
```

### Response Headers
```
X-Total-Count: 150
```

---

## 3.2 Get Certificate Audit Logs ⭐ NEW

**Endpoint**: `GET /api/certificates/audits`
**Auth**: Required (ADMIN or AUDITOR)
**Description**: Queries certificate management audit logs

### Query Parameters
```
- certificateId: long (optional) - Filter by certificate ID
- serialNumber: string (optional) - Filter by serial number
- action: string (optional) - UPLOAD, ACTIVATE, DEACTIVATE, REVOKE, REPLACE, etc.
- userId: string (optional) - Filter by user who performed action
- success: boolean (optional) - Filter by success/failure
- fromDate: datetime (optional) - ISO-8601 format
- toDate: datetime (optional) - ISO-8601 format
- page: int (default: 0)
- size: int (default: 20, max: 100)
- sort: string (default: "timestamp,desc")
```

### Example Request
```bash
curl -X GET 'http://localhost:8081/api/certificates/audits?action=UPLOAD&success=true&page=0&size=20' \
  -H 'Authorization: Bearer YOUR_TOKEN'
```

### Response (200 OK)
```json
{
  "content": [
    {
      "id": 45,
      "certificateId": 4,
      "serialNumber": "7a90f76300030004b78a",
      "action": "UPLOAD",
      "userId": "admin@unilink.com",
      "userEmail": "admin@unilink.com",
      "ipAddress": "192.168.1.50",
      "userAgent": "Mozilla/5.0...",
      "timestamp": "2026-06-01T15:19:28.327Z",
      "beforeState": null,
      "afterState": {
        "status": "ACTIVE",
        "entityId": "1017",
        "validTo": "2028-05-10T19:36:49.000Z"
      },
      "success": true,
      "errorMessage": null,
      "errorCode": null,
      "entityIdRequest": "1017",
      "processingTimeMs": 234
    },
    {
      "id": 46,
      "certificateId": 4,
      "serialNumber": "7a90f76300030004b78a",
      "action": "ACTIVATE",
      "userId": "admin@unilink.com",
      "userEmail": "admin@unilink.com",
      "ipAddress": "192.168.1.50",
      "timestamp": "2026-06-01T15:25:00.000Z",
      "beforeState": {
        "isActive": false
      },
      "afterState": {
        "isActive": true
      },
      "success": true,
      "processingTimeMs": 45
    }
  ],
  "totalElements": 24,
  "totalPages": 2
}
```

---

## 3.3 Get Certificate Upload History ⭐ NEW

**Endpoint**: `GET /api/certificates/{id}/history`
**Auth**: Required (ADMIN or OPERATOR)
**Description**: Gets complete history of a certificate (uploads, changes, usage)

### Example Request
```bash
curl -X GET 'http://localhost:8081/api/certificates/5/history' \
  -H 'Authorization: Bearer YOUR_TOKEN'
```

### Response (200 OK)
```json
{
  "certificate": {
    "id": 5,
    "serialNumber": "7a90f76300030004b78a",
    "entityName": "Banco Solidario S.A."
  },
  "uploadInfo": {
    "uploadedBy": "admin@unilink.com",
    "uploadedAt": "2026-06-01T15:19:28.327Z",
    "uploadMethod": "WEB_UI",
    "ipAddress": "192.168.1.50"
  },
  "changes": [
    {
      "action": "UPLOAD",
      "timestamp": "2026-06-01T15:19:28.327Z",
      "userId": "admin@unilink.com"
    },
    {
      "action": "ACTIVATE",
      "timestamp": "2026-06-01T15:25:00.000Z",
      "userId": "admin@unilink.com"
    },
    {
      "action": "DEACTIVATE",
      "timestamp": "2026-06-01T16:00:00.000Z",
      "userId": "operator@unilink.com",
      "reason": "Temporary maintenance"
    }
  ],
  "usageStats": {
    "totalDecryptions": 1523,
    "successfulDecryptions": 1520,
    "failedDecryptions": 3,
    "lastUsed": "2026-06-01T16:20:00.000Z",
    "firstUsed": "2026-06-01T15:30:00.000Z"
  },
  "expirationInfo": {
    "validFrom": "2026-05-11T19:36:49.000Z",
    "validTo": "2028-05-10T19:36:49.000Z",
    "daysRemaining": 709,
    "willExpireSoon": false,
    "notificationEmailsSent": 0
  }
}
```

---

## 3.4 Get Dashboard Statistics ⭐ NEW

**Endpoint**: `GET /api/dashboard/stats`
**Auth**: Required (ADMIN or OPERATOR)
**Description**: Gets dashboard statistics for monitoring

### Query Parameters
```
- period: string (optional) - today, week, month, year (default: today)
```

### Example Request
```bash
curl -X GET 'http://localhost:8081/api/dashboard/stats?period=today' \
  -H 'Authorization: Bearer YOUR_TOKEN'
```

### Response (200 OK)
```json
{
  "period": "today",
  "generatedAt": "2026-06-01T17:00:00.000Z",
  "certificates": {
    "total": 12,
    "active": 10,
    "expiringSoon": 2,
    "expired": 0,
    "revoked": 0,
    "byEntity": [
      {"entityId": "1017", "entityName": "Banco Solidario", "count": 3},
      {"entityId": "1009", "entityName": "Banco BISA", "count": 2},
      {"entityId": "1014", "entityName": "BCB", "count": 1}
    ]
  },
  "decryptions": {
    "total": 15234,
    "successful": 15180,
    "failed": 54,
    "successRate": 99.65,
    "avgProcessingTimeMs": 145,
    "byQrType": [
      {"qrType": "PAYMENT", "count": 12000},
      {"qrType": "ACCOUNT", "count": 2500},
      {"qrType": "UNKNOWN", "count": 734}
    ],
    "byEntity": [
      {"entityId": "1017", "count": 9000},
      {"entityId": "1009", "count": 4500},
      {"entityId": "1014", "count": 1734}
    ]
  },
  "errors": {
    "total": 54,
    "certificateNotFound": 12,
    "decryptionFailed": 30,
    "invalidQrFormat": 12,
    "topErrors": [
      {
        "errorCode": "DECRYPTION_FAILED",
        "count": 30,
        "lastOccurrence": "2026-06-01T16:45:00.000Z"
      }
    ]
  },
  "performance": {
    "avgResponseTimeMs": 145,
    "p95ResponseTimeMs": 320,
    "p99ResponseTimeMs": 580,
    "slowestCertificate": {
      "serialNumber": "abc123",
      "avgTimeMs": 450
    }
  }
}
```

---

# 4. Data Models

## Certificate DTO
```typescript
interface CertificateDTO {
  id: number;
  serialNumber: string;
  fingerprintSha256: string;
  entityId: string;
  entityName: string;
  subjectDn: string;
  issuerDn: string;
  issuerCn: string;
  validFrom: string; // ISO-8601 datetime
  validTo: string; // ISO-8601 datetime
  daysRemaining: number;
  status: "ACTIVE" | "EXPIRING_SOON" | "EXPIRED" | "REVOKED" | "SUPERSEDED";
  versionNumber: number;
  isCurrentVersion: boolean;
  isActive: boolean;
  isRevoked: boolean;
  revokedAt?: string | null;
  revokedBy?: string | null;
  revokedReason?: string | null;
  description?: string | null;
  tags?: string[] | null;
  notificationEmails?: string[] | null;
  createdDate: string;
  createdBy: string;
  lastModifiedDate: string;
  lastModifiedBy: string;
}
```

## Certificate Detail DTO
```typescript
interface CertificateDetailDTO extends CertificateDTO {
  pemContent: string; // Full PEM certificate content
}
```

## Decryption Log DTO
```typescript
interface DecryptionLogDTO {
  id: number;
  logId: string;
  keycloakClientId: string;
  mtlsCertCn: string;
  certificate: {
    id: number;
    serialNumber: string;
    entityName: string;
  };
  qrStringHash: string;
  entityIdRequest?: string;
  externalReference?: string;
  metadata?: string; // JSON string
  status: "SUCCESS" | "ERROR";
  qrType?: string;
  decryptedDataJson?: string; // JSON string
  errorMessage?: string;
  processingTimeMs: number;
  ipAddress: string;
  userAgent: string;
  createdBy: string;
  createdDate: string; // ISO-8601 datetime
}
```

## Certificate Audit Log DTO
```typescript
interface CertificateAuditLogDTO {
  id: number;
  certificateId?: number;
  serialNumber?: string;
  action: "UPLOAD" | "VALIDATE" | "ACTIVATE" | "DEACTIVATE" | "REVOKE" | "REPLACE" | "VIEW" | "DOWNLOAD" | "DECRYPT_QR";
  userId: string;
  userEmail?: string;
  ipAddress?: string;
  userAgent?: string;
  timestamp: string; // ISO-8601 datetime
  beforeState?: Record<string, any>;
  afterState?: Record<string, any>;
  success: boolean;
  errorMessage?: string;
  errorCode?: string;
  requestId?: string;
  entityIdRequest?: string;
  processingTimeMs?: number;
}
```

---

# 5. Error Responses

All error responses follow RFC 7807 Problem Details format:

```json
{
  "type": "https://api.sintesis.com.bo/problems/certificate-not-found",
  "title": "Certificate Not Found",
  "status": 404,
  "detail": "No active certificate found with serial: 7A90F76300030004B78A",
  "instance": "/api/qr/decode",
  "timestamp": "2026-06-01T15:26:53.810026600Z",
  "errorCode": "CERTIFICATE_NOT_FOUND"
}
```

## Common Error Codes

| HTTP Status | Error Code | Description |
|------------|------------|-------------|
| 400 | INVALID_REQUEST | Invalid request parameters |
| 400 | INVALID_PEM_FORMAT | Certificate PEM format is invalid |
| 400 | INVALID_QR_FORMAT | QR code format is invalid |
| 401 | UNAUTHORIZED | Missing or invalid authentication token |
| 403 | FORBIDDEN | Insufficient permissions |
| 404 | CERTIFICATE_NOT_FOUND | Certificate not found |
| 409 | DUPLICATE_CERTIFICATE | Certificate already exists |
| 500 | DECRYPTION_FAILED | Failed to decrypt QR code |
| 500 | INTERNAL_SERVER_ERROR | Unexpected server error |

---

# 6. Authentication

All protected endpoints require a JWT Bearer token:

```bash
curl -X GET 'http://localhost:8081/api/certificates' \
  -H 'Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...'
```

## Required Roles

| Endpoint Pattern | Required Role |
|-----------------|---------------|
| POST /api/certificates | ADMIN |
| POST /api/certificates/upload-file | ADMIN |
| POST /api/certificates/bulk-upload | ADMIN |
| POST /api/certificates/{id}/activate | ADMIN |
| POST /api/certificates/{id}/deactivate | ADMIN |
| POST /api/certificates/{id}/revoke | ADMIN |
| POST /api/certificates/{id}/replace | ADMIN |
| GET /api/certificates | ADMIN, OPERATOR |
| GET /api/certificates/{id} | ADMIN, OPERATOR |
| GET /api/certificates/audits | ADMIN, AUDITOR |
| POST /api/qr/decode | (Temporarily disabled) |
| GET /api/qr/audits | (Temporarily disabled) |

---

# 7. Rate Limiting

| Endpoint | Rate Limit |
|----------|-----------|
| POST /api/qr/decode | 100 req/min per client |
| POST /api/certificates | 10 req/min per user |
| POST /api/certificates/bulk-upload | 2 req/min per user |
| GET /api/certificates | 60 req/min per user |
| GET /api/qr/audits | 30 req/min per user |

---

# 8. Swagger/OpenAPI URL

Access the interactive API documentation at:

```
http://localhost:8081/swagger-ui.html
```

Download OpenAPI spec (JSON):
```
http://localhost:8081/v3/api-docs
```

Download OpenAPI spec (YAML):
```
http://localhost:8081/v3/api-docs.yaml
```

---

# 9. Testing Examples

## Test Certificate Upload
```bash
# Upload from PEM content
curl -X POST 'http://localhost:8081/api/certificates' \
  -H 'Authorization: Bearer YOUR_TOKEN' \
  -H 'Content-Type: application/json' \
  -d @- <<EOF
{
  "pemContent": "$(cat testdata/certs/1017_BcoSol_2028.crt)",
  "entityId": "1017",
  "entityName": "Banco Solidario S.A.",
  "description": "Test certificate",
  "tags": ["test", "bancosol"]
}
EOF
```

## Test QR Decryption
```bash
curl -X POST 'http://localhost:8081/api/qr/decode' \
  -H 'Content-Type: application/json' \
  -d '{
    "inputType": "DECODED_DATA",
    "content": "Bxd77WrOkqC58dbUPLK1vlkjOJiNaTLZ8Q9pbylZP3rfvt3ii1140Vyb54SMeecRzng042UQQRwwzLiGFPFltM6ISRf1jf9zkY5aYPxcxsKbG9Cdd/LiGDzAhNHzaO+xu3XofclBWF8n6wk+jozuv3PJTUYtlb6sz32x+fNv5jV5Fg64bgyzwBr2veBH4oKuQtw7NRLsC1VmsJkSN49zsBH3BvpRXW4hRPqBd3XBsBm28xO7/fq4wgO5dubSXjqy/FSwjTI9m9OKHgUCAlBXGBPkw54ff8BU6i8ybF6JXafRP7ur3RH2jhcC0XazGUsipx4+lpnjyn+IXORjQBe33g==|7A90F76300030004B78A",
    "entityIdRequest": "1017"
  }' | jq .
```

## Test Audit Query
```bash
# Get today's successful decryptions
curl -X GET 'http://localhost:8081/api/qr/audits?status=SUCCESS&fromDate=2026-06-01T00:00:00Z&size=10' \
  -H 'Authorization: Bearer YOUR_TOKEN' | jq .
```

---

**End of Documentation**

For questions or support, contact: dev@unilink.com
