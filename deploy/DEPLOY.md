# Guía de despliegue en servidor Linux

Servidor objetivo: Linux con PostgreSQL, nginx y Docker ya instalados.
Registro de imágenes: GitHub Container Registry (`ghcr.io`).

---

## Stack a desplegar

| Componente | Docker | Puerto expuesto |
|---|---|---|
| `hub-gateway` | sí | 8080 |
| `hub-ms-base` (base-service) | sí | 8084 (interno 8081) |
| `hub-ms-auth` (admin-service) | sí | 8083 |
| `hub-admin-fe` | sí | 8081 |
| Keycloak | sí (tools) | 8180 |
| Consul | sí (tools) | 8500 |
| Vault | sí (tools) | 8200 |
| Redis | sí (tools) | 6379 |
| PostgreSQL | host (ya instalado) | 5432 |

---

## 1. Desde tu máquina local: build y push de imágenes

### 1.1 Autenticar en ghcr.io

Crear un Personal Access Token en GitHub con permisos `write:packages`.

```bash
echo "<GH_TOKEN>" | docker login ghcr.io -u <GH_USERNAME> --password-stdin
```

### 1.2 Build y push con Jib

```bash
./gradlew :hub-gateway:jib  -PdockerRegistry=ghcr.io/<GH_USERNAME>
./gradlew :hub-ms-base:jib  -PdockerRegistry=ghcr.io/<GH_USERNAME>
./gradlew :hub-ms-auth:jib  -PdockerRegistry=ghcr.io/<GH_USERNAME>
```

Esto empuja las imágenes:
- `ghcr.io/<GH_USERNAME>/hub-gateway:1.0.x`
- `ghcr.io/<GH_USERNAME>/hub-ms-base:1.0.x`
- `ghcr.io/<GH_USERNAME>/hub-ms-auth:1.0.x`

> El frontend se construye por separado con bun/Angular CLI y se empaqueta
> como imagen nginx. Por ahora el build del FE queda pendiente.

### 1.3 Hacer las imágenes públicas (o configurar pull secret)

En GitHub → tu paquete → Settings → Change visibility → Public.
Si es privado, el servidor necesita hacer `docker login ghcr.io` antes de `docker compose pull`.

---

## 2. Preparar el servidor

### 2.1 Clonar el repositorio

```bash
cd /opt
git clone https://github.com/<GH_USERNAME>/<REPO_NAME>.git hub-interop
cd hub-interop
```

### 2.2 Crear las bases de datos PostgreSQL

```bash
sudo -u postgres psql <<'SQL'
CREATE DATABASE hub_interop;
CREATE DATABASE hub_auth;
CREATE USER hub WITH PASSWORD '<STRONG_PASSWORD>';
GRANT ALL PRIVILEGES ON DATABASE hub_interop TO hub;
GRANT ALL PRIVILEGES ON DATABASE hub_auth TO hub;
-- PostgreSQL 15+ requiere también:
\c hub_interop
GRANT ALL ON SCHEMA public TO hub;
\c hub_auth
CREATE SCHEMA admin;
GRANT ALL ON SCHEMA admin TO hub;
SQL
```

### 2.3 Crear la red Docker compartida

```bash
docker network create --driver bridge \
  --opt com.docker.network.driver.mtu=1500 \
  hub-shared
```

### 2.4 Crear directorios de logs

```bash
mkdir -p /var/log/hub/{gateway,base-service,admin-service}
```

---

## 3. Configurar el stack de tools (Keycloak, Consul, Vault, Redis)

```bash
cd /opt/hub-interop/deploy/tools
cp .env.example .env   # si existe, si no, crear desde cero
```

Editar `.env` con los valores mínimos:

```dotenv
COMPOSE_PROFILES=single
HUB_TOOLS_BIND_IP=0.0.0.0
KEYCLOAK_ADMIN_USER=admin
KEYCLOAK_ADMIN_PASSWORD=<STRONG_PASSWORD>
```

Levantar:

```bash
cd /opt/hub-interop/deploy/tools
docker compose up -d
```

Verificar que los 4 servicios estén `healthy`:

```bash
docker compose ps
```

---

## 4. Primer setup (solo una vez tras instalar o limpiar volúmenes)

### 4.1 Seed de Vault

```bash
cd /opt/hub-interop

# Para hub-ms-auth (namespace hub-auth)
TOOLS_HOST=127.0.0.1 DB_NAME=hub_auth \
  deploy/scripts/vault-seed.sh \
    --ns hub-auth \
    --kc-realm hub-admin

# Para hub-ms-base y gateway (namespace hub-base)
TOOLS_HOST=127.0.0.1 DB_NAME=hub_interop \
  deploy/scripts/vault-seed.sh \
    --ns hub-base \
    --kc-realm hub-admin
```

El script pedirá interactivamente los valores (password de DB, secrets de Keycloak).
Pasar el usuario/password del paso 2.2.

