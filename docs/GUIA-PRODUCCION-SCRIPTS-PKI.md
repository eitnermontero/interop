# Guía de Producción — Scripts de Bootstrap, Alta de Partners y PKI

> ⚠️ **Documento parcialmente desactualizado** (contiene contenido legacy pre-ADR-0004/rename 2026-07-03).
> Fuente de verdad actual: `CLAUDE.md` y `docs/adr/` (ADR-0005/0006/0007).

**Documento operativo** · Hub de Interoperabilidad FELCN · Dominio `felcn.gob.bo`

> Objetivo: dejar por escrito **cómo se configura el hub desde cero por script**,
> qué se hace **una sola vez** (bootstrap) y qué se repite **por cada partner**,
> las **implicancias de migrar la PKI** de OpenSSL a Vault PKI, y los **requisitos
> de un entorno de producción**. No se usa el frontend para administrar partners:
> todo es por script.

---

## 0. Estado real hoy (punto de partida, verificado en código)

| Área | Estado actual | Implicación |
|---|---|---|
| **PKI** | OpenSSL self-signed (`create-pki.sh`), `ca.key` **en disco** en `deploy/certs/` | Sin CRL/OCSP → **no se puede revocar un cert individual** hoy. Ver §5. |
| **mTLS gateway** | `application-prod.yml`: `client-auth: want` (opcional), truststore = `ca.crt` | El gateway puede terminar mTLS en `:8443`, pero como `want` no lo **exige**. |
| **mTLS nginx** | `ssl_verify_client` **comentado**, `proxy_ssl_verify off` | El borde **aún no valida** el cert de cliente. `HUB_MTLS_TEST_MODE` activo. |
| **Vault** | Solo KV v2, en **dev mode** (token `root`, memoria, se pierde al reiniciar) | Prod necesita Vault **persistente + unseal**. Ver §6. |
| **Vault Transit** | **No configurado** | Firma de auditoría corre en `NoOpAuditSigner` (sin firma real). |
| **Vault PKI** | **No configurado** | La migración del ADR-0001 está pendiente. |
| **Firma auditoría** | `NoOp` (hash encadenado sí, firma no) | Para no-repudio real hay que habilitar Transit. |
| **Scripts de partner** | Dos scripts **no integrados**: `create-pki.sh` (cert) + `create-partner.sh` (client+vault+db) | Se corren por separado; ver §4 y §7 (gotchas). |

> **Conclusión:** el camino de escritura del hub está construido, pero la capa
> criptográfica de producción (PKI real, mTLS obligatorio, firma de auditoría)
> está en modo desarrollo. Esta guía cubre cómo llevarla a producción por script.

---

## 1. Inventario de scripts

| Script | Frecuencia | Qué hace |
|---|---|---|
| `tools.sh` | bootstrap | Levanta el stack Docker de tools (Keycloak, Consul, Vault, Redis). |
| `create-pki.sh` | bootstrap + por partner | Genera CA raíz, cert de servidor, truststore y certs de cliente (OpenSSL). |
| `vault-seed.sh` | bootstrap (por namespace) | Escribe los KV que las apps leen al arrancar (redis, db, keycloak). |
| `keycloak-sync-admin.sh` | bootstrap | Crea/sincroniza realm `hub-admin` (clients, roles, scopes, SPA). |
| `keycloak-sync-partner.sh` | bootstrap + por partner | Crea/sincroniza realm `hub-partner`; `--add-client` para un partner. |
| `create-partner.sh` | por partner | Crea client M2M + secreto en Vault + (opcional) registros en DB. |
| `build-images.sh` | por release | Construye imágenes Docker con `Dockerfile.service`. |
| `env-sync.sh` | soporte | Sincroniza variables de entorno entre configs. |

---

## 2. Bootstrap de producción — configuración de una sola vez

Orden estricto. Cada paso deja listo el siguiente.

### 2.1 Prerrequisitos del host

```bash
# Red docker compartida
docker network create --driver bridge --opt com.docker.network.driver.mtu=1500 hub-shared

# PostgreSQL en el host (no en el stack tools). Crear las DBs:
#   hub_interop  (ms-base / decode + hub_audit_log, outbox)  schema public
#   hub_auth    (ms-auth / admin)                            schema admin
```

Herramientas requeridas en el host: `docker`, `curl`, `jq`, `openssl`, `keytool` (JDK), `nginx`.

