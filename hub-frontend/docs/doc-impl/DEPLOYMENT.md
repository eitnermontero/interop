# Guía de Despliegue - Genesis C-Cart

## Ambientes Soportados

- **Desarrollo (`dev`)**: Máquina local
- **Control de Calidad (`qa`)**: Servidor QA
- **Producción (`prod`)**: Servidor de producción

---

## Configuración de Variables de Entorno

### 1. Preparar el archivo `.env`

Cada ambiente necesita su propio archivo `.env` con las variables específicas:

```bash
# Para desarrollo (ya existe)
cp .env.example .env

# Para QA
cp .env.production.example .env.qa
# Editar .env.qa con URLs de QA

# Para producción  
cp .env.production.example .env
# Editar .env con URLs de producción
```

### 2. Variables Críticas por Ambiente

#### Desarrollo (`.env`)
```env
KEYCLOAK_HOSTNAME=http://localhost:8180
KEYCLOAK_HOSTNAME_BACKCHANNEL=http://keycloak:8080
CORS_ALLOWED_ORIGINS=http://localhost:8080,http://localhost:8081,http://localhost:4300
KC_REDIRECT_URIS=http://localhost:8080/*,http://localhost:8081/*
KC_WEB_ORIGINS=http://localhost:8080,http://localhost:8081
```

#### QA (`.env.qa`)
```env
KEYCLOAK_HOSTNAME=https://keycloak.qa.internal.com
KEYCLOAK_HOSTNAME_BACKCHANNEL=http://keycloak:8080
CORS_ALLOWED_ORIGINS=https://qa-gateway.internal.com,https://qa-admin.internal.com
KC_REDIRECT_URIS=https://qa-gateway.internal.com/*
KC_WEB_ORIGINS=https://qa-gateway.internal.com
VAULT_HOST=vault.qa.internal.com
VAULT_SCHEME=https
```

#### Producción (`.env`)
```env
KEYCLOAK_HOSTNAME=https://keycloak.example.com
KEYCLOAK_HOSTNAME_BACKCHANNEL=http://keycloak:8080
CORS_ALLOWED_ORIGINS=https://api.example.com,https://admin.example.com
KC_REDIRECT_URIS=https://api.example.com/*
KC_WEB_ORIGINS=https://api.example.com
VAULT_HOST=vault.example.com
VAULT_SCHEME=https
```

---

## Despliegue Paso a Paso

### Antes de Desplegar

Asegúrate de que:

1. ✅ **Vault está disponible** y los secretos están inicializados
2. ✅ **Keycloak es accesible** desde el ambiente
3. ✅ **Certificados SSL** están configurados (para QA y Prod)
4. ✅ **DNS** está configurado para las URLs de servicios
5. ✅ **Firewall** permite comunicación entre contenedores

### Pasos de Despliegue

#### 1. Cargar el archivo `.env` correcto

```bash
# Para desarrollo
source .env

# Para QA (usando archivo separado)
export $(cat .env.qa | grep -v '^#' | xargs)

# Para producción
export $(cat .env | grep -v '^#' | xargs)
```

#### 2. Inicializar Vault (si es nueva instalación)

```bash
deploy/scripts/vault-seed.sh
```

Este script siembra los secretos KV en Vault (modo single/dev con root token auto-unseal): credenciales de Redis, base de datos y clientes Keycloak (service-client y admin-client).

#### 3. Sincronizar Keycloak

```bash
bash deploy/scripts/keycloak-sync.sh --yes-to-all
```

Este script:
- Crea el realm `middleware-core`
- Configura roles y permisos
- Crea clientes OAuth2
- Sintoniza redirect URIs y web origins desde `KC_REDIRECT_URIS` y `KC_WEB_ORIGINS`
- Crea usuarios de prueba

#### 4. Iniciar los Servicios

```bash
# Desarrollo: seguir el manual en deploy/development/README.md

# QA o Producción (usar docker-compose directamente)
docker-compose -f docker-compose.yml up -d
```

#### 5. Verificar Salud de los Servicios

```bash
# Gateway
curl -s http://localhost:8080/api/v1/reports -H "Authorization: Bearer test" | grep -q "unauthorized" && echo "✓ Gateway activo"

# Keycloak
curl -s http://localhost:8180/realms/middleware-core/.well-known/openid-configuration | jq '.issuer'

# Vault
curl -s -H "X-Vault-Token: $VAULT_ROOT_TOKEN" http://localhost:8200/v1/sys/health | jq '.sealed'
```

---

## Configuración de Vault para Cada Ambiente

Vault almacena todas las credenciales sensibles. **Estructura de secretos:**

```
secret/
└── middleware-core/
    ├── system/
    │   ├── database/          # Credenciales PostgreSQL
    │   └── redis/             # Credenciales Redis
    ├── keycloak/
    │   ├── service-client/    # Client ID/Secret para servicios
    │   └── admin-client/      # Client admin
    ├── partners/
    │   └── ptnr_demo_001/     # Credenciales de socios
    └── gateway/               # Configuración del gateway
```