### 4.2 Sync de Keycloak

```bash
deploy/scripts/keycloak-sync-admin.sh
deploy/scripts/keycloak-sync-partner.sh
```

---

## 5. Configurar y levantar los servicios

### 5.1 Crear el .env de producción

```bash
cd /opt/hub-interop/deploy/production
cp .env.example .env
```

Editar `.env` — valores obligatorios a cambiar:

```dotenv
# Imágenes (ajustar al nombre de tu usuario de GitHub)
HUB_GATEWAY_IMAGE=ghcr.io/<GH_USERNAME>/hub-gateway
HUB_BASE_IMAGE=ghcr.io/<GH_USERNAME>/hub-ms-base
HUB_ADMIN_IMAGE=ghcr.io/<GH_USERNAME>/hub-ms-auth

# Tags de las imágenes
HUB_GATEWAY_VERSION=1.0.0
HUB_BASE_VERSION=1.0.0
HUB_ADMIN_VERSION=1.0.0

# Vault (token por defecto en dev mode = root)
HUB_VAULT_TOKEN=root

# Keycloak — usar IP real del servidor, NO localhost ni host.docker.internal
HUB_KEYCLOAK_URL=http://<IP_SERVIDOR>:8180

# CORS — URL del frontend para el browser
HUB_GATEWAY_CORS_ALLOWED_ORIGINS=http://<IP_SERVIDOR>:8081

# Logs
LOG_DIR=/var/log/hub
```

Las URLs de DB y perfiles ya están correctos en el `.env.example`:
```dotenv
HUB_BASE_DB_URL=jdbc:postgresql://host.docker.internal:5432/hub_interop
HUB_ADMIN_DB_URL=jdbc:postgresql://host.docker.internal:5432/hub_auth
HUB_GATEWAY_PROFILE=prod
HUB_BASE_PROFILE=prod
HUB_ADMIN_PROFILE=prod
```

### 5.2 Levantar los servicios

```bash
cd /opt/hub-interop/deploy/production
docker compose pull
docker compose up -d
```

Verificar estado:

```bash
docker compose ps
docker compose logs -f --tail=50
```

---

## 6. Configurar nginx como reverse proxy

Archivo: `/etc/nginx/sites-available/hub-interop`

```nginx
server {
    listen 80;
    server_name <IP_SERVIDOR_O_DOMINIO>;

    # Gateway (APIs y token endpoint)
    location / {
        proxy_pass         http://127.0.0.1:8080;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
        proxy_read_timeout 60s;
    }
}
```

Activar y recargar:

```bash
sudo ln -s /etc/nginx/sites-available/hub-interop /etc/nginx/sites-enabled/
sudo nginx -t
sudo systemctl reload nginx
```

> Para HTTPS: agregar certificado SSL (certbot/Let's Encrypt) y actualizar
> `HUB_KEYCLOAK_URL` con `https://`.

---

## 7. Verificación

```bash
# Health del gateway
curl http://localhost:8080/management/health

# Health del base-service (vía gateway)
curl http://localhost:8080/services/hubbaseservice/management/health

# Health del admin-service (vía gateway)
curl http://localhost:8080/services/hubadminservice/management/health

# Obtener token partner (flujo M2M externo)
curl -s -X POST http://localhost:8080/oauth2/token \
  -d grant_type=client_credentials \
  -d client_id=unilink-api \
  -d client_secret=unilink-api-secret \
  -d scope=https://api.sintesis.com.bo/caso.penal | jq .

# Crear un caso penal inbound (reemplazar TOKEN con el token obtenido arriba)
curl -s -X POST http://localhost:8080/partner/v1/inbound/CASO_PENAL/v1 \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: test-001" \
  -d '{"cud":"1234567890-1A","id_externo_caso":1,"id_tipo_denuncia":1,
       "id_oficina":1,"id_estado":1,"id_etapa":1}' | jq .
```

---

## 8. Actualizar a una nueva versión

```bash
# 1. En tu máquina local: build y push
./gradlew :hub-gateway:jib -PdockerRegistry=ghcr.io/<GH_USERNAME>

# 2. En el servidor: pull y reiniciar solo el servicio afectado
cd /opt/hub-interop/deploy/production
docker compose pull gateway
docker compose up -d --no-deps gateway
```

---

## Referencia rápida de puertos

| Servicio | Puerto host | Desde el browser |
|---|---|---|
| Gateway (entrada principal) | 8080 | ✓ (vía nginx :80) |
| Admin frontend | 8081 | ✓ |
| Public frontend | 8082 | ✓ |
| Admin-service (directo) | 8083 | solo debug |
| Base-service (directo) | 8084 | solo debug |
| Keycloak | 8180 | ✓ (solo gestión) |
| Consul | 8500 | solo debug |
| Vault | 8200 | solo debug |