### 2.2 Levantar tools (Keycloak, Consul, Vault, Redis)

```bash
# Copiar y ajustar deploy/tools/.env desde .env.example (bind IP, passwords, profile)
deploy/scripts/tools.sh --up
deploy/scripts/tools.sh --info    # verificar URLs/puertos
```

> **Producción:** el Vault del stack tools está en **dev mode** (efímero). Para
> prod real, sustituir por un Vault con storage persistente + unseal (ver §6.2).

### 2.3 Generar la PKI (CA + cert de servidor + truststore)

```bash
# Para producción, el CN del server cert debe ser el dominio, no una IP:
SERVER_CN=desarrollo.felcn.gob.bo \
CA_CN="FELCN Hub Root CA" \
CA_ORG="FELCN" \
CA_COUNTRY=BO \
KEYSTORE_PASSWORD='<PASSWORD-FUERTE>' \
TRUSTSTORE_PASSWORD='<PASSWORD-FUERTE>' \
  deploy/scripts/create-pki.sh
```

Produce en `deploy/certs/`: `ca.crt`, `ca.key` (⚠️ secreto), `server.p12`,
`truststore.p12`. **`ca.key` NO se distribuye.**

> **Distinción crítica de dos PKIs** (ver §6.3): esta CA (`ca.crt`) es para el
> **mTLS de cliente** (certificados de partners). El **TLS público** del dominio
> `felcn.gob.bo` (lo que valida el browser) es **otro certificado distinto**,
> emitido por una CA pública/estatal, que va en nginx.

### 2.4 Sembrar Vault (KV) por namespace

```bash
# Namespace hub-auth (ms-auth)
VAULT_TOKEN=<token> TOOLS_HOST=<IP_HOST> DB_NAME=hub_auth DB_PASSWORD='***' \
  deploy/scripts/vault-seed.sh --ns hub-auth --kc-realm hub-admin --external https://vault.felcn:8200

# Namespace hub-base (ms-base + gateway)
VAULT_TOKEN=<token> TOOLS_HOST=<IP_HOST> DB_NAME=hub_interop DB_PASSWORD='***' \
  deploy/scripts/vault-seed.sh --ns hub-base --kc-realm hub-admin --external https://vault.felcn:8200
```

> El flag `--external` usa un container Vault efímero (no requiere CLI en el host)
> y **exige** un `VAULT_TOKEN` real con permiso de escritura (no `root`).

### 2.5 Sincronizar realms de Keycloak

```bash
KC_URL=http://<IP_HOST>:8180 KC_PASSWORD='<ADMIN_PWD>' \
  deploy/scripts/keycloak-sync-admin.sh --yes-to-all

KC_URL=http://<IP_HOST>:8180 KC_PASSWORD='<ADMIN_PWD>' \
  deploy/scripts/keycloak-sync-partner.sh --yes-to-all
```

### 2.6 Construir y publicar imágenes

```bash
deploy/scripts/build-images.sh   # usa deploy/docker/Dockerfile.service
# push al registry cr.sintesis.com.bo/hub/...
```

### 2.7 Configurar y levantar los servicios

```bash
cp deploy/production/.env.example deploy/production/.env
# Completar todos los <REQUERIDO>: HUB_KEYCLOAK_URL, HUB_VAULT_TOKEN,
# HUB_SSL_*_PASSWORD (deben coincidir con create-pki.sh), CORS, DB URLs.
docker compose -f deploy/production/docker-compose.yml up -d
```

### 2.8 Configurar nginx (borde público del dominio)

```bash
sudo cp deploy/nginx/hub-interop.conf /etc/nginx/sites-available/hub-interop
sudo ln -s /etc/nginx/sites-available/hub-interop /etc/nginx/sites-enabled/hub-interop
sudo mkdir -p /etc/nginx/ssl/desarrollo.felcn.gob.bo
# Copiar el cert TLS PÚBLICO del dominio (fullchain.pem + privkey.pem)
sudo nginx -t && sudo systemctl reload nginx
```

Para **activar mTLS real** en el borde (dejar de estar en test mode): descomentar
en `hub-interop.conf` los bloques `ssl_client_certificate /…/ca.crt`,
`ssl_verify_client on` y `proxy_set_header X-SSL-Client-Cert $ssl_client_escaped_cert`,
y poner `HUB_MTLS_TEST_MODE=false` en el gateway. Ver §5 y §7.

---

