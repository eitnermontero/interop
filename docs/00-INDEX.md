# Índice de Documentación — MDQR

Clasificación de todos los documentos en `docs/`.

---

## Documentos Vigentes (Sistema MDQR Actual)

| Archivo | Descripción |
|---------|-------------|
| [01-SECURITY.md](01-SECURITY.md) | Seguridad del Gateway: cadenas JWT, filtros IP/rate-limit |
| [02-API-ENDPOINTS.md](02-API-ENDPOINTS.md) | Referencia completa de endpoints: partner + admin |
| [05-DATABASE.md](05-DATABASE.md) | Esquema de base de datos: certificados, logs, audit |
| [07-CONFIGURATION.md](07-CONFIGURATION.md) | Configuración: perfiles Spring, Vault, Consul |
| [08-DEPLOYMENT.md](08-DEPLOYMENT.md) | Build Gradle, Docker, estructura de módulos |
| [09-VAULT.md](09-VAULT.md) | Vault: namespaces, secretos, vault-seed.sh |
| [10-ADMIN-SERVICE.md](10-ADMIN-SERVICE.md) | ms-auth: usuarios, roles, menús, permisos RBAC |
| [11-AUDIT-LOG.md](11-AUDIT-LOG.md) | Logs de auditoría: ms-auth audit_log + ms-base logs |
| [12-FRONTEND-ARCHITECTURE.md](12-FRONTEND-ARCHITECTURE.md) | Arquitectura frontend Angular (monorepo) |
| [FLUJO-CRIPTOGRAFICO-CORRECTO.md](FLUJO-CRIPTOGRAFICO-CORRECTO.md) | Flujo RSA inverso confirmado: banco encripta con privada, Unilink desencripta con pública |
| [BUSINESS-QUESTIONS-CERTIFICATE-MANAGEMENT.md](BUSINESS-QUESTIONS-CERTIFICATE-MANAGEMENT.md) | Q&A sobre gestión de certificados bancarios |
| [SECURITY-AUDITING-CONFIG.md](SECURITY-AUDITING-CONFIG.md) | Configuración detallada de auditoría y seguridad |
| [FRONTEND-API-GUIDE.md](FRONTEND-API-GUIDE.md) | **Guía completa para el equipo frontend**: tokens, endpoints, TypeScript, curls |

---

## Documentos Obsoletos (Sistema MWC/ULQR — Pagos)

Estos documentos corresponden al sistema anterior de pagos (MWC/ULQR con Genesis SDK).
**No aplican al sistema MDQR actual.**

| Archivo | Por qué está obsoleto |
|---------|-----------------------|
| [03-EXCHANGE-RATE.md](03-EXCHANGE-RATE.md) | Motor de tipo de cambio USDT/BOB — sistema de pagos anterior |
| [04-CART-TIMER.md](04-CART-TIMER.md) | Timer de carrito de compras — sistema de pagos anterior |
| [06-GENESIS-CLIENT.md](06-GENESIS-CLIENT.md) | SDK intraplatinum Genesis — sistema de pagos anterior |
| [FRONTEND-API-COMPLETE-SPEC.md](FRONTEND-API-COMPLETE-SPEC.md) | Especificación API MWC — no aplica a MDQR |
| [FRONTEND-API-DOCS.md](FRONTEND-API-DOCS.md) | Documentación API MWC — no aplica a MDQR |
| [FRONTEND-HANDOFF-SUMMARY.md](FRONTEND-HANDOFF-SUMMARY.md) | Handoff frontend MWC — no aplica a MDQR |
| [RESUMEN-SOLUCION-FINAL.md](RESUMEN-SOLUCION-FINAL.md) | Resumen solución ULQR — sistema anterior |
| [SOLICITUD-RECURSOS-TESTING.md](SOLICITUD-RECURSOS-TESTING.md) | Solicitud de recursos para testing ULQR — no vigente |

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
│       ├── keycloak-sync-admin.sh   ← Crea realm mdqr-admin en Keycloak
│       └── keycloak-sync-partner.sh ← Crea realm mdqr-partner en Keycloak
├── mdqr-gateway/                    ← Spring Cloud Gateway (puerto 8080)
├── mdqr-ms-auth/                    ← Servicio de autenticación/RBAC (puerto 8083)
└── mdqr-ms-base/                    ← Servicio de desencriptación QR (puerto 8081)
```
