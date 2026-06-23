# ADR-0004: Eliminación del módulo de desencriptación QR

**Estado**: Implementado
**Fecha**: 2026-06-22
**Autores**: Hub Interop Team

## Contexto

El negocio del hub cambió radicalmente. El producto original (`middleware-decode-qr`)
desencriptaba códigos QR cifrados por entidades financieras (RSA inverso + certificados
X.509). Ese negocio ya **no existe**: el hub evoluciona a **interoperabilidad de casos
penales (POL/FELCN ↔ MP)** (ver `docs/adr/Ficha_Tecnica_Interoperabilidad_MP_POL_FELCN.md`).

Todo el código de desencriptación QR y de gestión de certificados pasa a ser **legacy**
y debe eliminarse. Sin embargo, el módulo `mdqr-ms-base` contiene **dos cuerpos de código
entrelazados**:

1. **Negocio QR (a eliminar)**: decode de imágenes, criptografía RSA, ciclo de vida de
   certificados, sincronización con Tuxedo API, logs de desencriptación.
2. **Andamiaje del hub (a conservar)**: pipeline inbound de auditoría con cadena de hashes
   (`HubAuditInterceptor`, `HubWebMvcConfig`), librería `mdqr-audit-commons`, tablas hub
   (`hub_audit_log`, `hub_audit_idempotency`, `outbox_event`), y el adaptador outbound de
   ejemplo `EfxRate*`.

El riesgo principal es que **el andamiaje del hub está acoplado puntualmente al negocio QR**
en tres puntos verificados en código:

- `HubAuditInterceptor` / `HubWebMvcConfig` están cableados a la ruta `POST /api/qr/decode`
  (constantes `ENDPOINT`, `PRODUCT="QR_DECODE"`, `addPathPatterns("/api/qr/decode")`,
  `addUrlPatterns("/api/qr/decode")`). Eliminar `QrResource` deja el interceptor sin ruta
  que interceptar.
- `RedisConfiguration#cacheManager` lee `ApplicationProperties.getQr().getDecryption().getCacheTtlMinutes()`.
- `EfxRateAutoConfiguration` (hub, conservado) lee `ApplicationProperties.getTuxedo().getApiKey()`
  como API key de fallback en perfil local.

Por eso este ADR **no elimina** sin más: distingue qué es exclusivamente QR de qué es
andamiaje compartido, y deja un orden de eliminación seguro.

> Este ADR es **solo el inventario verificado**. No elimina código.

## Decisión

Se eliminará **todo el dominio, servicios, controllers, DTOs, changelogs, tests y
dependencias exclusivos de desencriptación QR y gestión de certificados** del módulo
`mdqr-ms-base`. Se **conserva intacto** el andamiaje del hub: `mdqr-audit-commons`
(completo), `mdqr-ms-auth` (completo), el pipeline inbound de auditoría, las tablas hub
y el adaptador `EfxRate*`.

Los tres puntos de acoplamiento se resuelven así:

- `HubAuditInterceptor` / `HubWebMvcConfig`: **se conservan como componentes**, pero deben
  **re-apuntarse** al nuevo endpoint de negocio penal (constantes `ENDPOINT`, `PRODUCT`,
  `addPathPatterns`, `addUrlPatterns`). No se borran; se reescriben. Hasta que exista el
  nuevo endpoint, pueden quedar temporalmente sin ruta (el interceptor no falla si no
  matchea ninguna ruta).
- `ApplicationProperties`: se conservan las clases `ApplicationProperties` y su anidada
  `Tuxedo` (usadas por `EfxRateAutoConfiguration`), pero se **eliminan las anidadas `Qr`
  y `Certificate`** y sus referencias.
- `RedisConfiguration`: se conserva el bean (Redis es infra del hub), pero el TTL debe
  dejar de leer `getQr().getDecryption()` y pasar a una constante o nueva propiedad.

## Inventario de eliminación

### A. Clases de dominio (mdqr-ms-base)

Paquete `bo.com.sintesis.mdqr.base.domain`.

