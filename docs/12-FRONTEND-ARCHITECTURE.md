# 12 - Arquitectura Frontend

## Contexto

El sistema MDQR no tiene frontend implementado. Este documento define la arquitectura recomendada para construirlo desde cero.

El acceso al sistema es exclusivamente interno (operadores y administradores de Sintesis). No existe un portal publico para clientes externos en el alcance actual.

---

## Estructura del Workspace

Monorepo Angular con una app principal y paquetes compartidos:

```
mdqr-frontend/
├── apps/
│   └── admin/               # App interna (operaciones y administracion)
│       ├── src/
│       └── proxy.conf.json  # proxy en dev → gateway:8080
├── packages/
│   ├── ui/                  # Componentes standalone con signals
│   ├── sdk/                 # Cliente HTTP tipado contra el gateway
│   ├── auth/                # Logica de autenticacion + interceptors + guards
│   ├── theme/               # Tailwind preset + tokens de diseno
│   └── utils/               # Helpers puros (fecha, formato, validadores)
├── angular.json
├── tsconfig.base.json
└── package.json
```

---

## App Admin (apps/admin)

### Acceso y despliegue

- Disponible solo en red corporativa o VPN.
- No expuesta a internet.

### Autenticacion

- Realm Keycloak: `mdqr-admin`
- Client: `mdqradminservice`
- Flow: `POST /admin/auth/login` con `{ username, password }` → obtener tokens → almacenar en memoria.
- Los tokens nunca se guardan en `localStorage` ni `sessionStorage`.
- Refresh automatico via interceptor antes de que expire el `access_token`.
- En caso de 401: redirigir al login.

### Comunicacion con APIs

- Base URL en dev: `http://localhost:8080` (vía proxy Angular)
- Admin APIs: `GET /services/mdqradminservice/admin/...` — requiere JWT mdqr-admin
- Base APIs: `GET /services/mdqrbaseservice/api/...` — requiere JWT mdqr-admin
- Todas las peticiones incluyen header `Authorization: Bearer <token>`

---

## Paquetes Compartidos

### packages/sdk

Cliente HTTP tipado contra el gateway. Se puede generar desde el OpenAPI del gateway o mantener las interfaces TypeScript manualmente.

Responsabilidades:
- Definir todas las URLs de API en un solo lugar.
- Incluir interceptor que agrega el Bearer token automaticamente.
- Manejar errores en formato RFC 7807.

### packages/auth

- `authInterceptor`: agrega `Authorization` header en todas las peticiones.
- `authGuard`: protege rutas que requieren autenticacion.
- `roleGuard`: verifica permisos RBAC consultando el arbol de `GET /admin/auth/me/permissions`.
- `TokenStore`: almacena tokens en memoria (nunca en storage del navegador).

### packages/ui

Componentes standalone reutilizables con `input()`/`output()` signals. No conoce rutas, endpoints ni estado de la aplicacion.

### packages/theme

Preset de Tailwind con tokens de diseno del sistema (colores, tipografia, espaciado).

### packages/utils

Helpers puros sin dependencias de Angular: formateo de fechas, formateo de moneda, validadores comunes.

---

## Modelos TypeScript Relevantes

### CertificateDTO

```typescript
interface CertificateDTO {
  id: number;
  serialNumber: string;
  entityId: string;
  entityName: string;
  validFrom: string;   // ISO-8601
  validTo: string;     // ISO-8601
  daysRemaining: number;
  status: 'ACTIVE' | 'EXPIRING_SOON' | 'EXPIRED' | 'REVOKED' | 'SUPERSEDED';
  isActive: boolean;
  isRevoked: boolean;
}
```

### DecryptQrResponse

```typescript
interface DecryptQrResponse {
  logId: string;
  decryptedData: string;
  certificateCode: string;
  entityId: string;
  qrType: string;
  processingTimeMs: number;
  decryptedAt: string;  // ISO-8601
  fromCache: boolean;
}
```

### ProblemDetail (errores RFC 7807)

```typescript
interface ProblemDetail {
  type: string;
  title: string;
  status: number;
  detail: string;
  timestamp: string;
  errorCode: string;
  violations?: Record<string, string>;  // solo para status 400
}
```

