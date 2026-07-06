# HUB de Interoperabilidad — Guía de instalación y operación

> **EMPIEZA AQUÍ.** Esta guía es autocontenida: todo lo que necesitas para
> instalar el hub en un servidor y operarlo está en este paquete. No se
> requiere el código fuente, ni git, ni Java.

---

## 1. INSTALAR (3 comandos)

**Necesitas**: los 2 archivos (`hub-deploy-*.tar.gz` y `hub-images-*.tar.gz`)
subidos al servidor, y Docker instalado.

```bash
# 0. Prerequisitos — solo si el servidor no los tiene
curl -fsSL https://get.docker.com | sh && systemctl enable --now docker
apt-get install -y python3 openssl

# 1. Desempaquetar (deja los dos tar en el mismo directorio)
mkdir -p /srv/projects/hub && cd /srv/projects/hub
tar -xzf ../hub-deploy-*.tar.gz

# 2. Instalar TODO de cero (carga las imágenes solo, detecta la IP solo)
deploy/scripts/bootstrap.sh
```

Eso es todo. El script muestra 9 pasos con `[✓]` y termina con **"HUB
OPERATIVO"** y las URLs. Hace: red docker → Keycloak/Consul/Vault/Redis →
realms y partners → secretos → PKI (CAs y certificados) → configuración de
APIs → base de datos y servicios del hub → prueba end-to-end real.

Si un paso falla, el mensaje `[✗]` dice exactamente qué. Volver a correr
`bootstrap.sh` es seguro (es idempotente). **Reset total**: sección 5.

Al terminar, borra los tar del servidor (contienen credenciales):
`rm ../hub-deploy-*.tar.gz ../hub-images-*.tar.gz`

---

## 2. CONFIGURAR UNA API (el trabajo del día a día)

Las APIs se declaran en **un archivo YAML** — sin programar, sin recompilar:

**Paso 1** — Edita `deploy/staging/consul-config/base-service-application.yml`
y agrega tu API siguiendo este modelo:

```yaml
hub:
  connectors:
    backend-mi-institucion:              # el sistema destino (una vez)
      base-url: https://backend.institucion.gob.bo
      timeout-ms: 8000
  apis:
    mi-api-v1:
      product: MI_PRODUCTO               # define la URL del partner
      version: v1
      method: POST                       # POST o PATCH
      connector: backend-mi-institucion
      target-path: /recurso              # path en el sistema destino
      required-scope: https://api.sintesis.com.bo/caso.penal
      fields:                            # el contrato que el hub valida
        - { name: codigo, type: STRING,  required: true, max-length: 50 }
        - { name: monto,  type: INTEGER, required: true }
```

Tipos disponibles: `STRING` (con `max-length`), `INTEGER`, `BOOLEAN`,
`DATETIME` (con `format: iso8601`), `ARRAY`.

**Paso 2** — Publica con el CLI:

```bash
deploy/scripts/hub-api.sh publish
```

Valida primero (si hay un error, NO publica y te dice qué corregir), publica,
reinicia el servicio y muestra los contratos activos. Comandos de apoyo:

| Comando | Qué hace |
|---|---|
| `hub-api.sh validate` | Prueba el YAML sin publicar |
| `hub-api.sh diff` | Muestra qué cambiaría vs lo publicado |
| `hub-api.sh list` | APIs activas |
| `hub-api.sh status` | Salud de los contenedores |

La API queda disponible en `POST http://<servidor>:8088/partner/v1/inbound/<PRODUCTO>/<version>`
y aparece automáticamente en el Swagger: `http://<servidor>:8088/v3/api-docs/base-service`.

> Límites actuales: solo POST/PATCH (no GET), y el sistema destino no debe
> exigir autenticación propia (API keys: próxima versión).

---

## 3. DAR DE ALTA UN PARTNER

Un partner necesita credenciales OAuth2 + certificado:

```bash
# 1. Credenciales: agregar una línea al CSV y sincronizar
SECRET=$(openssl rand -hex 24)
echo "nuevo-partner,Descripción del partner,https://api.sintesis.com.bo/caso.penal,$SECRET" \
  >> deploy/scripts/keycloak-seed/partner/clients.csv
deploy/scripts/keycloak-sync-partner.sh

# 2. Certificado (PKI de Vault; muestra el serial para revocarlo)
deploy/scripts/vault-pki.sh partner nuevo-partner
```

**Entregar al partner por canal seguro**: el `client_id` + `$SECRET`, el
archivo `deploy/certs/partners/nuevo-partner.p12` (password `changeit`) y
`deploy/certs/vault-ca-chain.crt`.

El partner consume así:

```bash
TOKEN=$(curl -s -X POST http://<servidor>:8088/oauth2/token \
  -d grant_type=client_credentials -d client_id=nuevo-partner \
  -d client_secret=<secret> -d scope=https://api.sintesis.com.bo/caso.penal \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')

curl -X POST http://<servidor>:8088/partner/v1/inbound/CASO_PENAL/v1 \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-Idempotency-Key: <uuid-nuevo-por-operación>" \
  -d '{"cud":"X-1","id_externo_caso":1,"id_tipo_denuncia":3,"id_oficina":12,"id_estado":1,"id_etapa":1}'
```

Revocar un certificado: `deploy/scripts/vault-pki.sh revoke <serial>`.
Cortar acceso inmediato: quitar el scope al client en Keycloak (re-sync del CSV).

---

## 4. VER LA AUDITORÍA

Toda transacción (exitosa o no) queda registrada con hash encadenado:

```bash
PGPASSWORD=postgres psql -U postgres -h 127.0.0.1 -p 5433 -d hub_base -c \
 "SELECT ts, partner_id, product, http_status, correlation_id
  FROM hub_audit_log ORDER BY ts DESC LIMIT 20;"
```

El `correlation_id` es el mismo que recibe el partner en cada respuesta — es
la llave para investigar cualquier incidencia que reporten.

---

## 5. SI ALGO SALE MAL

```bash
deploy/scripts/hub-api.sh status                      # ¿qué está caído?
docker logs hub-staging-base-service --tail 50        # logs del motor
docker logs hub-staging-gateway --tail 50             # logs del gateway
```

| Síntoma | Solución |
|---|---|
| Contenedor en crash-loop por logs | `docker run --rm -v ~/logs/hub-staging:/f alpine chown -R 1000:1000 /f` y `docker restart <contenedor>` |
| Cambié el YAML y no se aplica | `deploy/scripts/hub-api.sh publish` (publica Y reinicia) |
| Token da 401/error | El realm de Keycloak se perdió (contenedor recreado): re-correr `bootstrap.sh` |
| Todo roto / quiero empezar de cero | Reset total (≈3 min): ver abajo |

**Reset total** (borra datos, recrea todo):

```bash
docker compose -f deploy/staging/docker-compose.yml --env-file deploy/staging/.env down -v
deploy/scripts/tools.sh --down -v
docker network rm hub-shared
deploy/scripts/bootstrap.sh
```

---

## 6. URLs y puertos

| Qué | Dónde |
|---|---|
| Gateway (lo único expuesto a la red) | `http://<servidor>:8088` |
| Token endpoint de partners | `POST http://<servidor>:8088/oauth2/token` |
| Swagger (referencia técnica de las APIs) | `http://<servidor>:8088/v3/api-docs/base-service` |
| Consul UI (solo local del servidor) | `http://127.0.0.1:8500` |
| Base de datos de auditoría (solo local) | `127.0.0.1:5433` / `hub_base` |

> Este entorno es de **staging/demo**: no exponerlo a internet ni cargar datos
> personales reales (los secretos son de desarrollo).