| Clase | Archivo | Referencias entrantes | ¿Rompe andamiaje hub? |
|---|---|---|---|
| `Certificate` (+ enum `CertificateStatus`) | `domain/Certificate.java` | `CertificateService`, `CertificateVersionService`, `CertificateAuditService`, `CertificateMapper`, `CertificateRepository`, `CertificateResource`, `DecryptionLog` (FK), `QrDecryptionService` (vía service) | No |
| `CertificateVersion` (+ enum `ChangeType`) | `domain/CertificateVersion.java` | `CertificateVersionService`, `CertificateService`, `CertificateVersionRepository` | No |
| `CertificateAuditLog` (+ enum `AuditAction`) | `domain/CertificateAuditLog.java` | `CertificateAuditService`, `CertificateAuditLogRepository`, `CertificateResource` | No |
| `DecryptionLog` | `domain/DecryptionLog.java` | `AuditService`, `DecryptionLogRepository`, `QrResource` | No |
| `AdminAuditLog` | `domain/AdminAuditLog.java` | `AuditService`, `AdminAuditLogRepository` | No. Auditoría administrativa legacy de ms-base; la auditoría del hub es `hub_audit_log`. La auditoría admin/IAM real vive en `mdqr-ms-auth` |
| `FinancialEntity` | `domain/FinancialEntity.java` | `FinancialEntityRepository` (sin más usuarios en código Java) | No. Mapea tabla `entity` (ex `trx_entity_code`), catálogo de bancos para QR |
| `AbstractAuditingEntity<T>` | `domain/AbstractAuditingEntity.java` | Solo `Certificate` extiende esta clase | No. Compartida en teoría, pero su único consumidor es `Certificate`. Va a eliminación al quedar huérfana. **Verificar** que `AuditingConfiguration`/`@EnableJpaAuditing` sigue teniendo al menos una entidad auditada antes de borrar (si no queda ninguna, `AuditingConfiguration` también queda sin uso funcional) |

### B. Servicios y componentes (mdqr-ms-base)

| Clase | Archivo | Referencias entrantes | ¿Rompe andamiaje hub? |
|---|---|---|---|
| `QrDecryptionService` (+ inner `QrComponents`) | `service/QrDecryptionService.java` | `QrResource` | No |
| `QrImageDecoderService` | `service/QrImageDecoderService.java` | `QrResource` | No |
| `CryptoService` (+ inner `CertificateMetadata`) | `service/CryptoService.java` | `QrDecryptionService`, `AuditService`, `LocalCertificateLoader` | No |
| `CertificateService` | `service/CertificateService.java` | `CertificateResource`, `QrDecryptionService` | No |
| `CertificateVersionService` | `service/CertificateVersionService.java` | `CertificateService` | No |
| `CertificateValidationService` (+ inner `CertificateMetadata`) | `service/CertificateValidationService.java` | `CertificateService` | No |
| `CertificateAuditService` | `service/CertificateAuditService.java` | `CertificateService` | No |
| `AuditService` | `service/AuditService.java` | `QrResource`, `QrDecryptionService` | No. Es la auditoría legacy QR (DecryptionLog/AdminAuditLog), distinta de `HubAuditService` (que está en `mdqr-audit-commons` y se conserva) |
| `LocalCertificateLoader` (+ inner `CertificateInfo`) | `service/LocalCertificateLoader.java` | Ninguna inyección encontrada; `@Service` con `@PostConstruct` que escanea disco | No. Eliminar |
| `TuxedoApiClient` (+ DTOs internos) | `client/TuxedoApiClient.java` | Inyecta `ApplicationProperties.getTuxedo()` | No directamente, pero ver nota en sección H sobre `ApplicationProperties.Tuxedo` (se conserva la config, se elimina el client) |
| `CertificateMapper` | `service/mapper/CertificateMapper.java` | `CertificateResource` | No |
| `SecurityUtils` | `security/SecurityUtils.java` | `AuditService` (`getCurrentUserClientId`, `getCurrentUserLogin`) | No. Su único consumidor es `AuditService` (QR). Va a eliminación al quedar huérfana — **verificar** que ninguna clase del hub la use (no se encontró ninguna) |

Repositorios (paquete `repository`):

