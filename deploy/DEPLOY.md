# Guía de despliegue en servidor Linux

Servidor objetivo: Linux con PostgreSQL, nginx y Docker ya instalados.
Registro de imágenes: GitHub Container Registry (`ghcr.io`).

---

## Stack a desplegar

| Componente | Docker | Puerto expuesto |
|---|---|---|
| `mdqr-gateway` | sí | 8080 |
| `mdqr-ms-base` (decrypt-service) | sí | 8084 (interno 8081) |
| `mdqr-ms-auth` (admin-service) | sí | 8083 |
| `mdqr-admin-fe` | sí | 8081 |
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
./gradlew :mdqr-gateway:jib  -PdockerRegistry=ghcr.io/<GH_USERNAME>
./gradlew :mdqr-ms-base:jib  -PdockerRegistry=ghcr.io/<GH_USERNAME>
./gradlew :mdqr-ms-auth:jib  -PdockerRegistry=ghcr.io/<GH_USERNAME>
```

Esto empuja las imágenes:
- `ghcr.io/<GH_USERNAME>/mdqr-gateway:1.0.x`
- `ghcr.io/<GH_USERNAME>/mdqr-ms-base:1.0.x`
- `ghcr.io/<GH_USERNAME>/mdqr-ms-auth:1.0.x`

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
CREATE DATABASE mdqr_auth;
CREATE USER mdqr WITH PASSWORD '<STRONG_PASSWORD>';
GRANT ALL PRIVILEGES ON DATABASE hub_interop TO mdqr;
GRANT ALL PRIVILEGES ON DATABASE mdqr_auth TO mdqr;
-- PostgreSQL 15+ requiere también:
\c hub_interop
GRANT ALL ON SCHEMA public TO mdqr;
\c mdqr_auth
CREATE SCHEMA admin;
GRANT ALL ON SCHEMA admin TO mdqr;
SQL
```

### 2.3 Crear la red Docker compartida

```bash
docker network create --driver bridge \
  --opt com.docker.network.driver.mtu=1500 \
  mdqr-shared
```

### 2.4 Crear directorios de logs

```bash
mkdir -p /var/log/mdqr/{gateway,decrypt-service,admin-service}
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
MDQR_TOOLS_BIND_IP=0.0.0.0
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

# Para mdqr-ms-auth (namespace mdqr-auth)
TOOLS_HOST=127.0.0.1 DB_NAME=mdqr_auth \
  deploy/scripts/vault-seed.sh \
    --ns mdqr-auth \
    --kc-realm mdqr-admin

# Para mdqr-ms-base y gateway (namespace mdqr-decode)
TOOLS_HOST=127.0.0.1 DB_NAME=hub_interop \
  deploy/scripts/vault-seed.sh \
    --ns mdqr-decode \
    --kc-realm mdqr-admin
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
MDQR_GATEWAY_IMAGE=ghcr.io/<GH_USERNAME>/mdqr-gateway
MDQR_DECRYPT_IMAGE=ghcr.io/<GH_USERNAME>/mdqr-ms-base
MDQR_ADMIN_IMAGE=ghcr.io/<GH_USERNAME>/mdqr-ms-auth

# Tags de las imágenes
MDQR_GATEWAY_VERSION=1.0.0
MDQR_DECRYPT_VERSION=1.0.0
MDQR_ADMIN_VERSION=1.0.0

# Vault (token por defecto en dev mode = root)
MDQR_VAULT_TOKEN=root

# Keycloak — usar IP real del servidor, NO localhost ni host.docker.internal
MDQR_KEYCLOAK_URL=http://<IP_SERVIDOR>:8180

# CORS — URL del frontend para el browser
MDQR_GATEWAY_CORS_ALLOWED_ORIGINS=http://<IP_SERVIDOR>:8081

# Logs
LOG_DIR=/var/log/mdqr
```

Las URLs de DB y perfiles ya están correctos en el `.env.example`:
```dotenv
MDQR_DECRYPT_DB_URL=jdbc:postgresql://host.docker.internal:5432/hub_interop
MDQR_ADMIN_DB_URL=jdbc:postgresql://host.docker.internal:5432/mdqr_auth
MDQR_GATEWAY_PROFILE=prod
MDQR_DECRYPT_PROFILE=prod
MDQR_ADMIN_PROFILE=prod
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
> `MDQR_KEYCLOAK_URL` con `https://`.

---

## 7. Verificación

```bash
# Health del gateway
curl http://localhost:8080/management/health

# Health del decrypt-service (vía gateway)
curl http://localhost:8080/services/mdqrbaseservice/management/health

# Health del admin-service (vía gateway)
curl http://localhost:8080/services/mdqradminservice/management/health

# Obtener token partner (flujo M2M externo)
curl -s -X POST http://localhost:8080/oauth2/token \
  -d grant_type=client_credentials \
  -d client_id=unilink-api \
  -d client_secret=unilink-api-secret \
  -d scope=https://api.sintesis.com.bo/qr.decode | jq .

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
./gradlew :mdqr-gateway:jib -PdockerRegistry=ghcr.io/<GH_USERNAME>

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
| Decrypt-service (directo) | 8084 | solo debug |
| Keycloak | 8180 | ✓ (solo gestión) |
| Consul | 8500 | solo debug |
| Vault | 8200 | solo debug |
