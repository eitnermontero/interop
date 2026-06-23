> **[OBSOLETO]** Este documento corresponde al sistema MWC/ULQR (pagos) anterior.
> No aplica al sistema MDQR actual. Conservado solo como referencia histórica.
> Ver [docs/00-INDEX.md](00-INDEX.md) para documentación vigente.

---

# Frontend Development - API Handoff Summary

## 📋 Overview

Este documento resume todas las APIs disponibles para el desarrollo del frontend del sistema ULQR (QR Decryption System).

**Fecha**: 2026-06-01
**API Base URL**: `http://localhost:8081`
**Documentación Completa**: `FRONTEND-API-COMPLETE-SPEC.md`
**Swagger UI**: `http://localhost:8081/swagger-ui.html`

---

## 🎯 Funcionalidades Principales

### 1. Gestión de Certificados
- ✅ Upload certificado desde contenido PEM
- ✅ Upload certificado desde archivo (.crt, .pem, .cer)
- 🔄 Bulk upload (múltiples certificados)
- ✅ Listar certificados con paginación
- ✅ Ver detalle de certificado
- ✅ Descargar certificado PEM
- ✅ Filtrar por entidad bancaria
- ✅ Ver certificados por expirar
- ✅ Validar certificado (sin guardar)
- ✅ Activar/Desactivar certificado
- ✅ Revocar certificado
- ✅ Reemplazar certificado (nueva versión)

### 2. Desencriptación de QR
- ✅ Desencriptar desde contenido decodificado
- ✅ Desencriptar desde imagen Base64
- ✅ Desencriptar desde archivo de imagen

### 3. Auditoría y Monitoreo
- ✅ Ver logs de desencriptación con filtros
- 🔄 Ver logs de auditoría de certificados
- 🔄 Ver historial completo de certificado
- 🔄 Dashboard de estadísticas

**Leyenda:**
- ✅ Implementado y funcionando
- 🔄 Parcialmente implementado / Requiere completar

---

## 📚 Documentos Disponibles

| Documento | Descripción | Ubicación |
|-----------|-------------|-----------|
| **API Complete Spec** | Especificación completa de todas las APIs | `docs/FRONTEND-API-COMPLETE-SPEC.md` |
| **QR Decode Examples** | Ejemplos de uso del API de QR | `docs/QR-DECODE-EXAMPLES.md` |
| **CURL Examples** | Ejemplos de pruebas con CURL | `CURL-EXAMPLES.md` |
| **Swagger/OpenAPI** | Documentación interactiva | `http://localhost:8081/swagger-ui.html` |

---

## 🔐 Autenticación

### Bearer Token JWT
```bash
Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...
```

### Roles Requeridos
| Funcionalidad | Rol Requerido |
|--------------|---------------|
| Ver certificados | `ADMIN`, `OPERATOR` |
| Subir certificados | `ADMIN` |
| Activar/Desactivar | `ADMIN` |
| Revocar certificados | `ADMIN` |
| Ver auditorías | `ADMIN`, `AUDITOR` |
| Desencriptar QR | Temporalmente sin restricción |

---

## 📊 APIs por Módulo

### Módulo: Certificados (`/api/certificates`)

#### Endpoints Principales

| Método | Endpoint | Descripción | Estado |
|--------|----------|-------------|--------|
| GET | `/api/certificates` | Listar certificados | ✅ |
| POST | `/api/certificates` | Upload desde PEM | ✅ |
| POST | `/api/certificates/upload-file` | Upload desde archivo | ✅ |
| POST | `/api/certificates/bulk-upload` | Upload múltiple | 🔄 |
| GET | `/api/certificates/{id}` | Ver detalle | ✅ |
| GET | `/api/certificates/{id}/pem` | Descargar PEM | ✅ |
| GET | `/api/certificates/entity/{entityId}` | Por entidad | ✅ |
| GET | `/api/certificates/expiring/{days}` | Por expirar | ✅ |
| POST | `/api/certificates/validate` | Validar PEM | ✅ |
| POST | `/api/certificates/{id}/activate` | Activar | ✅ |
| POST | `/api/certificates/{id}/deactivate` | Desactivar | ✅ |
| POST | `/api/certificates/{id}/revoke` | Revocar | ✅ |
| POST | `/api/certificates/{id}/replace` | Reemplazar | ✅ |
| GET | `/api/certificates/audits` | Ver auditoría | 🔄 |
| GET | `/api/certificates/{id}/history` | Ver historial | 🔄 |

### Módulo: QR Decryption (`/api/qr`)

| Método | Endpoint | Descripción | Estado |
|--------|----------|-------------|--------|
| POST | `/api/qr/decode` | Desencriptar QR | ✅ |
| POST | `/api/qr/decode/file` | Desde imagen | ✅ |
| GET | `/api/qr/audits` | Ver logs | ✅ |

