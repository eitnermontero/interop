# Índice de Documentación — HUB

Clasificación de todos los documentos en `docs/`.

---

## Documentos Vigentes (Sistema HUB Actual)

Fuente de verdad principal: `CLAUDE.md` (raíz del repo) y `docs/adr/`.

| Archivo | Descripción |
|---------|-------------|
| [01-SECURITY.md](01-SECURITY.md) | Seguridad del Gateway: cadenas JWT, filtros IP/rate-limit (desactualizado) |
| [02-API-ENDPOINTS.md](02-API-ENDPOINTS.md) | Referencia de endpoints: motor inbound genérico + admin (desactualizado) |
| [05-DATABASE.md](05-DATABASE.md) | Esquema de base de datos: hub_audit_log, outbox, tablas admin (desactualizado) |
| [07-CONFIGURATION.md](07-CONFIGURATION.md) | Configuración: perfiles Spring, Vault, Consul (desactualizado) |
| [08-DEPLOYMENT.md](08-DEPLOYMENT.md) | Build Gradle, Docker, estructura de módulos (desactualizado) |
| [09-VAULT.md](09-VAULT.md) | Vault: namespaces, secretos, vault-seed.sh (desactualizado) |
| [10-ADMIN-SERVICE.md](10-ADMIN-SERVICE.md) | ms-auth: usuarios, roles, menús, permisos RBAC (desactualizado) |
| [11-AUDIT-LOG.md](11-AUDIT-LOG.md) | Logs de auditoría: ms-auth audit_log + ms-base (desactualizado) |
| [12-FRONTEND-ARCHITECTURE.md](12-FRONTEND-ARCHITECTURE.md) | Arquitectura frontend Angular (monorepo) (desactualizado) |
| [ARQUITECTURA-SEGURIDAD-HUB.md](ARQUITECTURA-SEGURIDAD-HUB.md) | mTLS + RFC 8705: flujo de petición partner (desactualizado) |
| [GUIA-PRODUCCION-SCRIPTS-PKI.md](GUIA-PRODUCCION-SCRIPTS-PKI.md) | Bootstrap de producción, alta de partners, PKI (desactualizado) |
| [SECURITY-AUDITING-CONFIG.md](SECURITY-AUDITING-CONFIG.md) | Configuración de auditoría JPA y seguridad (desactualizado) |
| [FRONTEND-API-GUIDE.md](FRONTEND-API-GUIDE.md) | Guía de APIs para el frontend: tokens, endpoints, TypeScript (desactualizado) |

---

## Documentos Eliminados (Sistema MWC/ULQR — Pagos)

Los siguientes archivos fueron eliminados vía `git rm` el 2026-07-03 (ADR-0004, rename hub).
No aplican al sistema HUB actual. El historial de git los conserva si se necesitan.

| Archivo eliminado | Motivo |
|-------------------|--------|
| `03-EXCHANGE-RATE.md` | Motor de tipo de cambio USDT/BOB — sistema de pagos anterior |
| `04-CART-TIMER.md` | Timer de carrito de compras — sistema de pagos anterior |
| `06-GENESIS-CLIENT.md` | SDK intraplatinum Genesis — sistema de pagos anterior |
| `FLUJO-CRIPTOGRAFICO-CORRECTO.md` | Flujo RSA inverso QR bancario — sistema QR anterior |
| `BUSINESS-QUESTIONS-CERTIFICATE-MANAGEMENT.md` | Q&A gestión de certificados bancarios QR |
| `FRONTEND-API-COMPLETE-SPEC.md` | Especificación API MWC/ULQR |
| `FRONTEND-API-DOCS.md` | Documentación API MWC/ULQR |
| `FRONTEND-HANDOFF-SUMMARY.md` | Handoff frontend MWC/ULQR |
| `RESUMEN-SOLUCION-FINAL.md` | Resumen solución RSA inverso ULQR |
| `SOLICITUD-RECURSOS-TESTING.md` | Solicitud de recursos testing ULQR |
| `HUB-External-Postman-QA.json` | Colección Postman con endpoints QR legacy (`/partner/v1/qr/decode`) |
| `HUB-Internal-Postman-QA.json` | Colección Postman con endpoints QR/certificados legacy |

---

## Estructura del Repositorio

```
unilink-qr-decrypt/
├── README.md                        ← Setup y arquitectura general
├── docs/                            ← Documentación (este directorio)
├── deploy/
│   ├── tools/                       ← Docker Compose del stack de herramientas
│   └── scripts/
│       ├── tools.sh                 ← Gestión del stack Docker
│       ├── vault-seed.sh            ← Seedea Vault con secretos
│       ├── keycloak-sync-admin.sh   ← Crea realm hub-admin en Keycloak
│       └── keycloak-sync-partner.sh ← Crea realm hub-partner en Keycloak
├── hub-gateway/                    ← Spring Cloud Gateway (puerto 8080)
├── hub-ms-auth/                    ← Servicio de autenticación/RBAC (puerto 8083)
└── hub-ms-base/                    ← Servicio de desencriptación QR (puerto 8081)
```
