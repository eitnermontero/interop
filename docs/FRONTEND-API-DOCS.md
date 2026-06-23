> **[OBSOLETO]** Este documento corresponde al sistema MWC/ULQR (pagos) anterior.
> No aplica al sistema MDQR actual. Conservado solo como referencia histórica.
> Ver [docs/00-INDEX.md](00-INDEX.md) para documentación vigente.

---

# APIs Disponibles para Frontend - Unilink QR Decrypt

**Backend URL**: `http://localhost:8081`
**Estado**: ✅ 100% Implementado y Funcional

---

## 🔐 Autenticación (Temporalmente Deshabilitada)

Para desarrollo local, la autenticación OAuth2 está **deshabilitada**.

**Para producción** (cuando se habilite):
- Tipo: OAuth2 + JWT (Keycloak)
- Header: `Authorization: Bearer <token>`
- Roles: `API_CLIENT`, `ADMIN`, `AUDITOR`

---

## 📋 API 1: Gestión de Certificados

Base URL: `/api/certificates`

### 1.1 Listar Certificados

**GET** `/api/certificates`

**Descripción**: Obtiene todos los certificados disponibles en el sistema.

**Response 200**:
```json
[
  {
    "id": 1,
    "certificateCode": "7A90F76300030004B78A",
    "entityId": "1017",
    "subjectDn": "CN=banco.solidario.com.bo, O=Banco Solidario, C=BO",
    "issuerDn": "CN=CA-ROOT, O=Authority",
    "serialNumber": "7A90F76300030004B78A",
    "validFrom": "2025-01-15T00:00:00Z",
    "validTo": "2028-05-13T23:59:59Z",
    "daysRemaining": 750,
    "status": "ACTIVE",
    "isCached": true,
    "lastSyncedAt": "2026-05-26T20:00:00Z"
  }
]
```

**Campos importantes**:
- `status`: `ACTIVE`, `EXPIRING_SOON` (< 30 días), `EXPIRED`, `REVOKED`
- `daysRemaining`: Días hasta que expire
- `isCached`: Si está en cache Redis

**Headers de respuesta**:
- `X-Total-Count`: Total de certificados

**Ejemplo TypeScript**:
```typescript
interface Certificate {
  id: number;
  certificateCode: string;
  entityId: string;
  subjectDn: string;
  issuerDn: string;
  serialNumber: string;
  validFrom: string;
  validTo: string;
  daysRemaining: number;
  status: 'ACTIVE' | 'EXPIRING_SOON' | 'EXPIRED' | 'REVOKED';
  isCached: boolean;
  lastSyncedAt: string;
}

// Service method
getCertificates(): Observable<Certificate[]> {
  return this.http.get<Certificate[]>(`${this.apiUrl}/certificates`);
}
```

---

### 1.2 Importar Certificado

**POST** `/api/certificates`

**Descripción**: Importa un nuevo certificado al sistema.

**Request Body**:
```json
{
  "alias": "banco_test_2026",
  "pemContent": "-----BEGIN CERTIFICATE-----\nMIIBIjAN...\n-----END CERTIFICATE-----",
  "entityId": "1017"
}
```

**Response 201**:
```json
{
  "id": 42,
  "certificateCode": "ABC123DEF",
  "message": "Certificado importado exitosamente"
}
```

**Validaciones**:
- `alias`: Requerido, único, 3-100 caracteres
- `pemContent`: Requerido, formato PEM válido
- `entityId`: Requerido

**Errores comunes**:
- `400`: PEM inválido o alias duplicado
- `500`: Error al procesar certificado

**Ejemplo TypeScript**:
```typescript
interface ImportCertificateRequest {
  alias: string;
  pemContent: string;
  entityId: string;
}

importCertificate(request: ImportCertificateRequest): Observable<any> {
  return this.http.post(`${this.apiUrl}/certificates`, request);
}
```

---

### 1.3 Revocar Certificado

**POST** `/api/certificates/{id}/revoke`

**Descripción**: Revoca un certificado (lo marca como inactivo).

**Path Parameter**:
- `id`: ID del certificado

**Response 204**: No Content (éxito)

**Errores**:
- `404`: Certificado no encontrado
- `400`: Certificado ya está revocado

**Ejemplo TypeScript**:
```typescript
revokeCertificate(id: number): Observable<void> {
  return this.http.post<void>(`${this.apiUrl}/certificates/${id}/revoke`, {});
}
```

---

### 1.4 Invalidar Cache

**POST** `/api/certificates/cache/invalidate`

**Descripción**: Invalida el cache de certificados en Redis.

**Response 204**: No Content

**Uso**: Llamar después de importar/revocar certificados para forzar recarga.

**Ejemplo TypeScript**:
```typescript
invalidateCache(): Observable<void> {
  return this.http.post<void>(`${this.apiUrl}/certificates/cache/invalidate`, {});
}
```

---

## 📊 API 2: Auditoría de Desencriptaciones

Base URL: `/api/qr`

### 2.1 Consultar Auditorías

**GET** `/api/qr/audits`

**Descripción**: Obtiene el historial de desencriptaciones con filtros y paginación.