### Módulo: Dashboard (`/api/dashboard`)

| Método | Endpoint | Descripción | Estado |
|--------|----------|-------------|--------|
| GET | `/api/dashboard/stats` | Estadísticas | 🔄 |

---

## 🎨 Pantallas Sugeridas para Frontend

### 1. Dashboard Principal
**Componentes:**
- 📊 Tarjetas de estadísticas (total certs, activos, por expirar, desencriptaciones hoy)
- 📈 Gráfico de desencriptaciones por hora/día
- ⚠️ Alertas de certificados por expirar
- 📋 Últimas desencriptaciones (tabla)

**APIs a usar:**
- `GET /api/dashboard/stats?period=today`
- `GET /api/certificates/expiring/30`
- `GET /api/qr/audits?size=10&sort=createdDate,desc`

---

### 2. Gestión de Certificados
**Componentes:**
- 📋 Tabla de certificados con filtros y paginación
- ➕ Botón "Subir Certificado" (modal)
- 🔍 Búsqueda por entidad, serial, estado
- 🏷️ Badges de estado (ACTIVE, EXPIRING_SOON, REVOKED)
- 🔧 Acciones: Ver, Activar/Desactivar, Revocar, Reemplazar

**APIs a usar:**
- `GET /api/certificates?page={page}&size={size}&sort={sort}`
- `GET /api/certificates/entity/{entityId}`
- `POST /api/certificates` (para upload)
- `POST /api/certificates/upload-file`
- `POST /api/certificates/{id}/activate`
- `POST /api/certificates/{id}/deactivate`
- `POST /api/certificates/{id}/revoke`

---

### 3. Detalle de Certificado
**Componentes:**
- 📄 Información del certificado (Subject, Issuer, Serial, Fechas)
- 📊 Estadísticas de uso (total desencriptaciones, tasa de éxito)
- 📜 Historial de cambios (timeline)
- 📥 Botón "Descargar PEM"
- 🔄 Botón "Reemplazar"

**APIs a usar:**
- `GET /api/certificates/{id}`
- `GET /api/certificates/{id}/history`
- `GET /api/certificates/{id}/pem`
- `POST /api/certificates/{id}/replace`

---

### 4. Upload de Certificados
**Componentes:**
- 📤 Drag & drop para archivos .crt, .pem, .cer
- 📝 Formulario: entityId, entityName, description, tags
- ✅ Validación en tiempo real
- 📋 Preview de información del certificado antes de guardar

**APIs a usar:**
- `POST /api/certificates/validate` (preview)
- `POST /api/certificates/upload-file` (guardar)
- `POST /api/certificates/bulk-upload` (múltiples)

---

### 5. Auditoría de Certificados
**Componentes:**
- 📋 Tabla de logs con filtros
- 🔍 Filtros: Acción, Usuario, Fecha, Certificado, Éxito/Fallo
- 📊 Exportar a CSV/Excel
- 👤 Información de quién realizó la acción

**APIs a usar:**
- `GET /api/certificates/audits?action={action}&userId={userId}&fromDate={fromDate}&toDate={toDate}`

---

### 6. Desencriptación de QR
**Componentes:**
- 📷 Scanner de QR (usando cámara)
- 📤 Upload de imagen QR
- 📝 Input manual de contenido QR
- ✅ Resultado de desencriptación (JSON formatted)
- 📋 Información del certificado usado

**APIs a usar:**
- `POST /api/qr/decode` (con BASE64_IMAGE o DECODED_DATA)
- `POST /api/qr/decode/file`

---

### 7. Logs de Desencriptación
**Componentes:**
- 📋 Tabla de logs con paginación
- 🔍 Filtros: Entidad, Estado, Tipo QR, Fecha, Cliente
- 📊 Gráficos de tasa de éxito
- 📉 Análisis de errores

**APIs a usar:**
- `GET /api/qr/audits?status={status}&entityId={entityId}&fromDate={fromDate}&toDate={toDate}`

---

## 🎨 Componentes UI Sugeridos

### Tarjeta de Certificado
```tsx
interface CertificateCardProps {
  certificate: CertificateDTO;
  onActivate: (id: number) => void;
  onDeactivate: (id: number) => void;
  onRevoke: (id: number, reason: string) => void;
  onView: (id: number) => void;
}
```

### Formulario de Upload
```tsx
interface CertificateUploadFormProps {
  onSuccess: (certificate: CertificateDTO) => void;
  onError: (error: string) => void;
}
```

### Tabla de Auditoría
```tsx
interface AuditTableProps {
  filters: AuditFilters;
  onFilterChange: (filters: AuditFilters) => void;
  onPageChange: (page: number) => void;
}
```

---

## 📦 Modelos TypeScript