## 3. Verificación del bootstrap (smoke test)

```bash
# 1. Token de partner (mTLS + client_credentials) vía dominio
curl --cert deploy/certs/partners/<partner>.crt \
     --key  deploy/certs/partners/<partner>.key \
     -X POST https://desarrollo.felcn.gob.bo/api/partner/oauth2/token \
     -d grant_type=client_credentials -d client_id=<client> -d client_secret=<secret>

# 2. Verificar que el JWT trae el binding cnf.x5t#S256 (holder-of-key)
#    (decodificar el access_token y comprobar el claim cnf)

# 3. Llamada de negocio con cert + token
curl --cert ... --key ... -H "Authorization: Bearer $TOKEN" \
     -X POST https://desarrollo.felcn.gob.bo/api/partner/v1/inbound/CASO_PENAL/v1 \
     -H 'Content-Type: application/json' -d @caso.json

# 4. Confirmar registro en hub_audit_log + outbox_event
psql -d hub_interop -c "SELECT id, partner_id, product, http_status, chain_hash FROM hub_audit_log ORDER BY ts DESC LIMIT 5;"
```

---

## 4. Alta de un partner (proceso recurrente por script)

Hoy son **dos comandos** que hay que correr coordinados (no están integrados):

### Paso A — Certificado de cliente (mTLS)

```bash
KEYSTORE_PASSWORD='<PWD>' CERT_DAYS=825 \
CA_ORG="FELCN" CA_COUNTRY=BO \
  deploy/scripts/create-pki.sh --partner <nombre-partner>
```

Produce `deploy/certs/partners/<nombre>.{crt,key,p12}` e imprime el **thumbprint
SHA-256 (Base64url)** = el `cnf.x5t#S256` que Keycloak pondrá en el token.

### Paso B — Client M2M + secreto en Vault (+ opcional DB)

```bash
KC_URL=http://<IP_HOST>:8180 KC_REALM=hub-partner KC_PASSWORD='<ADMIN_PWD>' \
VAULT_CONTAINER=hub-vault VAULT_TOKEN='<token>' \
  deploy/scripts/create-partner.sh --name <nombre-partner> \
    --client-secret '<SECRETO-FUERTE>' \
    --roles "qr:decode"
```

> **Alternativa mínima** (solo client Keycloak, sin Vault/DB extra):
> `keycloak-sync-partner.sh --add-client <clientId> "<desc>" <secret>`

### Paso C — Habilitar X.509 en Keycloak (una vez por realm)

Para que Keycloak emita el `cnf.x5t#S256`, el realm `hub-partner` debe tener
**X.509/Validate Certificate** configurado (client authenticator o mapper que lea
el cert reenviado `X-SSL-Client-Cert`). Esto se configura una sola vez.

### Paso D — Entrega al partner

Empaquetar y entregar por canal seguro: `<nombre>.crt`, `<nombre>.key` (o `.p12`),
`ca.crt` (la CA del hub para su truststore), la guía de integración y la colección
Postman (`deploy/nginx/partner-docs/`). El partner carga el cert en su cliente TLS.

---

## 5. Rotación, renovación y revocación (hoy vs. con Vault PKI)

| Operación | Con OpenSSL (hoy) | Con Vault PKI (objetivo) |
|---|---|---|
| **Rotar `client_secret`** | Regenerar en Keycloak + `vault kv put .../keycloak/<client> client-secret=…` | Igual (no depende de la PKI). |
| **Renovar certificado** | Re-correr `create-pki.sh --partner <n>` (borrar el `.p12` viejo primero) | `vault write pki/sign/<role> csr=@partner.csr` — vía API, auditado. |
| **Revocar 1 certificado** | ⚠️ **No soportado**: el truststore confía en la **CA entera**; sin CRL no se puede invalidar un cert individual | `vault write pki/revoke serial=<serial>` → Vault publica CRL; nginx la valida (`ssl_crl`). |
| **Suspender partner** | Deshabilitar client en Keycloak (no emite tokens) | Igual + revocar cert. |

> **Este es el punto más importante:** con la PKI actual **la revocación granular
> de un certificado no es posible**. Solo se puede deshabilitar el client en
> Keycloak (corta tokens nuevos) o rehacer toda la CA (corta a todos). La
> revocación real por partner **requiere CRL/OCSP → requiere Vault PKI** (o al
> menos un flujo OpenSSL con CRL, que hoy no existe).

---