| Clase | Archivo | ¿Rompe andamiaje hub? |
|---|---|---|
| `CertificateRepository` | `repository/CertificateRepository.java` | No |
| `CertificateVersionRepository` | `repository/CertificateVersionRepository.java` | No |
| `CertificateAuditLogRepository` | `repository/CertificateAuditLogRepository.java` | No |
| `DecryptionLogRepository` | `repository/DecryptionLogRepository.java` | No |
| `AdminAuditLogRepository` | `repository/AdminAuditLogRepository.java` | No |
| `FinancialEntityRepository` | `repository/FinancialEntityRepository.java` | No |

Excepciones (paquete `service.exception`) — todas exclusivas de QR/certificados:

| Clase | Archivo | ¿Rompe andamiaje hub? |
|---|---|---|
| `CertificateInactiveException` | `service/exception/CertificateInactiveException.java` | No |
| `CertificateRevokedException` | `service/exception/CertificateRevokedException.java` | No |
| `DuplicateCertificateException` | `service/exception/DuplicateCertificateException.java` | No |
| `MissingCertificateException` | `service/exception/MissingCertificateException.java` | No |
| `DecryptionException` | `service/exception/DecryptionException.java` | No |
| `InvalidQrFormatException` | `service/exception/InvalidQrFormatException.java` | No |
| `TuxedoApiException` | `service/exception/TuxedoApiException.java` | No |

### C. Controllers y endpoints

| Clase / Ruta | Archivo | Método | ¿Rompe andamiaje hub? |
|---|---|---|---|
| `QrResource` — `POST /api/qr/decode` | `web/rest/QrResource.java` | `decodeQr` | **SÍ (indirecto)**. `HubAuditInterceptor`/`HubWebMvcConfig` están cableados a `/api/qr/decode`. Al borrar la ruta, el interceptor queda sin endpoint. Re-apuntar el interceptor al nuevo endpoint penal |
| `QrResource` — `POST /api/qr/decode/file` | `web/rest/QrResource.java` | `decodeQrFromFile` | No (el interceptor solo cubre `/api/qr/decode`, no `/file`) |
| `QrResource` — `GET /api/qr/audits` | `web/rest/QrResource.java` | `getAudits` | No |
| `CertificateResource` — `/api/certificates/**` (list, upload, upload-file, validate, {id}, {id}/pem, entity/{id}, expiring/{days}, {id}/activate, {id}/deactivate, {id}/revoke, {id}/replace, audits) | `web/rest/CertificateResource.java` | múltiples | No |
| `GlobalExceptionHandler` | `web/rest/errors/GlobalExceptionHandler.java` | `@RestControllerAdvice` | **PARCIAL**. Maneja excepciones QR (`MissingCertificateException`, `DecryptionException`, `InvalidQrFormatException`, `TuxedoApiException`) **y** genéricas (`MethodArgumentNotValid`, `AccessDenied`, `Authentication`, `ErrorResponse`, `Exception`). NO eliminar la clase: quitar solo los 4 `@ExceptionHandler` de excepciones QR. Los genéricos los necesita el hub (incl. el 409 por `IdempotencyKeyConflictException` propagado desde el interceptor) |

### D. DTOs

| Clase | Paquete | Usado por | ¿Rompe andamiaje hub? |
|---|---|---|---|
| `DecodeQrRequest` (+ enum `InputType`) | `web.rest.dto` | `QrResource` | No |
| `DecryptQrRequest` | `web.rest.dto` | `QrResource`, `QrDecryptionService` | No |
| `DecryptQrResponse` | `web.rest.dto` | `QrResource`, `QrDecryptionService` | No |
| `AuditLogFilter` | `service.dto` | `QrResource`, `AuditService` | No |
| `CertificateDTO` | `service.dto` | `CertificateMapper`, `CertificateResource` | No |
| `CertificateDetailDTO` | `service.dto` | `CertificateMapper`, `CertificateResource` | No |
| `UploadCertificateRequest` | `service.dto` | `CertificateResource` | No |
| `ImportCertificateRequest` | `service.dto` | `CertificateService`/`CertificateResource` (cert) | No |
| `ReplaceCertificateRequest` | `service.dto` | `CertificateResource` | No |
| `RevokeCertificateRequest` | `service.dto` | `CertificateResource` | No |

> El paquete `service.dto` queda con `AuditLogFilter` + 5 DTOs de certificado, todos
> eliminables. El paquete `web.rest.dto` queda completamente vacío de QR.