**Query Parameters**:
| Parámetro | Tipo | Requerido | Descripción |
|-----------|------|-----------|-------------|
| `keycloakClientId` | string | No | Filtrar por ID de cliente OAuth2 |
| `certificateCode` | string | No | Filtrar por código de certificado |
| `entityId` | string | No | Filtrar por entidad |
| `status` | string | No | `SUCCESS` o `ERROR` |
| `fromDate` | string | No | ISO 8601: `2026-05-01T00:00:00Z` |
| `toDate` | string | No | ISO 8601: `2026-05-31T23:59:59Z` |
| `page` | number | No | Número de página (default: 0) |
| `size` | number | No | Tamaño de página (default: 20, max: 100) |
| `sort` | string | No | Campo para ordenar (default: `createdDate`) |
| `order` | string | No | `asc` o `desc` (default: `desc`) |

**Response 200**:
```json
{
  "content": [
    {
      "id": 123,
      "logId": "6748a9d3f1e2c4b5a6d7e8f9",
      "keycloakClientId": "mobile-app-001",
      "certificateCode": "7A90F76300030004B78A",
      "entityId": "1017",
      "qrStringHash": "a3d5e7f9...",
      "status": "SUCCESS",
      "qrType": "PAYMENT",
      "processingTimeMs": 45,
      "errorMessage": null,
      "ipAddress": "192.168.1.100",
      "userAgent": "Mozilla/5.0...",
      "createdBy": "system",
      "createdDate": "2026-05-26T20:30:00Z",
      "mtlsCertCn": null
    }
  ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 20,
    "sort": {
      "sorted": true,
      "unsorted": false
    }
  },
  "totalElements": 1543,
  "totalPages": 78,
  "size": 20,
  "number": 0,
  "first": true,
  "last": false
}
```

**Headers de respuesta**:
- `X-Total-Count`: Total de registros (sin paginación)

**Ejemplo TypeScript**:
```typescript
interface AuditLog {
  id: number;
  logId: string;
  keycloakClientId: string;
  certificateCode: string;
  entityId: string;
  qrStringHash: string;
  status: 'SUCCESS' | 'ERROR';
  qrType: string;
  processingTimeMs: number;
  errorMessage?: string;
  ipAddress: string;
  userAgent: string;
  createdBy: string;
  createdDate: string;
  mtlsCertCn?: string;
}

interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
  first: boolean;
  last: boolean;
}

interface AuditFilters {
  keycloakClientId?: string;
  certificateCode?: string;
  entityId?: string;
  status?: 'SUCCESS' | 'ERROR';
  fromDate?: string;
  toDate?: string;
  page?: number;
  size?: number;
  sort?: string;
  order?: 'asc' | 'desc';
}

getAudits(filters: AuditFilters): Observable<PageResponse<AuditLog>> {
  const params = new HttpParams({ fromObject: filters as any });
  return this.http.get<PageResponse<AuditLog>>(`${this.apiUrl}/qr/audits`, { params });
}
```

---

### 2.2 Desencriptar QR (Opcional - para testing)

**POST** `/api/qr/decode`

**Descripción**: Desencripta un código QR (opcional, para probar desde UI).

**Request**:
```json
{
  "inputType": "DECODED_DATA",
  "content": "encrypted_base64|serial",
  "entityIdRequest": "1017"
}
```

**Response 200**:
```json
{
  "logId": "abc123",
  "decryptedData": "datos|desencriptados|...",
  "certificateCode": "7A90F76300030004B78A",
  "entityId": "1017",
  "qrType": "PAYMENT",
  "processingTimeMs": 45,
  "decryptedAt": "2026-05-26T20:30:00Z",
  "fromCache": true
}
```

---

## 🎨 UI Mockups Sugeridos

### Pantalla 1: Gestión de Certificados

**Ruta**: `/admin/certificates`

**Layout**:
```
┌─────────────────────────────────────────────────────────────┐
│ 📜 Gestión de Certificados                    [+ Importar]  │
├─────────────────────────────────────────────────────────────┤
│ 🔍 Buscar: [____________]  Estado: [Todos ▼]                │
├─────────────────────────────────────────────────────────────┤
│ Serial         │ Entidad │ Válido Hasta │ Estado   │ Acciones│
│ 7A90F7...B78A │ 1017    │ 2028-05-13   │ ✓ ACTIVO │ [Revocar]│
│ 30A1BE...93BD │ 1056    │ 2027-03-20   │ ⚠ EXPIRA │ [Revocar]│
│ 69e6b3...     │ 1426    │ 2025-12-01   │ ✗ EXPIRÓ │ [Eliminar]│
├─────────────────────────────────────────────────────────────┤
│                                      Página 1 de 5  [< >]    │
└─────────────────────────────────────────────────────────────┘
```

**Componentes PrimeNG**:
- `p-table` con lazy loading
- `p-tag` para status (success, warning, danger)
- `p-dialog` para modal de importar
- `p-confirmDialog` para revocar
- `p-button` para acciones

**Estados visuales**:
- 🟢 `ACTIVE`: verde
- 🟡 `EXPIRING_SOON`: amarillo (< 30 días)
- 🔴 `EXPIRED`: rojo
- ⚫ `REVOKED`: gris