## 6. Migrar la PKI: OpenSSL → Vault PKI (implicancias)

El ADR-0001 manda **Vault PKI**. Hoy se usa OpenSSL. Migrar implica lo siguiente.

### 6.1 Qué cambia

| Aspecto | OpenSSL (hoy) | Vault PKI |
|---|---|---|
| Clave de la CA | `deploy/certs/ca.key` en disco | **Dentro de Vault**, nunca en filesystem |
| Emisión de cert | `openssl x509 -req` local | `vault write pki/issue/<role>` o `pki/sign` (CSR) — API |
| Revocación | ❌ sin CRL | ✅ `pki/revoke` + CRL/OCSP publicados por Vault |
| TTL / política | solo `notAfter` del cert | rol Vault con `ttl`/`max_ttl` forzados |
| Auditoría de emisión | ninguna | log de auditoría de Vault |
| Custodia de clave privada | el hub genera el par (ve la clave) | **CSR del partner**: la clave nunca toca el hub |

### 6.2 Prerrequisito duro: Vault persistente

Vault PKI **guarda la CA dentro de Vault**. Si Vault corre en **dev mode**
(como hoy), al reiniciar **se pierde la CA** → **todos los certs de partners
quedan inválidos**. Por lo tanto, migrar a Vault PKI **obliga** a:

- Vault con **storage persistente** (raft/integrated storage o Consul backend).
- **Unseal** gestionado (auto-unseal con KMS, o llaves Shamir custodiadas).
- Autenticación por **AppRole** (no token `root`) para los servicios.
- Backup de Vault (la CA es ahora un secreto crítico dentro de Vault).

### 6.3 Cambios concretos en el borde (nginx) y scripts

- nginx `ssl_client_certificate` sigue siendo la **cadena de la CA** — se exporta
  desde Vault (`vault read pki/cert/ca`) a un archivo que nginx lee.
- nginx `ssl_crl` apunta a la **CRL publicada por Vault** (`pki/crl/pem`),
  refrescada periódicamente por un cron.
- `create-pki.sh` (CA + emisión) se reemplaza por comandos `vault write pki/...`.
- `create-partner.sh` cambia el paso del cert por `pki/sign` de un CSR.
- **No cambia**: el `MtlsCertBindingFilter` del gateway (el thumbprint
  `cnf.x5t#S256` se calcula igual sin importar quién firmó), ni la config X.509
  de Keycloak, ni la forma de la entrega al partner.

### 6.4 Flujo recomendado con Vault PKI (CSR del partner)

1. El partner genera su par de claves y un **CSR** (la clave privada **nunca**
   sale de su lado → mejor no-repudio).
2. La FELCN firma: `vault write pki/sign/partner csr=@partner.csr ttl=825d`.
3. Se entrega al partner el cert firmado + `ca.crt`. La clave privada se queda
   con el partner.
4. Revocación: `vault write pki/revoke serial=<serial>` → CRL actualizada → nginx
   rechaza en el borde.

### 6.5 Esfuerzo / riesgo de la migración

- **Medio-alto**, concentrado en infraestructura (Vault persistente + unseal +
  AppRole + backup), **no** en el código Java (el binding no cambia).
- Riesgo principal: la **CA pasa a ser un activo dentro de Vault**; su pérdida o
  un unseal mal gestionado deja el hub sin poder validar/emitir. Requiere
  procedimiento de custodia de llaves y DR probado.
- Ganancia: **revocación real**, TTLs forzados, sin claves de CA en disco,
  emisión auditada — cumplimiento del ADR-0001 y de los lineamientos AGETIC.

---

## 7. Requisitos de un entorno de producción (dominio `felcn.gob.bo`)

### 7.1 Recursos de infraestructura