### E. Changelogs Liquibase

Archivos en `src/main/resources/db/changelog/`. Eliminar tanto el archivo como su
`<include>` en `db.changelog-master.xml`.

| Archivo | Tablas / objetos que crea | ¿Rompe andamiaje hub? |
|---|---|---|
| `changes/001-create-certificate.xml` | `certificate` (+ seq `certificate_seq`, índices) | No |
| `changes/002-create-decryption-log.xml` | `decryption_log` (+ seq, FK → `certificate`, índices) | No |
| `changes/003-create-admin-audit-log.xml` | `admin_audit_log` (+ seq, índices) | No |
| `changes/006-create-certificate-version.xml` | `certificate_version` (+ seq, índices) | No |
| `changes/007-create-certificate-audit-log.xml` | `certificate_audit_log` (+ seq) | No |
| `changes/008-create-trx-entity-code.xml` | `trx_entity_code` (catálogo de bancos MLD/ACCL/UNI) | No |
| `changes/009-schema-refactoring.xml` | renombra `trx_entity_code`→`entity`, `decryption_log`→`decode_log`, FK `certificate.entity_id`→`entity.code` | No |
| `changes/data/100-dev-api-key.csv`, `changes/data/100-dev-partner.csv` | datos seed dev (no incluidos en master) | No. Revisar si son referenciados; aparentemente no en master |

Changelogs **CONSERVADOS** (hub):

| Archivo | Tablas que crea |
|---|---|
| `v2/0001-hub-audit-log.xml` | `hub_audit_log` (particionada) + `hub_audit_idempotency` |
| `v2/0002-outbox-event.xml` | `outbox_event` |
| `v2/0003-hub-measurement.xml` | `hub_measurement` |
| `v2/0004-provider.xml` | `provider` + `provider_credential_ref` |

> **Nota sobre BD existente**: estos changelogs ya ejecutaron en ambientes vivos. Eliminar
> los `<include>` NO borra las tablas en BD existentes (Liquibase no hace rollback
> automático). Para entornos limpios el efecto es inmediato; para entornos vivos se
> requiere un changeset de `dropTable` explícito en una migración nueva (decisión separada,
> fuera del alcance de este inventario). El orden de FK al dropear sería el inverso:
> `decryption_log`/`decode_log` → `certificate_audit_log` → `certificate_version` →
> `certificate` → `entity`/`trx_entity_code`.

### F. Tests

Archivos en `src/test/java/bo/com/sintesis/mdqr/base/`.

| Archivo | Motivo de eliminación | ¿Afecta cobertura del hub? |
|---|---|---|
| `hub/QrDecodePartnerIT.java` | **Es test del hub** (verifica `hub_audit_log`, `outbox_event`, cadena de hashes, idempotencia, resiliencia) **pero usa el endpoint `POST /api/qr/decode`** que desaparece. Hay que **migrarlo, no solo borrarlo**: re-apuntar a la nueva ruta penal una vez exista. Toda la lógica de aserción del pipeline (hashes, outbox, idempotency) es reutilizable | **SÍ** — es la cobertura principal del pipeline inbound del hub. No debe perderse: migrar al nuevo endpoint |
| `service/ExchangeRateServiceTest.java` | Ya excluido en `build.gradle` (`compileTestJava.exclude`). Test legacy que referencia código eliminado (SDK intraplatinum / genesis). Eliminar el archivo y su `exclude` | No |
| `service/PaymentServiceTest.java` | Ya excluido. Legacy | No |
| `service/AccountServiceScheduleValidationTest.java` | Ya excluido. Legacy | No |
| `service/IdempotencyServiceTest.java` | Ya excluido. Legacy | No |
| `service/client/CurrencyEngineClientTest.java` | Ya excluido (`CurrencyEngineClientTest.java`). Legacy (currencyengine dto) | No |
| `integration/LiquibaseMigrationTest.java` | Ya excluido. Si valida changelogs QR, eliminar; si se readapta a changelogs v2 del hub, conservar/migrar | Posible — revisar al migrar |
| `integration/TransactionTimerServiceIT.java` | Ya excluido. Legacy | Revisar |
| `service/NativeHintsTest.java` | Referenciado en `build.gradle` como excluido pero **ya no existe** el archivo. Quitar la línea `exclude '**/NativeHintsTest.java'` | No |