---

### Pantalla 2: Auditoría de Desencriptaciones

**Ruta**: `/admin/audits`

**Layout**:
```
┌─────────────────────────────────────────────────────────────────┐
│ 📊 Auditoría de Desencriptaciones                               │
├─────────────────────────────────────────────────────────────────┤
│ Filtros:                                                         │
│ Cliente: [___________]  Certificado: [___________]              │
│ Entidad: [___________]  Estado: [Todos ▼]                       │
│ Desde: [📅 2026-05-01]  Hasta: [📅 2026-05-31]  [Buscar] [Excel]│
├─────────────────────────────────────────────────────────────────┤
│ Log ID  │ Cliente │ Certif. │ Estado │ Tipo    │ Tiempo │ Fecha│
│ abc123  │ app-001 │ 7A90... │ ✓ OK   │ PAYMENT │ 45ms   │ 20:30│
│ def456  │ app-002 │ 30A1... │ ✗ ERR  │ -       │ 120ms  │ 20:25│
├─────────────────────────────────────────────────────────────────┤
│                                Página 1 de 78  [< >]  20 items │
└─────────────────────────────────────────────────────────────────┘
```

**Componentes PrimeNG**:
- `p-table` con lazy loading server-side
- `p-calendar` con rango de fechas
- `p-dropdown` para filtros
- `p-button` para exportar Excel
- `p-tooltip` para ver error completo

**Features**:
- ✅ Filtros reactivos (debounce 500ms)
- ✅ Exportación a Excel
- ✅ Lazy loading (cargar al hacer scroll)
- ✅ Click en fila para ver detalles completos
- ✅ Auto-refresh cada 30 segundos (opcional)

---

## 🚀 Ejemplo de Servicio Angular Completo

```typescript
import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '@env/environment';

@Injectable({ providedIn: 'root' })
export class QrManagementService {
  private apiUrl = environment.apiUrl; // http://localhost:8081/api

  constructor(private http: HttpClient) {}

  // Certificates
  getCertificates(): Observable<Certificate[]> {
    return this.http.get<Certificate[]>(`${this.apiUrl}/certificates`);
  }

  importCertificate(request: ImportCertificateRequest): Observable<any> {
    return this.http.post(`${this.apiUrl}/certificates`, request);
  }

  revokeCertificate(id: number): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/certificates/${id}/revoke`, {});
  }

  invalidateCache(): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/certificates/cache/invalidate`, {});
  }

  // Audits
  getAudits(filters: AuditFilters): Observable<PageResponse<AuditLog>> {
    let params = new HttpParams();

    Object.keys(filters).forEach(key => {
      const value = (filters as any)[key];
      if (value !== null && value !== undefined && value !== '') {
        params = params.set(key, value.toString());
      }
    });

    return this.http.get<PageResponse<AuditLog>>(`${this.apiUrl}/qr/audits`, { params });
  }

  exportAudits(filters: AuditFilters): Observable<Blob> {
    // TODO: Implementar endpoint de exportación
    return this.http.get(`${this.apiUrl}/qr/audits/export`, {
      params: filters as any,
      responseType: 'blob'
    });
  }
}
```

---

## 📝 Checklist para Frontend Developer

### Setup Inicial
- [ ] Clonar repositorio
- [ ] Checkout a `feature/frontend-admin-ui`
- [ ] Ejecutar `./start-dev.sh` (ver sección 3)
- [ ] Verificar APIs con Postman/curl
- [ ] Probar Swagger UI: `http://localhost:8081/swagger-ui.html`

### Implementación
- [ ] Crear módulo `QrManagementModule`
- [ ] Crear servicio `QrManagementService`
- [ ] Crear modelos TypeScript (interfaces arriba)
- [ ] Pantalla 1: `CertificateListComponent`
  - [ ] Tabla con certificados
  - [ ] Modal importar
  - [ ] Confirmar revocar
  - [ ] Badges de status
- [ ] Pantalla 2: `AuditListComponent`
  - [ ] Tabla con lazy loading
  - [ ] Filtros reactivos
  - [ ] Exportar a Excel
  - [ ] Modal de detalles
- [ ] Routing configurado
- [ ] Tests unitarios básicos

### Testing
- [ ] Importar certificado desde UI
- [ ] Listar certificados
- [ ] Revocar certificado
- [ ] Filtrar auditorías por fecha
- [ ] Paginación funciona
- [ ] Exportar Excel funciona

---

## 🐛 Troubleshooting

### Error: CORS
**Solución**: El backend ya tiene CORS habilitado para `http://localhost:4200`

### Error: 401 Unauthorized
**Solución**: Autenticación deshabilitada temporalmente. Si aparece, contactar backend dev.

### Error: 502 Bad Gateway
**Solución**: El Go API o PostgreSQL no están corriendo. Ejecutar `./start-dev.sh`

### No aparecen certificados
**Solución**: Verificar que el backend esté conectado a PostgreSQL y que hayan datos de prueba.

---

**Contacto Backend Dev**: [Tu nombre/email]
**Última actualización**: 26 Mayo 2026
