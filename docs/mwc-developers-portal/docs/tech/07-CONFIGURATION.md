# 07 - Configuración de la Aplicación

## Visión General

El sistema HUB usa dos mecanismos de configuración según el perfil de Spring activo:

| Perfil | Vault | Consul config | Consul discovery | Uso |
|---|---|---|---|---|
| `local` | Desactivado | Desactivado | Activado | Desarrollo local |
| default (sin perfil) | Activado | Activado | Activado | Staging / Producción |

En el perfil `local`, los secretos se leen directamente desde variables de entorno con valores por defecto. En el perfil default, los secretos se obtienen de Vault y la configuración no-sensible de Consul KV.

---

## Perfil `local`

### Variables de entorno soportadas

Todos los módulos en perfil `local` respetan las siguientes variables de entorno con sus valores por defecto:

| Variable | Default | Descripción |
|---|---|---|
| `DB_HOST` | `127.0.0.1` | Host de PostgreSQL |
| `DB_PORT` | `5432` | Puerto de PostgreSQL |
| `DB_USER` | `postgres` | Usuario de la base de datos |
| `DB_PASSWORD` | `postgres` | Password de la base de datos |
| `REDIS_HOST` | `127.0.0.1` | Host de Redis |
| `REDIS_PORT` | `6379` | Puerto de Redis |
| `REDIS_PASSWORD` | (vacío) | Password de Redis |
| `KEYCLOAK_HOST` | `127.0.0.1` | Host de Keycloak |
| `KEYCLOAK_PORT` | `8180` | Puerto de Keycloak |

### Patrón de configuración local (aplica a los tres módulos)

```yaml
spring:
  config:
    import: ""
  cloud:
    vault:
      enabled: false
    consul:
      enabled: true
      config:
        enabled: false
      discovery:
        enabled: true
  datasource:
    url: jdbc:postgresql://${DB_HOST:127.0.0.1}:${DB_PORT:5432}/<nombre_db>
    username: ${DB_USER:postgres}
    password: ${DB_PASSWORD:postgres}
  data:
    redis:
      host: ${REDIS_HOST:127.0.0.1}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://${KEYCLOAK_HOST:127.0.0.1}:${KEYCLOAK_PORT:8180}/realms/<realm>
```

Los módulos `hub-ms-auth` y `hub-ms-base` también deshabilitan el import-check de Spring Cloud:

```yaml
spring:
  cloud:
    config:
      import-check:
        enabled: false
```

---

## hub-gateway (puerto 8080)

### application-local.yml — propiedades clave

```yaml
spring:
  config:
    import: ""
  cloud:
    vault:
      enabled: false
    consul:
      enabled: true
      config:
        enabled: false
      discovery:
        enabled: true
  data:
    redis:
      host: ${REDIS_HOST:127.0.0.1}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
```

### Cadenas de seguridad (Security Chains)

El gateway define dos cadenas de seguridad con prioridad por `Order`:

| Order | Paths cubiertos | Realm Keycloak | Uso |
|---|---|---|---|
| 1 (partner) | `/partner/**`, `/oauth2/token` | `hub-partner` | APIs externas M2M |
| 2 (admin) | `/services/**`, Swagger, OIDC | `hub-admin` | APIs internas |

### Resolución de servicios via Consul

El gateway resuelve rutas con prefijo `lb://` a través del discovery de Consul:

| URI del gateway | Nombre en Consul | Módulo |
|---|---|---|
| `lb://hubadminservice` | `hubadminservice` | `hub-ms-auth` |
| `lb://hubbaseservice` | `hubbaseservice` | `hub-ms-base` |

Las rutas con `StripPrefix=2` eliminan el prefijo `/services/{nombre-servicio}` antes de reenviar al microservicio destino.

---

## hub-ms-auth (puerto 8083)

### Base de datos

- Nombre: `hub_auth`
- Schema: `admin`
- Vault NS: `hub-auth`

### application-local.yml — propiedades clave

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:127.0.0.1}:${DB_PORT:5432}/hub_auth
    username: ${DB_USER:postgres}
    password: ${DB_PASSWORD:postgres}
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://${KEYCLOAK_HOST:127.0.0.1}:${KEYCLOAK_PORT:8180}/realms/hub-admin