### Estructura de Directorios en Vault

```bash
# Ver estructura de Vault
vault kv list secret/middleware-core/

# Actualizar configuración de Keycloak en Vault
vault kv put secret/middleware-core/keycloak/service-client \
  auth-server-url="https://keycloak.qa.internal.com" \
  client-id="middleware-core-api" \
  client-secret="secret123" \
  realm="middleware-core"
```

---

## Variables de Entorno Requeridas por Servicio

### Gateway

| Variable | Origen | Propósito |
|----------|--------|----------|
| `VAULT_HOST` | `.env` | Host de Vault |
| `VAULT_ROLE_ID` | Vault generado | AppRole ID para autenticación |
| `VAULT_SECRET_ID` | Vault generado | AppRole Secret para autenticación |
| `KEYCLOAK_SERVICE_CLIENT_AUTH_SERVER_URL` | Vault | URL de Keycloak para validación de JWT |
| `CORS_ALLOWED_ORIGINS` | `.env` | Orígenes permitidos para CORS |

### Keycloak

| Variable | Origen | Propósito |
|----------|--------|----------|
| `KC_HOSTNAME` | `.env` | URL externa de Keycloak (para tokens) |
| `KC_HOSTNAME_BACKCHANNEL` | `.env` | URL interna para comunicación servidor-a-servidor |
| `KEYCLOAK_ADMIN` | `.env` | Usuario admin |
| `KEYCLOAK_ADMIN_PASSWORD` | `.env` | Contraseña admin |

### Scripts de Despliegue

| Variable | Origen | Propósito |
|----------|--------|----------|
| `KC_URL` | `.env` | URL de Keycloak para sincronización |
| `KC_USERNAME` | `.env` | Usuario admin para sync |
| `KC_PASSWORD` | `.env` | Contraseña admin para sync |
| `KC_REDIRECT_URIS` | `.env` | Orígenes de redirección para clientes |
| `KC_WEB_ORIGINS` | `.env` | Orígenes web permitidos |

---

## Resolución de Problemas de Despliegue

### Problema: Gateway no puede validar JWT

**Síntoma**: Respuesta `401 Unauthorized` con "No authenticated client"

**Solución**:
1. Verificar que `KEYCLOAK_SERVICE_CLIENT_AUTH_SERVER_URL` en Vault sea accesible desde el gateway
2. Verificar que el issuer en el token coincida con la configuración
3. Verificar conectividad de red entre gateway y Keycloak

```bash
# Verificar configuración del gateway
echo "Auth server URL: $(vault kv get -field=auth-server-url secret/middleware-core/keycloak/service-client)"

# Verificar que Keycloak es accesible
curl -s https://keycloak.example.com/realms/middleware-core/.well-known/openid-configuration | jq '.issuer'
```

### Problema: Keycloak CORS error

**Síntoma**: Errores CORS desde el frontend

**Solución**:
1. Verificar que `KC_WEB_ORIGINS` contiene el dominio del frontend
2. Re-ejecutar `keycloak-sync.sh` después de cambiar valores

```bash
# Actualizar web origins
export KC_WEB_ORIGINS="https://app.example.com,https://admin.example.com"
bash deploy/scripts/keycloak-sync.sh --yes-to-all
```

### Problema: Servicios no se conectan a Vault

**Síntoma**: Errores "permission denied" en logs

**Solución**:
1. Verificar que el root token es correcto y que Vault esta en modo dev/single auto-unseal
2. Verificar que VAULT_HOST y VAULT_ROOT_TOKEN son correctos

```bash
# Verificar estado de Vault
vault status

# Re-sembrar secretos KV si es necesario
deploy/scripts/vault-seed.sh
```

---

## Checklist de Despliegue

- [ ] `.env` configurado con URLs correctas
- [ ] Vault inicializado y accesible
- [ ] Keycloak inicializado
- [ ] `keycloak-sync.sh` ejecutado exitosamente
- [ ] Gateway inicia sin errores
- [ ] Servicios registrados en Consul
- [ ] Test de conectividad: `curl http://gateway/api/v1/reports` con JWT válido
- [ ] Logs sin errores de autenticación
- [ ] CORS funciona desde el frontend
- [ ] Base de datos accesible
- [ ] Redis accesible

---

## Notas Importantes para Producción

1. **No usar localhost en URLs de producción**
2. **Usar HTTPS en todas las URLs externas**
3. **Mantener secretos en Vault, no en código**
4. **Configurar backups de Vault**
5. **Habilitar logs auditables**
6. **Implementar monitoreo de salud**
7. **Rotar credenciales regularmente**
8. **Usar redes privadas entre servicios**