---

## Pantallas de la App Admin

### 1. Dashboard

- Resumen de certificados por estado (ACTIVE, EXPIRING_SOON, EXPIRED, REVOKED).
- Alertas de certificados proximos a vencer.
- Ultimas desencriptaciones (tabla con las N mas recientes).
- Estadisticas de volumen por periodo.

### 2. Gestion de Certificados

- Tabla con filtros por estado, entidad, fechas.
- Bulk actions: activar, desactivar, revocar, reemplazar.
- Indicador visual para certificados en estado EXPIRING_SOON.

### 3. Detalle de Certificado

- Metadata completa del certificado.
- Historial de versiones (certificados anteriores de la misma entidad).
- Estadisticas de uso (cantidad de desencriptaciones).

### 4. Upload de Certificado

- Drag and drop de archivo PEM.
- Validacion en tiempo real (firma, vigencia, formato).
- Vista previa de metadata antes de confirmar.

### 5. Auditoria QR

- Log de desencriptaciones con filtros: entidad, estado (SUCCESS/ERROR), rango de fechas.
- Detalle de cada entrada con los datos desencriptados.

### 6. Auditoria Certificados

- Log de operaciones CRUD sobre certificados.
- Visualizacion de before/after state en formato diff.

### 7. Gestion de Usuarios

- CRUD de usuarios en Keycloak via `/services/mdqradminservice/admin/users`.
- Asignacion de roles.
- Activar/desactivar, reset de contrasena.

### 8. Gestion de Roles y Permisos

- Tabla visual de roles x menus x acciones.
- Edicion inline del mapeo rol → menu → accion.
- Refleja en tiempo real el sistema RBAC del `mdqr-ms-auth`.

---

## Path Mappings (tsconfig.base.json)

```jsonc
{
  "compilerOptions": {
    "paths": {
      "@mdqr/ui":    ["packages/ui/src/public-api.ts"],
      "@mdqr/ui/*":  ["packages/ui/src/lib/*"],
      "@mdqr/sdk":   ["packages/sdk/src/public-api.ts"],
      "@mdqr/auth":  ["packages/auth/src/public-api.ts"],
      "@mdqr/theme": ["packages/theme/src/public-api.ts"],
      "@mdqr/utils": ["packages/utils/src/public-api.ts"]
    }
  }
}
```

---

## Proxy en Desarrollo (proxy.conf.json)

```json
{
  "/services": {
    "target": "http://127.0.0.1:8080",
    "secure": false,
    "changeOrigin": true
  },
  "/admin": {
    "target": "http://127.0.0.1:8080",
    "secure": false,
    "changeOrigin": true
  },
  "/oauth2": {
    "target": "http://127.0.0.1:8080",
    "secure": false,
    "changeOrigin": true
  }
}
```

---

## Comandos de Desarrollo

```bash
# Instalar dependencias
npm install

# Iniciar app admin en desarrollo
ng serve admin

# Build de produccion
ng build admin --configuration production

# Ejecutar tests de paquetes
ng test ui
ng test sdk
```

---

## Relacion con el Backend

| Pantalla | Servicio | Ruta via gateway |
|---|---|---|
| Login | ms-auth | `POST /services/mdqradminservice/admin/auth/login` |
| Permisos | ms-auth | `GET /services/mdqradminservice/admin/auth/me/permissions` |
| Usuarios | ms-auth | `/services/mdqradminservice/admin/users/**` |
| Roles | ms-auth | `/services/mdqradminservice/admin/roles/**` |
| Menus | ms-auth | `/services/mdqradminservice/admin/menus/**` |
| Auditoria admin | ms-auth | `GET /services/mdqradminservice/admin/audit` |
| Certificados | ms-base | `/services/mdqrbaseservice/api/certificates/**` |
| Desencriptacion QR | ms-base | `POST /services/mdqrbaseservice/api/qr/decode` |
| Auditoria QR | ms-base | `GET /services/mdqrbaseservice/api/qr/audits` |
| Auditoria certificados | ms-base | `GET /services/mdqrbaseservice/api/certificates/audits` |
