# ADR-0001 — Hub de Interoperabilidad con Terceros

Estado: Propuesto · Fecha: 2026-06-22

## Contexto
Proyecto independiente: hub que actúa como único punto de entrada y salida de
información entre la empresa y terceros (instituciones, negocios, partners,
clientes). Dos direcciones de tráfico:

- **Inbound**: terceros consumen APIs de negocio de la empresa.
- **Outbound**: aplicaciones de la empresa consumen APIs de terceros a través del
  hub (nunca directamente).

Requisitos transversales: mTLS, autenticación/autorización fuerte, auditoría
total, hash de los payloads intercambiados para conciliación/no-repudio, y
medición de consumo (las llamadas se facturan).

## Decisión

### 1. Doble capa de identidad (transporte + aplicación)
- **mTLS** como capa de transporte: cada partner presenta un certificado de
  cliente emitido por la **PKI de Vault** (motor `pki`), de vida corta y
  rotable. El CN/SAN identifica al partner y se enlaza a un registro de partner
  activo. Sin certificado válido y mapeado a una suscripción activa → rechazo en
  el edge.
- **OAuth2 client_credentials (Keycloak)** como capa de aplicación: el token
  porta los `scopes` que definen qué productos puede invocar el partner.
- **Tokens enlazados al certificado (RFC 8705, OAuth 2.0 mTLS)**: el access
  token queda criptográficamente ligado al certificado de cliente, de modo que
  un token robado es inútil sin la clave privada del partner. Keycloak lo
  soporta nativamente.

Justificación: el certificado da no-repudio e identidad de transporte; el token
da autorización granular. RFC 8705 elimina el riesgo de robo de bearer token.

### 2. Auditoría inmutable y hash de payloads
- Toda transacción (inbound y outbound) genera un registro de auditoría con:
  dirección, partner, producto, endpoint, **hash SHA-256 del request y del
  response**, estado, latencia, unidades facturables y timestamp.
- **Canonicalización antes de hashear** (JSON Canonicalization Scheme, RFC 8785)
  para que los hashes sean reproducibles en conciliaciones futuras.
- **Cadena de hashes (tamper-evident)**: cada registro incluye el hash del
  registro anterior (`prev_hash`), formando una cadena verificable.
- **Firma y cifrado con Vault Transit**: los registros se firman con una clave
  gestionada por Vault. Si por requisito legal hay que reproducir el contenido,
  se guarda **cifrado** (envelope encryption con Transit), nunca en claro.

Justificación: hash para conciliación/verificación sin almacenar PII en claro;
cadena + firma para no-repudio y evidencia de integridad.

### 3. Medición y facturación desacopladas (patrón Outbox)
- La transacción de negocio escribe el registro de auditoría y un evento de
  facturación en una tabla `outbox` dentro de la **misma transacción**.
- Un relay consume el outbox y alimenta la tabla de medición/facturación.
  Garantía at-least-once + idempotencia por `idempotency_key`.

Justificación: nunca perder un evento facturable; consistencia entre lo auditado
y lo facturado.

### 4. Outbound = Anti-Corruption Layer (ACL) / Facade
- Las aplicaciones de la empresa **NUNCA llaman APIs externas directamente**.
  Llaman al contrato canónico interno del hub.
- El hub mantiene un **adaptador por proveedor externo** que: traduce el
  contrato canónico al formato del proveedor, gestiona credenciales (en Vault),
  aplica resiliencia (resilience4j: timeout, retry, circuit breaker, bulkhead),
  cachea cuando está permitido (Redis), audita y mide.

Justificación: desacopla las apps internas del churn de las APIs externas
(agregar un proveedor = nuevo adaptador, cero cambios en las apps); centraliza
credenciales, observabilidad y conciliación.

### 5. Contratos y onboarding
- Contratos de API versionados (OpenAPI) por producto.
- Portal de desarrolladores para onboarding de partners, documentación y emisión
  de credenciales.

## Roles de módulos (a definir según evolución del proyecto)
| Rol                      | Responsabilidad                                                  |
|--------------------------|------------------------------------------------------------------|
| Edge / Gateway           | Terminación mTLS, enrutamiento inbound/outbound, rate limiting   |
| Servicio de identidad    | Registro de partners, clients Keycloak, orquestación PKI         |
| Núcleo del hub           | Rutas inbound a negocio, adaptadores outbound                    |
| Librería de auditoría    | Auditoría compartida, canonicalización, hash-chain, Vault Transit |
| Servicio de facturación  | Relay de outbox + medición + facturación                         |
| Frontend / portal        | Admin interno + portal de partners                               |

> Nota de decoupling: el código heredado de la base original conserva prefijos
> de paquetes y nombres de módulos del proyecto origen. Renombrarlos (paquetes,
> nombres de módulos, nombres de base de datos) es trabajo de decoupling
> documentado aparte y debe completarse antes de considerar este proyecto
> independiente de su base.

## Consecuencias
Positivas: seguridad defensa-en-profundidad; auditoría con valor legal;
facturación confiable; apps internas desacopladas de terceros; reutilización de
infra (Vault PKI+Transit, Keycloak, Consul, Redis).

Negativas / costos: complejidad operativa (CA y rotación de certs); latencia
adicional por el doble salto en outbound; el hash-chain exige cuidado en
concurrencia (serializar la escritura o particionar la cadena por partner); RFC
8705 requiere configuración fina de Keycloak y del gateway.

## Alternativas descartadas
- **mTLS sin binding al token**: más simple, pierde la protección contra robo de
  token → descartado por el requisito de facturación/no-repudio.
- **Apps internas llamando directo a terceros**: dispersa credenciales y rompe
  la auditoría centralizada → descartado.
- **Guardar payloads en claro**: inaceptable por PII → hash + cifrado Transit
  bajo demanda.
