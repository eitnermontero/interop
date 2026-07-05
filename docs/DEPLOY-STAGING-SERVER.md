# Propuesta: despliegue en servidor de staging (sin código fuente, sin registry)

Cómo publicar el hub en un servidor remoto partiendo de cero, con dos
restricciones: **el servidor no tiene el código fuente** y **no hay Docker
registry** alcanzable.

## Principio

Al servidor solo viajan **dos artefactos**:

1. **Imágenes Docker** empaquetadas como tar (`docker save` → `scp` → `docker load`).
2. **La carpeta `deploy/`** (compose, scripts, seeds CSV, certs) — es
   infraestructura-como-código, no código fuente de la aplicación.

```
 MÁQUINA DE BUILD (tiene el repo)              SERVIDOR STAGING (sin fuente)
 ┌─────────────────────────────┐   scp   ┌──────────────────────────────────┐
 │ ./gradlew jibDockerBuild    │ ──────► │ docker load -i hub-images.tar.gz  │
 │ export-images.sh            │         │ /opt/hub/deploy/ (compose+scripts)│
 │ tar de deploy/              │ ──────► │ tools + seeds + stack staging     │
 └─────────────────────────────┘         └──────────────────────────────────┘
```

> Cuando exista registry (el Nexus de Síntesis soporta Docker registry), el
> transporte cambia a `./gradlew jib` + `docker compose pull` y esta guía se
> simplifica. `docker save/load` es el mecanismo interino, perfectamente válido.

## Fase A — En la máquina de build (con el repo)

```bash
# 1. Construir imágenes en el daemon local (no requiere registry)
./gradlew :hub-gateway:jibDockerBuild :hub-ms-base:jibDockerBuild

# 2. Empaquetar imágenes (--with-tools si el servidor no tiene salida a internet
#    para hacer pull de keycloak/consul/vault/redis/postgres)
deploy/scripts/export-images.sh --with-tools
#    → deploy/dist/hub-images-<fecha>.tar.gz

# 3. (La PKI ahora es de Vault y se inicializa EN el servidor, fase B —
#     requiere openssl y keytool ahí; si el servidor no tiene JDK, generar los
#     .p12 en esta máquina apuntando VAULT_ADDR al Vault del servidor)

# 4. Empaquetar el deploy (sin fuente, sin dist para no duplicar el tar)
tar --exclude='deploy/dist' -czf hub-deploy-<fecha>.tar.gz deploy/

# 5. Enviar
scp deploy/dist/hub-images-<fecha>.tar.gz hub-deploy-<fecha>.tar.gz usuario@servidor:/opt/hub/
```

## Fase B — En el servidor (desde cero)

Prerequisitos del servidor: Docker Engine + docker compose plugin, usuario con
grupo `docker`. Nada más (ni Java, ni Gradle, ni el repo).

```bash
cd /opt/hub
tar -xzf hub-deploy-<fecha>.tar.gz
docker load -i hub-images-<fecha>.tar.gz

# 1. Red compartida + tools
docker network create hub-shared
deploy/scripts/tools.sh --up            # Keycloak/Consul/Vault/Redis

# 2. Seeds (idempotentes; usan docker exec, no requieren nada instalado)
deploy/scripts/keycloak-sync-admin.sh
deploy/scripts/keycloak-sync-partner.sh   # crea los clients de partners del CSV
TOOLS_HOST=127.0.0.1 DB_NAME=hub_auth deploy/scripts/vault-seed.sh --ns hub-auth --kc-realm hub-admin
TOOLS_HOST=127.0.0.1 DB_NAME=hub_base deploy/scripts/vault-seed.sh --ns hub-base --kc-realm hub-admin

# 2b. PKI de Vault (CA + cert del gateway + certs de partners)
deploy/scripts/vault-pki.sh init
deploy/scripts/vault-pki.sh server <hostname-del-servidor> <ip>
deploy/scripts/vault-pki.sh partner felcn-api

# 3. Directorios de logs (los contenedores corren como uid 1000)
mkdir -p ~/logs/hub-staging/{gateway,base-service} && chown -R 1000:1000 ~/logs/hub-staging

# 4. Publicar la configuración de APIs (plano de control) en Consul KV
curl -X PUT --data-binary @deploy/staging/consul-config/base-service-application.yml \
     http://127.0.0.1:8500/v1/kv/config/base-service/hub-ms-base.yml

# 5. Ajustar deploy/staging/.env si cambia el host (puertos expuestos, passwords
#    de DB reales, HUB_MTLS_TEST_MODE) y levantar
docker compose -f deploy/staging/docker-compose.yml --env-file deploy/staging/.env up -d

# 6. Verificar
docker ps --filter name=hub-staging          # todo (healthy)
# E2E: ver docs/STAGING.md §6 (token partner + POST/PATCH + auditoría)
```

## Actualizaciones (nueva versión de una imagen)

```bash
# build machine:
./gradlew :hub-ms-base:jibDockerBuild && deploy/scripts/export-images.sh
scp deploy/dist/hub-images-<fecha>.tar.gz usuario@servidor:/opt/hub/
# servidor:
docker load -i hub-images-<fecha>.tar.gz
docker compose -f deploy/staging/docker-compose.yml --env-file deploy/staging/.env up -d base-service
```

Cambios de **configuración** (nueva API, cambio de destino) NO requieren nada de
esto: editar la KV de Consul + `docker restart` (ver `docs/AGREGAR-API.md`).

## Seguridad del paquete

- `deploy/scripts/keycloak-seed/partner/clients.csv` lleva los **secrets** de
  los partners y `deploy/certs/` las claves privadas: el tar debe viajar por
  canal seguro y borrarse tras el despliegue; en el servidor, permisos 600.
- Cambiar TODOS los defaults `postgres/postgres`, `changeit`, token `root` de
  Vault (dev mode) antes de exponer el servidor fuera de la red interna. Para
  un staging expuesto: Vault en modo real (no dev), passwords generados, y
  mTLS activo (ONBOARDING-PARTNER.md §6).

## Checklist de despliegue

- [ ] Imágenes cargadas (`docker images | grep hub`)
- [ ] Tools healthy + realms sincronizados + Vault seedeado
- [ ] KV de Consul publicada (config/base-service/hub-ms-base.yml)
- [ ] Stack staging healthy; Liquibase creó el esquema (DB desde cero)
- [ ] Token de partner OK vía `POST /oauth2/token`
- [ ] POST/PATCH de caso → 201/200 y fila en `hub_audit_log`
- [ ] Swagger unificado accesible (`/v3/api-docs/base-service`)