| Recurso | Requisito de producción |
|---|---|
| **DNS** | `desarrollo.felcn.gob.bo` (y el dominio de prod) → IP pública del borde. |
| **TLS público del dominio** | Certificado emitido por CA pública/estatal (o Let's Encrypt) para nginx `:443`. **Distinto** de la CA mTLS interna. Renovación automatizada. |
| **CA mTLS interna** | La CA del hub (`create-pki.sh` o Vault PKI) que firma los certs de partners. |
| **Vault** | Persistente + unseal + AppRole + backup (ver §6.2). Habilitar **Transit** (firma de auditoría) y, si se migra, **PKI**. |
| **Keycloak** | Storage `postgres` (no `dev-file`), issuer estable = hostname del dominio, HTTPS. |
| **PostgreSQL** | DBs `hub_interop` y `hub_auth`, backups, retención de `hub_audit_log`/`outbox`. |
| **Consul / Redis** | Persistentes; considerar profile `cluster` para HA. |
| **nginx** | Terminación TLS pública + (al activar) validación mTLS de cliente + reenvío `X-SSL-Client-Cert`. |

### 7.2 Endurecimiento obligatorio antes de prod (cambiar defaults de dev)

- [ ] `KEYSTORE_PASSWORD` / `TRUSTSTORE_PASSWORD` ≠ `changeit`.
- [ ] `HUB_VAULT_TOKEN` = AppRole, **no** token `root`.
- [ ] `client_secret` de cada partner **fuerte** (no `= client_id`, que es el default del script).
- [ ] `SERVER_CN` de la PKI = dominio, no IP `172.16.76.20`.
- [ ] `HUB_KEYCLOAK_URL` / `external-url` = hostname real del dominio (issuer consistente).
- [ ] CORS restringido al dominio del frontend (no `*`).
- [ ] `HUB_MTLS_TEST_MODE=false` y mTLS real activado en nginx (§2.8).
- [ ] Habilitar validación JWT en **ms-base** (hoy `permitAll` — modo desarrollo).
- [ ] Habilitar **Vault Transit** para firma real de auditoría (hoy `NoOp`).

### 7.3 Decisión de topología de mTLS (define la config)

Dos opciones para terminar el mTLS de cliente:

- **(A) nginx termina TLS+mTLS** (recomendado para el dominio público): nginx
  valida el cert contra `ca.crt`, reenvía `X-SSL-Client-Cert` al gateway; el
  gateway lee el header (`CertForwardFilter`) y aplica el binding. El TLS público
  lo cierra nginx con el cert del dominio.
- **(B) el gateway termina mTLS en `:8443`** directamente (`client-auth: want`
  actual): sin nginx en el medio, o con nginx en TCP passthrough. Menos flexible
  para el TLS público del dominio.

En producción con `felcn.gob.bo`, lo natural es **(A)**.

### 7.4 Gotchas conocidos de los scripts (a corregir/parametrizar)

- `create-partner.sh`: default `KC_URL=http://localhost:8080` — **8080 es el
  gateway, no Keycloak** (8180). Pasar siempre `KC_URL=…:8180` explícito.
- `create-partner.sh`: default `--client-secret = client-id` → **inseguro**,
  pasar secreto fuerte siempre.
- `create-partner.sh`: arrastra campos del proyecto legacy (credenciales
  *Genesis*, tablas `partner`/`api_key_registry`, roles `cart:*`). Para el hub
  usar `--roles "qr:decode"` y, si las tablas del hub difieren, **no** usar `--db`
  hasta alinear el esquema con `partner_subscription`.
- `create-pki.sh` y `create-partner.sh` **no comparten naming** (`<nombre>` vs
  `ptnr_<nombre>`) ni están integrados: correrlos coordinados (§4).
- La **revocación por partner no funciona** con la PKI OpenSSL actual (§5) →
  gating para producción si se requiere revocación granular.

---

## 8. Checklist resumido de puesta en producción

**Bootstrap (una vez):**
1. [ ] Red docker + DBs `hub_interop` / `hub_auth`.
2. [ ] tools.sh --up (Vault persistente en prod).
3. [ ] create-pki.sh con `SERVER_CN=<dominio>` y passwords fuertes.
4. [ ] vault-seed.sh por namespace (`hub-auth`, `hub-base`) con token real.
5. [ ] keycloak-sync-admin.sh + keycloak-sync-partner.sh.
6. [ ] X.509 client auth configurado en realm `hub-partner`.
7. [ ] build-images.sh + push.
8. [ ] .env de producción completo + `docker compose up`.
9. [ ] nginx con TLS público del dominio + mTLS activado.
10. [ ] (Objetivo) Vault Transit para firma + migración a Vault PKI.

**Por cada partner:**
1. [ ] create-pki.sh --partner `<nombre>` (o `pki/sign` de CSR con Vault PKI).
2. [ ] create-partner.sh (o keycloak-sync-partner --add-client) con secreto fuerte.
3. [ ] Entrega segura de cert + ca.crt + guía + Postman.
4. [ ] Smoke test (§3) con el cert del partner.
```
