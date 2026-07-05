# 09 - HashiCorp Vault: Gestión de Secretos

## Visión General

Vault centraliza todos los secretos del sistema HUB: credenciales de bases de datos, Redis y Keycloak. Se accede a él desde cada microservicio via Spring Cloud Vault al momento de arranque.

En el perfil `local` Vault está desactivado y los valores se toman de variables de entorno con defaults. En staging y producción, Vault es la única fuente de credenciales.

## Namespaces

Cada módulo usa un namespace (path) independiente dentro del engine KV de Vault:

| Namespace | Módulo | Base de datos |
|---|---|---|
| `hub-auth` | `hub-ms-auth` | `hub_auth` |
| `hub-base` | `hub-ms-base` | `hub_base` |

## Estructura de secretos

Dentro de cada namespace se almacenan tres paths:

```
secret/<namespace>/
├── database    → credenciales de PostgreSQL
├── keycloak    → configuración del realm y client
└── redis       → credenciales de Redis
```

### Path `database`

| Campo | Descripción |
|---|---|
| `url` | URL JDBC completa (ej: `jdbc:postgresql://localhost:5432/hub_auth`) |
| `username` | Usuario de la base de datos |
| `password` | Password del usuario |
| `driver` | Clase del driver (ej: `org.postgresql.Driver`) |

### Path `keycloak`

| Campo | Descripción |
|---|---|
| `auth-server-url` | URL base de Keycloak (ej: `http://127.0.0.1:8180`) |
| `realm` | Nombre del realm |
| `client-id` | Client ID del servicio |
| `client-secret` | Client secret del servicio |

### Path `redis`

| Campo | Descripción |
|---|---|
| `host` | Host de Redis |
| `port` | Puerto de Redis |
| `password` | Password de Redis (puede ser vacío) |

---

## Script `vault-seed.sh`

El script `deploy/scripts/vault-seed.sh` siembra todos los secretos de un namespace en Vault. Debe ejecutarse una vez durante el setup inicial o después de limpiar el stack Docker.

### Parámetros

| Parámetro | Obligatorio | Descripción |
|---|---|---|
| `--ns <namespace>` | Si | Namespace de Vault: `hub-auth` o `hub-base` |
| `--kc-realm <realm>` | Si | Realm de Keycloak. Siempre usar `hub-admin` |

### Variables de entorno del script

| Variable | Default | Descripción |
|---|---|---|
| `TOOLS_HOST` | `host.docker.internal` | Host donde corren los tools (DB, Redis, Keycloak) |
| `DB_NAME` | — | Nombre de la base de datos a sembrar |
| `DB_HOST` | valor de `TOOLS_HOST` | Host de PostgreSQL (override opcional) |

### Advertencia critica: parametro `--kc-realm`

Si se omite `--kc-realm`, el script usa el nombre del namespace como realm por defecto. Para el namespace `hub-base`, esto resulta en que Vault almacena el realm `hub-base` en lugar de `hub-admin`. Los servicios fallan al arrancar con un error de issuer JWT porque el realm no existe en Keycloak.

**Siempre especificar `--kc-realm hub-admin` explicitamente.**

---

## Comandos de seed

### ms-auth (namespace `hub-auth`)

```bash
DB_NAME=hub_auth DB_HOST=localhost \
  deploy/scripts/vault-seed.sh --ns hub-auth --kc-realm hub-admin
```

### ms-base (namespace `hub-base`)

```bash
DB_NAME=hub_base DB_HOST=localhost \
  deploy/scripts/vault-seed.sh --ns hub-base --kc-realm hub-admin
```

### Nota sobre `TOOLS_HOST` en WSL2

En entornos WSL2, `host.docker.internal` no es alcanzable desde las aplicaciones Spring que corren en la terminal (fuera de Docker). Para Redis y otros servicios que corren en Docker, usar `TOOLS_HOST=127.0.0.1`:

```bash
TOOLS_HOST=127.0.0.1 DB_NAME=hub_auth \
  deploy/scripts/vault-seed.sh --ns hub-auth --kc-realm hub-admin
```

---

## Verificacion post-seed

Verificar que los secretos quedaron correctamente sembrados:

```bash
# Verificar keycloak de ms-auth
docker exec -e VAULT_TOKEN=root -e VAULT_ADDR=http://127.0.0.1:8200 hub-vault \
  vault kv get secret/hub-auth/keycloak

# Verificar database de ms-auth
docker exec -e VAULT_TOKEN=root -e VAULT_ADDR=http://127.0.0.1:8200 hub-vault \
  vault kv get secret/hub-auth/database

# Verificar redis de ms-auth
docker exec -e VAULT_TOKEN=root -e VAULT_ADDR=http://127.0.0.1:8200 hub-vault \
  vault kv get secret/hub-auth/redis

# Verificar keycloak de ms-base
docker exec -e VAULT_TOKEN=root -e VAULT_ADDR=http://127.0.0.1:8200 hub-vault \
  vault kv get secret/hub-base/keycloak

# Verificar database de ms-base
docker exec -e VAULT_TOKEN=root -e VAULT_ADDR=http://127.0.0.1:8200 hub-vault \
  vault kv get secret/hub-base/database
```

Confirmar que el campo `realm` en el path `keycloak` de ambos namespaces es `hub-admin`. Si aparece el nombre del namespace como realm, el seed se ejecuto sin `--kc-realm` y debe corregirse.

---

## Integracion con Spring Cloud Vault

### Dependencia Gradle

```gradle
implementation 'org.springframework.cloud:spring-cloud-starter-vault-config'
```

### Configuracion en application.yml (perfil default)

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
        default-context: <namespace>
```

Reemplazar `<namespace>` con `hub-auth` para ms-auth o `hub-base` para ms-base.

### Mapeo de secretos a properties de Spring

Spring Cloud Vault mapea los campos del path KV a properties con el formato `<path>.<campo>`. Ejemplos para el namespace `hub-auth`:

| Path en Vault | Campo | Property en Spring |
|---|---|---|
| `secret/hub-auth/database` | `username` | `database.username` |
| `secret/hub-auth/database` | `password` | `database.password` |
| `secret/hub-auth/database` | `url` | `database.url` |
| `secret/hub-auth/redis` | `host` | `redis.host` |
| `secret/hub-auth/redis` | `password` | `redis.password` |
| `secret/hub-auth/keycloak` | `realm` | `keycloak.realm` |
| `secret/hub-auth/keycloak` | `client-secret` | `keycloak.client-secret` |

---

## Variables de entorno requeridas (perfil default)

| Variable | Default | Descripcion |
|---|---|---|
| `VAULT_HOST` | `localhost` | Host de Vault |
| `VAULT_PORT` | `8200` | Puerto de Vault |
| `VAULT_SCHEME` | `http` | Protocolo. Usar `https` en produccion |
| `VAULT_TOKEN` | — | Token de acceso a Vault |

En entornos productivos se recomienda usar autenticacion AppRole o Kubernetes auth en lugar de token estatico.

---

## Consideraciones

- **Vault en local usa token `root`**: el stack Docker de desarrollo inicia Vault en modo dev con token `root`. No usar en ambientes expuestos.
- **Vault sellado en produccion**: en produccion Vault arranca sellado. Configurar auto-unseal con KMS.
- **Audit log de Vault**: habilitar el audit log de Vault en produccion para trazar todo acceso a secretos.
- **Rotacion de credenciales**: al rotar una credencial en Vault, el servicio debe reiniciarse para que Spring Cloud Vault la vuelva a leer al arranque (no hay recarga en caliente por defecto).
