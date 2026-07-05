# Onboarding de un partner — credenciales, certificado y consumo

Procedimiento completo para dar de alta un partner del hub en un entorno real.
Verificado con el partner `felcn-api` en staging (2026-07-05).

Un partner necesita **dos credenciales ligadas entre sí** (ADR-0001):

1. **Certificado de cliente** (transporte, mTLS) — identidad y no-repudio.
2. **Client OAuth2** (aplicación, client_credentials) — autorización por scopes.

## 1. Crear las credenciales OAuth2 (Keycloak, realm `hub-partner`)

El alta es **declarativa**: una fila en el CSV de seed + re-sync (idempotente).

```bash
# 1. Generar un secret fuerte
SECRET=$(openssl rand -hex 24)

# 2. Agregar la fila al seed (clientId, descripción, scopes, secret)
echo "felcn-api,FELCN — Partner del Hub,https://api.sintesis.com.bo/caso.penal,$SECRET" \
  >> deploy/scripts/keycloak-seed/partner/clients.csv

# 3. Sincronizar el realm (crea el client M2M con service account + scope)
deploy/scripts/keycloak-sync-partner.sh
```

Reglas:
- **Un scope por producto** (`https://api.sintesis.com.bo/caso.penal`). Scope
  habilitado en el client = suscripción activa al producto (el
  `PartnerSubscriptionFilter` del gateway lo verifica en cada request).
- El CSV con secrets NO debe commitearse con secrets reales de producción —
  gestionarlos en Vault o en el gestor de secretos del pipeline.

## 2. Emitir el certificado del partner (PKI de Vault)

La PKI corre sobre el motor `pki` de **Vault** (ADR-0001): jerarquía
`HUB Root CA` (10 años) → `HUB Intermediate CA` (5 años) → certificados de
partner (TTL 90 días, rotables) con **CRL de revocación**.

```bash
# Setup de la PKI (una sola vez por Vault)
deploy/scripts/vault-pki.sh init
deploy/scripts/vault-pki.sh server <hostname-del-gateway> <ip>   # server.p12 + truststore.p12

# Por cada partner
deploy/scripts/vault-pki.sh partner felcn-api
```

Produce en `deploy/certs/`:

| Archivo | Qué es | Quién lo recibe |
|---|---|---|
| `vault-ca-chain.crt` | Cadena de CAs (intermedia + raíz) | Público (partners validan el server) |
| `server.p12` / `truststore.p12` | Keystore TLS y truststore del gateway | Solo el gateway |
| `partners/<n>.p12` (+ .crt/.key/.chain.crt) | Credencial del partner (password = `KEYSTORE_PASSWORD`) | **El partner, por canal seguro** |

Operación:
- **Rotación**: `vault-pki.sh partner <nombre>` re-emite (TTL corto por diseño — 90 días).
- **Revocación**: `vault-pki.sh revoke <serial>` → entra al CRL (`/v1/pki_int/crl`). El serial se muestra al emitir.
- **Estado**: `vault-pki.sh status` lista los seriales emitidos.

> ⚠ El Vault del stack tools corre en **dev mode** (volátil): si el contenedor
> se recrea, la CA se pierde y hay que re-emitir todo (`init` + `server` +
> `partner ...`). Para producción: Vault en modo real (raft) — bloqueante ya
> identificado. La PKI OpenSSL anterior (`create-pki.sh`) queda **deprecada**.

## 3. Qué se le entrega al partner

1. `client_id` + `client_secret` (canal seguro, separado del cert).
2. `felcn-api.p12` + password (canal seguro).
3. `vault-ca-chain.crt` (para validar el certificado del servidor).
4. URLs: token endpoint `POST https://<hub>/oauth2/token` (nunca Keycloak
   directo) y catálogo de APIs (Swagger del gateway).
5. La guía de consumo (§4) y el catálogo de `error.code` (ADR-0005 §7).

## 4. Cómo consume el partner

