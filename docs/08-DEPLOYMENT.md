# 08 - Despliegue y Estructura de Módulos

> ⚠️ **Documento parcialmente desactualizado** (contiene contenido legacy pre-ADR-0004/rename 2026-07-03).
> Fuente de verdad actual: `CLAUDE.md` y `docs/adr/` (ADR-0005/0006/0007).

## Estructura del Proyecto (Gradle Multi-Módulo)

```
unilink-qr-decrypt/
├── settings.gradle          ← incluye: hub-gateway, hub-ms-auth, hub-ms-base
├── build.gradle             ← configuración común
├── gradlew                  ← wrapper
├── hub-gateway/
│   ├── build.gradle
│   └── src/
├── hub-ms-auth/
│   ├── build.gradle
│   └── src/
└── hub-ms-base/
    ├── build.gradle
    └── src/
```

### Módulos

| Módulo | Puerto | Responsabilidad |
|---|---|---|
| `hub-gateway` | 8080 | Spring Cloud Gateway: enrutamiento, autenticación JWT, rate limiting, IP whitelist |
| `hub-ms-auth` | 8083 | Admin RBAC: gestión de usuarios/roles vía Keycloak, permisos de menú, auditoría |
| `hub-ms-base` | 8081 | Desencriptación QR, gestión de certificados digitales |

Stack: Spring Boot 4, Spring Cloud 2023, Java 21.

---

## Compilación

```bash
# Compilar todos los módulos
./gradlew build

# Compilar un módulo específico
./gradlew :hub-ms-auth:build
./gradlew :hub-ms-base:build
./gradlew :hub-gateway:build

# Ejecutar tests
./gradlew test
./gradlew :hub-ms-base:test

# Generar JAR ejecutable
./gradlew :hub-ms-auth:bootJar
./gradlew :hub-ms-base:bootJar
./gradlew :hub-gateway:bootJar
```

---

## Ejecución en Desarrollo (perfil local)

El perfil `local` desactiva Vault y Consul config, pero mantiene Consul discovery activo para que el gateway resuelva las rutas `lb://`.

```bash
# Levantar el stack de herramientas primero
deploy/scripts/tools.sh --up

# Luego arrancar cada módulo en terminales separadas
./gradlew :hub-ms-auth:bootRun --args='--spring.profiles.active=local'
./gradlew :hub-ms-base:bootRun --args='--spring.profiles.active=local'
./gradlew :hub-gateway:bootRun --args='--spring.profiles.active=local'
```

Variables de entorno disponibles para sobreescribir los defaults del perfil local:

```bash
KEYCLOAK_HOST=127.0.0.1   KEYCLOAK_PORT=8180
DB_HOST=127.0.0.1         DB_PORT=5432
REDIS_HOST=127.0.0.1      REDIS_PORT=6379
```

---

## Stack Docker de Herramientas

Los servicios de infraestructura se gestionan con `deploy/scripts/tools.sh`. Los módulos Spring Boot se ejecutan directamente con Gradle — no se dockerizan en desarrollo local.

### Comandos

```bash
deploy/scripts/tools.sh --up                     # levantar todos los servicios
deploy/scripts/tools.sh --down -v                # bajar y borrar volúmenes
deploy/scripts/tools.sh --info                   # ver estado de cada servicio
deploy/scripts/tools.sh --logs <servicio>        # logs en tiempo real
deploy/scripts/tools.sh --restart <servicio>     # reiniciar un servicio específico
```

### Servicios (deploy/tools/)

| Servicio | Imagen | Puerto |
|---|---|---|
| `hub-keycloak` | Keycloak 22+ | 8180 |
| `hub-consul` | Consul 1.17+ | 8500 |
| `hub-vault` | Vault 1.14+ | 8200 |
| `hub-redis` | Redis 7+ | 6379 |

### Red Docker

Todos los contenedores comparten la red `hub-shared`. Crear antes del primer `--up`:

```bash
docker network create --driver bridge --opt com.docker.network.driver.mtu=1500 hub-shared
```

### Variables de entorno del stack

```bash
cp deploy/tools/.env.example deploy/tools/.env
# Editar deploy/tools/.env con los valores necesarios
```

---

## Inicialización (primer setup o tras limpiar volúmenes)

Ejecutar en orden después de que el stack Docker esté levantado:

```bash
# 1. Sembrar Vault con secretos
#    Namespace hub-auth (ms-auth)
deploy/scripts/vault-seed.sh --ns hub-auth --kc-realm hub-admin

#    Namespace hub-base (ms-base)
#    IMPORTANTE: pasar --kc-realm hub-admin explícitamente (el default del script no aplica aquí)
#    IMPORTANTE: usar TOOLS_HOST=127.0.0.1 — host.docker.internal no es alcanzable desde WSL2
TOOLS_HOST=127.0.0.1 deploy/scripts/vault-seed.sh --ns hub-base --kc-realm hub-admin

# 2. Crear realms y clientes en Keycloak
deploy/scripts/keycloak-sync-admin.sh      # realm hub-admin (APIs internas)
deploy/scripts/keycloak-sync-partner.sh    # realm hub-partner (APIs externas M2M)
```