Tests **CONSERVADOS** (hub):

| Archivo | Cobertura |
|---|---|
| `interop/outbound/efxrate/EfxRateAdapterTest.java` | Adaptador outbound EfxRate |
| `interop/outbound/efxrate/EfxRateClientIT.java` | Client EfxRate (WireMock) |

### G. Dependencias de build (build.gradle de mdqr-ms-base)

| Artefacto | Motivo | ¿Usado por algo del hub? |
|---|---|---|
| `org.bouncycastle:bcprov-jdk18on:1.78` | Solo `CryptoService` (RSA, X.509) | No |
| `org.bouncycastle:bcpkix-jdk18on:1.78` | Solo `CryptoService` (PEM parsing) | No |
| `com.google.zxing:core:3.5.3` | Solo `QrImageDecoderService` (decode imagen QR) | No |
| `com.google.zxing:javase:3.5.3` | Solo `QrImageDecoderService` (`BufferedImageLuminanceSource`) | No |
| `org.mongodb:bson:5.6.0` | Solo `QrDecryptionService#generateLogId()` (`ObjectId`). **Verificar** que `HubAuditService`/`mdqr-audit-commons` use `UUID` (sí: el interceptor usa `UUID.randomUUID()`) antes de quitar | No (verificado: el hub usa `java.util.UUID`) |

Dependencias **NO tocar** (las usa el hub o son infra base): `spring-cloud-starter-vault-config`
(Vault para firma/secretos), `spring-boot-starter-data-redis` + `commons-pool2` (caché EfxRate),
`resilience4j-*` (pipeline outbound), `spring-boot-starter-data-jpa`/`jdbc`, `liquibase-core`,
`postgresql`, Jackson, `project(':mdqr-audit-commons')`, Testcontainers/WireMock/awaitility.

> El `wiremock-standalone` se conserva: lo usa `EfxRateClientIT`.

### H. Configuración (application.yml, ApplicationProperties, gateway routes)

| Elemento | Archivo | ¿Rompe andamiaje hub? |
|---|---|---|
| Bloque `application.tuxedo.*` | `ms-base/application.yml` (152-155), `application-local.yml` (69-71) | **PARCIAL**. La clase `ApplicationProperties.Tuxedo` la lee `EfxRateAutoConfiguration` (fallback API key local). Si se elimina `TuxedoApiClient`, la config `tuxedo` puede simplificarse a solo `api-key`, pero NO eliminar la propiedad mientras EfxRate dependa de ella. Decisión: conservar `Tuxedo` (al menos `apiKey`) o refactorizar EfxRate para usar su propio fallback |
| Bloque `application.certificate.sync.*` | `ms-base/application.yml` (158-162), `application-local.yml` (74-...) | No. Solo QR. Eliminar config + clase `ApplicationProperties.Certificate` |
| Bloque `application.qr.decryption.*` | `ms-base/application.yml` (165-169), `application-local.yml` (79-...) | **PARCIAL**. `RedisConfiguration#cacheManager` lee `getQr().getDecryption().getCacheTtlMinutes()`. Antes de eliminar `ApplicationProperties.Qr`, cambiar `RedisConfiguration` a una constante / nueva propiedad |
| Clase anidada `ApplicationProperties.Qr` (+ `Decryption`) | `config/ApplicationProperties.java` | **PARCIAL** — ver fila anterior (RedisConfiguration) |
| Clase anidada `ApplicationProperties.Certificate` (+ `Sync`) | `config/ApplicationProperties.java` | No |
| Clase anidada `ApplicationProperties.Tuxedo` | `config/ApplicationProperties.java` | **CONSERVAR** — la usa `EfxRateAutoConfiguration` |
| `OpenApiConfiguration` (título/descr. "QR Decryption Service", roles QR, formato QR) | `config/OpenApiConfiguration.java` | No rompe, pero **actualizar** textos (no eliminar la clase: define el esquema `bearer-jwt` y servidores) |
| `HubAuditInterceptor` constantes `PRODUCT="QR_DECODE"`, `ENDPOINT="/api/qr/decode"` | `hub/HubAuditInterceptor.java` | **SÍ — re-apuntar**. No eliminar la clase; cambiar constantes al nuevo endpoint penal |
| `HubWebMvcConfig` `addPathPatterns("/api/qr/decode")`, `addUrlPatterns("/api/qr/decode")` | `hub/HubWebMvcConfig.java` | **SÍ — re-apuntar**. No eliminar la clase; cambiar patrones al nuevo endpoint |
| Ruta gateway `partner-qr-api` (`/partner/v1/** → lb://mdqrbaseservice /api/**`) | `gateway/application.yml` (149-160) | No rompe el chain (es genérica `/partner/v1/**`). El comentario "Partner QR decode API" y el `id: partner-qr-api` son cosméticos; **renombrar** al nuevo dominio. La reescritura `/partner/v1/(segment)→/api/(segment)` se conserva |