Ver sección "4. Data Models" en `FRONTEND-API-COMPLETE-SPEC.md` para los modelos completos.

### Principales Interfaces

```typescript
interface CertificateDTO {
  id: number;
  serialNumber: string;
  entityId: string;
  entityName: string;
  status: "ACTIVE" | "EXPIRING_SOON" | "EXPIRED" | "REVOKED" | "SUPERSEDED";
  validFrom: string;
  validTo: string;
  daysRemaining: number;
  isActive: boolean;
  isRevoked: boolean;
  createdBy: string;
  createdDate: string;
  // ... más campos
}

interface DecryptionLogDTO {
  logId: string;
  status: "SUCCESS" | "ERROR";
  qrType: string;
  decryptedDataJson?: string;
  errorMessage?: string;
  processingTimeMs: number;
  createdDate: string;
  // ... más campos
}
```

---

## 🧪 Testing

### Ejemplos de Requests

#### 1. Listar Certificados
```bash
curl -X GET 'http://localhost:8081/api/certificates?page=0&size=20' \
  -H 'Authorization: Bearer YOUR_TOKEN'
```

#### 2. Upload Certificado
```bash
curl -X POST 'http://localhost:8081/api/certificates/upload-file' \
  -H 'Authorization: Bearer YOUR_TOKEN' \
  -F 'file=@certificate.crt' \
  -F 'entityId=1017' \
  -F 'entityName=Banco Solidario'
```

#### 3. Desencriptar QR
```bash
curl -X POST 'http://localhost:8081/api/qr/decode' \
  -H 'Content-Type: application/json' \
  -d '{
    "inputType": "DECODED_DATA",
    "content": "Bxd77WrOkqC...==|7A90F76300030004B78A",
    "entityIdRequest": "1017"
  }'
```

Ver más ejemplos en: `docs/QR-DECODE-EXAMPLES.md` y `CURL-EXAMPLES.md`

---

## ⚠️ Consideraciones Importantes

### 1. Manejo de Errores
- Todos los errores siguen el formato RFC 7807 Problem Details
- Verificar siempre el campo `errorCode` para manejo específico
- Mostrar mensajes user-friendly basados en el `errorCode`

### 2. Paginación
- El backend usa paginación 0-indexed
- Máximo `size` permitido: 100 items
- Headers de respuesta incluyen `X-Total-Count`

### 3. Fechas
- Todas las fechas están en formato ISO-8601 UTC
- Ejemplo: `2026-06-01T15:19:28.327Z`
- Usar librerías como `date-fns` o `dayjs` para formateo

### 4. Estados de Certificado
- **ACTIVE**: Certificado válido y activo
- **EXPIRING_SOON**: Expira en menos de 30 días
- **EXPIRED**: Ya expiró
- **REVOKED**: Revocado permanentemente
- **SUPERSEDED**: Reemplazado por nueva versión

### 5. Seguridad
- Nunca mostrar el PEM completo en listas (solo en detalle)
- Validar acciones críticas (revoke, deactivate) con confirmación
- Logs de auditoría deben ser solo lectura
- No permitir modificar logs

### 6. Performance
- Implementar debouncing en búsquedas (300-500ms)
- Usar paginación virtual para tablas grandes
- Cachear datos de certificados (5 minutos TTL)
- Lazy load de imágenes QR

---

## 🚀 Próximos Pasos

### Para el Frontend Developer

1. **Setup Inicial**
   - [ ] Clonar repositorio
   - [ ] Configurar variables de entorno (API_URL, AUTH_SERVER)
   - [ ] Instalar dependencias
   - [ ] Configurar autenticación (JWT)

2. **Desarrollo**
   - [ ] Crear modelos TypeScript (copiar de spec)
   - [ ] Crear servicio de API (axios/fetch)
   - [ ] Implementar autenticación
   - [ ] Desarrollar componentes UI

3. **Testing**
   - [ ] Tests unitarios de componentes
   - [ ] Tests de integración con API
   - [ ] Tests E2E de flujos principales

### Para el Backend Developer

1. **Completar APIs Faltantes**
   - [ ] Implementar `POST /api/certificates/bulk-upload`
   - [ ] Completar `GET /api/certificates/audits` (servicio)
   - [ ] Implementar `GET /api/certificates/{id}/history`
   - [ ] Crear `GET /api/dashboard/stats`

2. **Optimizaciones**
   - [ ] Agregar índices en tablas de auditoría
   - [ ] Implementar rate limiting
   - [ ] Agregar cache para certificados activos

---

## 📞 Contacto y Soporte

- **Backend Lead**: backend@unilink.com
- **API Issues**: Crear issue en GitHub
- **Documentación**: Ver `docs/` directory

---

**Última Actualización**: 2026-06-01
**Versión**: 1.0.0