```bash
# 1. Token (client_credentials vía el proxy del gateway)
TOKEN=$(curl -s -X POST https://<hub>/oauth2/token \
  --cert felcn-api.crt --key felcn-api.key --cacert ca.crt \
  -d grant_type=client_credentials \
  -d client_id=felcn-api -d client_secret=<secret> \
  -d scope=https://api.sintesis.com.bo/caso.penal | jq -r .access_token)

# 2. Crear caso
curl -X POST https://<hub>/partner/v1/inbound/CASO_PENAL/v1 \
  --cert felcn-api.crt --key felcn-api.key --cacert ca.crt \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -H "X-Idempotency-Key: <uuid nuevo por operación>" \
  -d '{ "cud": "...", "id_externo_caso": 1, "id_tipo_denuncia": 3,
        "id_oficina": 12, "id_estado": 1, "id_etapa": 1 }'

# 3. Editar caso
curl -X PATCH https://<hub>/partner/v1/inbound/CASO_PENAL/v1/{id_pol_caso} \
  --cert ... -H "Authorization: Bearer $TOKEN" -H "X-Idempotency-Key: <uuid>" \
  -d '{ "id_tipo_denuncia": 4 }'
```

Contrato de respuesta: **siempre** `ApiResponse` (`success`, `status`,
`message`, `data`, `error`, `correlationId`, `timestamp`) — ADR-0005. El
partner debe **guardar el `correlationId`** de cada respuesta: es la llave para
cruzar incidencias con nuestra auditoría.

Reglas de reintento:
- `X-Idempotency-Key`: obligatoria en escrituras. Reintentar SIEMPRE con la
  misma clave; clave repetida con payload distinto → `409 IDEMPOTENCY_CONFLICT`.
- Reintentables: 503, 504 (y 502 según el caso). No reintentables: 4xx.
- `connection reset` / alert TLS = certificado ausente o no confiable (no es un
  error HTTP; revisar el cert presentado).

## 5. Verificación del alta (checklist del operador)

```bash
# Token OK
curl -s -X POST http://127.0.0.1:8088/oauth2/token -d grant_type=client_credentials \
  -d client_id=felcn-api -d client_secret=$SECRET \
  -d scope=https://api.sintesis.com.bo/caso.penal | jq .access_token

# Transacción OK + auditada
# (POST de prueba y consultar hub_audit_log — ver STAGING.md §6)
```

## 6. Paso a mTLS real + RFC 8705 (producción)

En staging el binding cert↔token corre en modo simulado
(`HUB_MTLS_TEST_MODE=true`). Para el entorno real:

1. **TLS con client-auth en el gateway** — perfil prod ya lo trae
   (`application-prod.yml`: HTTPS + `client-auth: want` + keystore/truststore
   de la PKI). En compose: montar `deploy/certs/{server,truststore}.p12` y
   setear `SSL_KEYSTORE_PASSWORD`/`SSL_TRUSTSTORE_PASSWORD`.
2. **Keycloak con binding de certificado (RFC 8705)**: habilitar en el client
   del partner `tls-client-certificate-bound-access-tokens` y exponer el token
   endpoint por mTLS (o proxy que propague el cert en header X.509 configurado
   en Keycloak). Con eso el token sale con el claim `cnf.x5t#S256`.
3. **Apagar el modo simulado**: `HUB_MTLS_TEST_MODE=false`. El
   `MtlsCertBindingFilter` pasa a exigir que el thumbprint del cert presentado
   coincida con el `cnf.x5t#S256` del JWT → un token robado es inútil sin la
   clave privada del partner.
4. Documentar al partner que el rechazo de handshake TLS no tiene body HTTP
   (única excepción al contrato `ApiResponse` — ADR-0005 §8).

## 7. Baja / rotación

- **Baja**: deshabilitar el client en Keycloak (o quitar el scope) — corta el
  acceso aunque el cert siga vigente. El cert se revoca con
  `vault-pki.sh revoke <serial>` (CRL de Vault).
- **Rotación de secret**: nuevo secret en el CSV + re-sync + entrega.
- **Rotación de cert**: `vault-pki.sh partner <nombre>` re-emite; entregar el
  nuevo `.p12`; revocar el anterior por serial.