> El gateway **no tiene filtros ni clases Java exclusivas de QR**. Solo la ruta `partner-qr-api`
> con naming QR (cosmético) y los comentarios. No hay nada que eliminar en
> `mdqr-gateway/src/main/java`. El token proxy, las dos chains de seguridad, IP/rate/domain
> filters y la agregación Swagger se conservan íntegros.

## Lo que se CONSERVA (no tocar)

**Módulos completos:**
- `mdqr-audit-commons/` íntegro: `HubAuditService`, `ChainHashCalculator`, `PayloadHasher`,
  `AuditSigner`/`NoOpAuditSigner`, `HubAuditCommand`, `IdempotencyKeyConflictException`,
  patrón outbox.
- `mdqr-ms-auth/` íntegro (IAM, usuarios, roles, auditoría admin/IAM real).

**En `mdqr-ms-base`:**
- Pipeline inbound: `hub/HubAuditInterceptor.java`, `hub/HubWebMvcConfig.java`
  (se conservan; se re-apuntan al nuevo endpoint, no se borran).
- Adaptador outbound de ejemplo: paquete completo
  `interop/outbound/efxrate/**` (`EfxRateAdapter`, `EfxRateClient`, `EfxRateProperties`,
  `EfxRateMapper`, `EfxRateAutoConfiguration`, DTOs y excepciones) + canónicos
  `interop/canonical/ExchangeRateRequest|Response`.
- `config/ApplicationProperties.java` (clase + anidada `Tuxedo`; se eliminan anidadas
  `Qr` y `Certificate`).
- `config/RedisConfiguration.java`, `config/AsyncConfiguration.java`,
  `config/LiquibaseConfiguration.java`, `config/SecurityConfiguration.java`,
  `config/OpenApiConfiguration.java` (actualizar textos), `config/AuditingConfiguration.java`
  (revisar si queda sin entidades auditadas), `MdqrMsBaseApplication.java`.
- `web/rest/errors/GlobalExceptionHandler.java` (quitar solo handlers QR).
- Changelogs `v2/0001`..`v2/0004` y tablas `hub_audit_log`, `hub_audit_idempotency`,
  `outbox_event`, `hub_measurement`, `provider`, `provider_credential_ref`.
- Tests `EfxRateAdapterTest`, `EfxRateClientIT`.
- Toda la infraestructura del gateway (chains, token proxy, filtros, Swagger).

## Orden de eliminación recomendado

Secuencia para no romper el build (ni el contexto de Spring) en pasos intermedios.
Cada paso debe dejar el proyecto compilando.

1. **Desacoplar el hub de la config QR (sin borrar nada de QR aún)**:
   - En `RedisConfiguration`, reemplazar `getQr().getDecryption().getCacheTtlMinutes()`
     por una constante (p.ej. 1440) o una propiedad nueva del hub.
   - Confirmar que `EfxRateAutoConfiguration` sigue usando `getTuxedo().getApiKey()`
     (se conserva `Tuxedo`).
2. **Re-apuntar el interceptor del hub** (`HubAuditInterceptor` + `HubWebMvcConfig`):
   cambiar `ENDPOINT`, `PRODUCT`, `addPathPatterns`, `addUrlPatterns` al nuevo endpoint
   penal (o dejarlo apuntando a una ruta placeholder temporal). Migrar `QrDecodePartnerIT`
   al nuevo endpoint (renombrar y conservar sus aserciones de hub).