### Realms Keycloak

| Realm | Uso | Client |
|---|---|---|
| `hub-admin` | APIs internas, gateway chain Order=2 | `hubadminservice` |
| `hub-partner` | APIs externas M2M, gateway chain Order=1 | `unilink-api` |

---

## Bases de Datos

| Módulo | Base de datos | Schema | Vault namespace |
|---|---|---|---|
| `hub-ms-auth` | `hub_auth` | `admin` | `hub-auth` |
| `hub-ms-base` | `hub_base` | `public` | `hub-base` |

### Nota sobre Liquibase

Spring Boot 4 con Liquibase 5 no incluye `LiquibaseAutoConfiguration`. Cada módulo declara su propio bean `SpringLiquibase` en `LiquibaseConfiguration.java`. No usar el starter de Liquibase esperando autoconfiguración automática.

---

## Notas de Implementación

- `commons-pool2` es requerido para el connection pool de Lettuce (Redis). Sin esta dependencia, el pool de conexiones no funciona.
- `ConsulConfiguration` debe declararse con `@ConditionalOnProperty` para no fallar cuando Consul config está desactivado en el perfil local.
- `IpWhitelistFilter` y `RateLimitFilter` en el gateway incluyen `.onErrorResume` para no causar respuestas 500 si Redis no está disponible.
- El issuer JWT debe ser consistente entre servicios: usar `127.0.0.1:8180` tanto en la configuración de resource server como al obtener los tokens.
- `ms-base` requiere `management.endpoints.web.base-path: /management` — no usa el path por defecto `/actuator`.

---

## Obtener Tokens JWT (local)

```bash
# Token admin (realm hub-admin, APIs internas)
ADMIN_TOKEN=$(curl -s http://127.0.0.1:8180/realms/hub-admin/protocol/openid-connect/token \
  -d "grant_type=client_credentials" \
  -d "client_id=hubadminservice" \
  -d "client_secret=hubadminservice-secret" | jq -r '.access_token')

# Token partner (realm hub-partner, vía proxy del gateway)
PARTNER_TOKEN=$(curl -s -X POST http://127.0.0.1:8080/oauth2/token \
  -d "grant_type=client_credentials" \
  -d "client_id=unilink-api" \
  -d "client_secret=unilink-api-secret" \
  -d "scope=https://api.sintesis.com.bo/qr.decode" | jq -r '.access_token')
```

---

## Cadenas de Seguridad del Gateway

| Chain | Order | Rutas | Realm JWT |
|---|---|---|---|
| partner | 1 | `/partner/**`, `/oauth2/token` | `hub-partner` |
| admin | 2 | `/services/**`, Swagger, OIDC | `hub-admin` |

El discovery locator resuelve `lb://hubadminservice` a `/services/hubadminservice/**` con `StripPrefix=2`.

---

## Monitoreo

### Actuator

| Módulo | Base path | Ejemplo |
|---|---|---|
| `hub-gateway` | `/actuator` | `/actuator/health` |
| `hub-ms-auth` | `/actuator` | `/actuator/health` |
| `hub-ms-base` | `/management` | `/management/health` |

### Endpoints expuestos

| Endpoint | Descripción |
|---|---|
| `{base}/health` | Estado general del servicio |
| `{base}/metrics` | Métricas JVM y aplicación |
| `{base}/info` | Información del build |

---

## Checklist de Primer Setup

- [ ] Red Docker `hub-shared` creada
- [ ] Archivo `deploy/tools/.env` configurado desde `.env.example`
- [ ] Stack Docker levantado (`tools.sh --up`)
- [ ] Vault sembrado para namespace `hub-auth`
- [ ] Vault sembrado para namespace `hub-base` (con `TOOLS_HOST=127.0.0.1` y `--kc-realm hub-admin`)
- [ ] Keycloak realm `hub-admin` creado (`keycloak-sync-admin.sh`)
- [ ] Keycloak realm `hub-partner` creado (`keycloak-sync-partner.sh`)
- [ ] `hub-ms-auth` arranca correctamente (puerto 8083)
- [ ] `hub-ms-base` arranca correctamente (puerto 8081)
- [ ] `hub-gateway` arranca correctamente (puerto 8080)
- [ ] Token admin obtenible desde `127.0.0.1:8180/realms/hub-admin`
- [ ] Token partner obtenible vía proxy en `127.0.0.1:8080/oauth2/token`
- [ ] Rutas del gateway resuelven via Consul discovery