application:
  keycloak:
    realm: hub-admin
    client-id: hubadminservice
  audit:
    keycloak-poll:
      enabled: false
```

El polling de eventos de Keycloak (`keycloak-poll`) se desactiva en local para evitar ruido durante desarrollo.

### Registro en Consul

El módulo se registra en Consul con el nombre `hubadminservice`. Este nombre debe coincidir exactamente con la URI configurada en el gateway (`lb://hubadminservice`).

---

## hub-ms-base (puerto 8081)

### Base de datos

- Nombre: `hub_base`
- Schema: `public`
- Vault NS: `hub-base`

### application-local.yml — propiedades clave

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:127.0.0.1}:${DB_PORT:5432}/hub_base
    username: ${DB_USER:postgres}
    password: ${DB_PASSWORD:postgres}
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://${KEYCLOAK_HOST:127.0.0.1}:${KEYCLOAK_PORT:8180}/realms/hub-admin

application:
  tuxedo:
    api-url: http://${TUXEDO_HOST:127.0.0.1}:${TUXEDO_PORT:5050}
  certificate:
    sync:
      enabled: false
  qr:
    decryption:
      cache-enabled: true

management:
  endpoints:
    web:
      base-path: /management
```

La propiedad `management.endpoints.web.base-path: /management` es requerida en ms-base. Sin ella, los health checks de Consul no funcionan correctamente porque el módulo no tiene el path `/actuator` por defecto.

La sincronización de certificados (`certificate.sync.enabled`) se desactiva en local para no requerir conectividad con sistemas externos.

### Registro en Consul

El módulo se registra en Consul con el nombre `hubbaseservice`.

---

## Perfil default (Vault + Consul)

En entornos distintos de local (staging, producción), la configuración se obtiene de:

1. **Vault** — credenciales sensibles: passwords de DB, Redis, client secrets de Keycloak
2. **Consul KV** — configuración no-sensible: URLs, timeouts, flags de features

### Configuración de Vault en application.yml

```yaml
spring:
  config:
    import: "vault://"
  cloud:
    vault:
      enabled: true
      host: ${VAULT_HOST:localhost}
      port: ${VAULT_PORT:8200}
      scheme: ${VAULT_SCHEME:http}
      authentication: TOKEN
      token: ${VAULT_TOKEN}
      kv:
        enabled: true
        backend: secret
    consul:
      enabled: true
      config:
        enabled: true
      discovery:
        enabled: true
```

Ver `09-VAULT.md` para la estructura completa de secretos y los comandos de seed.

---

## Consistencia del Issuer JWT

El issuer URI configurado en cada servicio debe coincidir exactamente con el claim `iss` del token JWT. En local, todos los tokens deben obtenerse de `http://127.0.0.1:8180` (no `localhost`, no el nombre del contenedor Docker). Si el issuer no coincide, Spring Security rechaza el token con error 401.

Ejemplo de obtención de token en local:

```bash
# Token para realm hub-admin (APIs internas via ms-auth)
curl -s http://127.0.0.1:8180/realms/hub-admin/protocol/openid-connect/token \
  -d "grant_type=client_credentials" \
  -d "client_id=hubadminservice" \
  -d "client_secret=hubadminservice-secret" | jq -r '.access_token'

# Token para realm hub-partner (APIs externas via gateway)
curl -s -X POST http://127.0.0.1:8080/oauth2/token \
  -d "grant_type=client_credentials" \
  -d "client_id=unilink-api" \
  -d "client_secret=unilink-api-secret" \
  -d "scope=https://api.sintesis.com.bo/qr.decode" | jq -r '.access_token'
```

---

## Archivos de configuración relevantes

| Archivo | Módulo | Descripción |
|---|---|---|
| `hub-gateway/src/main/resources/application.yml` | gateway | Configuración base del gateway |
| `hub-gateway/src/main/resources/application-local.yml` | gateway | Perfil local (Redis + Keycloak hardcoded) |
| `hub-ms-auth/src/main/resources/application.yml` | ms-auth | Configuración base de ms-auth |
| `hub-ms-auth/src/main/resources/application-local.yml` | ms-auth | Perfil local de ms-auth |
| `hub-ms-base/src/main/resources/application.yml` | ms-base | Configuración base de ms-base |
| `hub-ms-base/src/main/resources/application-local.yml` | ms-base | Perfil local de ms-base |