3. **Eliminar capa web QR**: `QrResource`, `CertificateResource`; quitar los 4
   `@ExceptionHandler` QR de `GlobalExceptionHandler`; eliminar DTOs de
   `web.rest.dto` (`DecodeQrRequest`, `DecryptQrRequest`, `DecryptQrResponse`) y de
   `service.dto` (`AuditLogFilter`, `CertificateDTO`, `CertificateDetailDTO`,
   `UploadCertificateRequest`, `ImportCertificateRequest`, `ReplaceCertificateRequest`,
   `RevokeCertificateRequest`).
4. **Eliminar servicios QR**: `QrDecryptionService`, `QrImageDecoderService`,
   `CryptoService`, `CertificateService`, `CertificateVersionService`,
   `CertificateValidationService`, `CertificateAuditService`, `AuditService`,
   `LocalCertificateLoader`, `CertificateMapper`, `TuxedoApiClient`, `SecurityUtils`.
5. **Eliminar repositorios**: `CertificateRepository`, `CertificateVersionRepository`,
   `CertificateAuditLogRepository`, `DecryptionLogRepository`, `AdminAuditLogRepository`,
   `FinancialEntityRepository`.
6. **Eliminar dominio**: `Certificate`, `CertificateVersion`, `CertificateAuditLog`,
   `DecryptionLog`, `AdminAuditLog`, `FinancialEntity`, y finalmente
   `AbstractAuditingEntity` (huérfana). Revisar `AuditingConfiguration` si ya no hay
   entidades auditadas.
7. **Eliminar excepciones QR**: las 7 de `service.exception`.
8. **Eliminar config QR**: anidadas `Qr` y `Certificate` de `ApplicationProperties`;
   bloques `qr.*` y `certificate.*` de `application.yml`/`application-local.yml`.
9. **Eliminar changelogs**: quitar `<include>` de `001,002,003,006,007,008,009` en
   `db.changelog-master.xml` y borrar los archivos (+ CSV dev). Para BD viva, planear un
   changeset `dropTable` aparte.
10. **Limpiar build.gradle**: quitar `bcprov`, `bcpkix`, `zxing core`, `zxing javase`,
    `mongodb:bson`; quitar `exclude '**/NativeHintsTest.java'` (archivo inexistente) y los
    `exclude` de tests legacy que se borren.
11. **Cosmético**: actualizar textos de `OpenApiConfiguration`; renombrar la ruta
    `partner-qr-api` del gateway y sus comentarios.

## Consecuencias

**Positivas:**
- Se elimina ~todo el dominio criptográfico y de certificados: menos superficie de ataque,
  menos dependencias (BouncyCastle, ZXing, bson) y un build más liviano.
- El andamiaje del hub queda limpio y reorientable al negocio penal con cambios mínimos
  (re-apuntar interceptor + migrar un IT).
- Desaparece el `SecurityConfiguration` en "modo desarrollo" como deuda asociada a QR (se
  revisará la seguridad real al definir el endpoint penal).

**Negativas / riesgos:**
- `QrDecodePartnerIT` es la única cobertura del pipeline inbound del hub. Si se borra sin
  migrar, se pierde la verificación de cadena de hashes / outbox / idempotencia. **Mitigación**:
  migrarlo en el paso 2, no en el 3.
- Tres puntos de acoplamiento (`RedisConfiguration`→`getQr`, `EfxRate`→`getTuxedo`,
  interceptor→`/api/qr/decode`) hacen que un borrado ingenuo rompa el contexto de Spring.
  **Mitigación**: respetar el orden (pasos 1-2 antes de borrar).
- BD en ambientes vivos: quitar los `<include>` no dropea tablas. Quedarán tablas huérfanas
  hasta un changeset de limpieza explícito (decisión separada).
- `AbstractAuditingEntity` y `AuditingConfiguration` (`@EnableJpaAuditing`) quedan sin
  entidades que auditar; revisar si se eliminan o se reutilizan para futuras entidades del
  negocio penal.
- Nombres heredados estables (`mdqr-*`, `mdqrbaseservice`, Vault `mdqr-decode`, realms): este
  ADR **no** los renombra (regla del proyecto). El decoupling de nombres es trabajo aparte.
