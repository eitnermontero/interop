# Configuración de Seguridad y Auditoría JPA

> ⚠️ **Documento parcialmente desactualizado** (contiene contenido legacy pre-ADR-0004/rename 2026-07-03).
> Fuente de verdad actual: `CLAUDE.md` y `docs/adr/` (ADR-0005/0006/0007).

## 📋 Problema Encontrado

Al intentar crear certificados con el endpoint `POST /api/certificates`, se obtenía el siguiente error:

```
ERROR: null value in column "created_by" of relation "certificate" violates not-null constraint
```

**Causa Raíz:**
- Las entidades usan `AbstractAuditingEntity` con anotaciones `@CreatedBy` y `@LastModifiedBy`
- JPA Auditing estaba habilitado (`@EnableJpaAuditing`)
- **Faltaba** el bean `AuditorAware` que proporciona el usuario actual
- Sin este bean, JPA no podía establecer el valor de `created_by`

---

## ✅ Solución Implementada (Desarrollo)

### 1. Creado `AuditingConfiguration.java`

**Ubicación:** `ulqr-ms-decrypt/src/main/java/bo/com/sintesis/ulqr/config/AuditingConfiguration.java`

```java
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorProvider")
public class AuditingConfiguration {

    @Bean
    public AuditorAware<String> auditorProvider() {
        return new AuditorAwareImpl();
    }

    public static class AuditorAwareImpl implements AuditorAware<String> {
        @Override
        public Optional<String> getCurrentAuditor() {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

            // ⚠️ MODO DESARROLLO: Sin autenticación, usa "system"
            if (authentication == null || !authentication.isAuthenticated()) {
                return Optional.of("system");
            }

            String principal = authentication.getName();
            if (principal == null || principal.equals("anonymousUser")) {
                return Optional.of("system");
            }

            return Optional.of(principal);
        }
    }
}
```

**Características:**
- ✅ En desarrollo (sin OAuth2): Retorna `"system"` como auditor
- ✅ En producción (con OAuth2): Retorna el `username` del JWT
- ✅ Funciona en ambos escenarios sin cambios de código

### 2. Actualizado `CertificateResource.java`

Mejorada la validación del `Principal` en todos los endpoints:

```java
// ANTES (potencialmente null)
String userId = principal != null ? principal.getName() : "system";

// AHORA (null-safe)
String userId = (principal != null && principal.getName() != null)
    ? principal.getName()
    : "system";
```

**Endpoints modificados:**
- `uploadCertificate()`
- `validateCertificate()`
- `activateCertificate()`
- `deactivateCertificate()`
- `revokeCertificate()`
- `replaceCertificate()`

### 3. Limpiado `UlqrDecryptApplication.java`

Removida la anotación `@EnableJpaAuditing` (ahora está en `AuditingConfiguration`):

```java
// ANTES
@EnableJpaAuditing
public class UlqrDecryptApplication {

// AHORA
public class UlqrDecryptApplication {
```

---

## 🔐 Habilitación de Seguridad OAuth2 (Producción)

### ⚠️ IMPORTANTE: Pasos para Producción

Cuando se habilite OAuth2 con Keycloak en producción:

#### 1. Habilitar `@EnableMethodSecurity`

**Archivo:** `ulqr-ms-decrypt/src/main/java/bo/com/sintesis/ulqr/config/SecurityConfiguration.java`

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)  // ✅ DESCOMENTAR ESTA LÍNEA
public class SecurityConfiguration {
```

#### 2. Descomentar OAuth2 Resource Server

**Mismo archivo - Método `securityFilterChain()`:**

```java
http
    // ✅ DESCOMENTAR ESTE BLOQUE
    .oauth2ResourceServer(oauth2 -> oauth2
        .jwt(jwt -> jwt
            .jwtAuthenticationConverter(jwtAuthenticationConverter())
        )
    )

    .authorizeHttpRequests(authz -> authz
        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**").permitAll()

        // ✅ DESCOMENTAR ESTAS LÍNEAS (remover el .anyRequest().permitAll())
        .requestMatchers("/api/qr/decode").hasRole("API_CLIENT")
        .requestMatchers("/api/qr/audits/**").hasAnyRole("ADMIN", "AUDITOR")
        .requestMatchers("/api/certificates/**").hasRole("ADMIN")
        .anyRequest().authenticated()  // ✅ CAMBIAR de .permitAll() a .authenticated()
    )
```

#### 3. Verificar Configuración OAuth2

**Archivo:** `ulqr-ms-decrypt/src/main/resources/application-prod.yml`

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://keycloak.prod.sintesis.com.bo/realms/middleware-qr
          jwk-set-uri: https://keycloak.prod.sintesis.com.bo/realms/middleware-qr/protocol/openid-connect/certs
```

#### 4. Configurar Roles en Keycloak

Crear los siguientes roles en Keycloak realm `middleware-qr`:

- **ADMIN**: Gestión completa de certificados, activación, revocación
- **OPERATOR**: Lectura de certificados, validación
- **API_CLIENT**: Solo desencriptación de QRs
- **AUDITOR**: Consulta de logs de auditoría

#### 5. Verificar que `AuditorAware` Funciona con OAuth2

El bean `AuditorAware` ya está preparado para OAuth2:

```java
// Extrae automáticamente el username del JWT
Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
String principal = authentication.getName();  // ✅ Obtendrá el "preferred_username" del JWT
```

**No requiere cambios** - funcionará automáticamente cuando se habilite OAuth2.

---

## 🧪 Testing en Producción

### Con Token JWT

```bash
# 1. Obtener token de Keycloak
TOKEN=$(curl -X POST https://keycloak.prod.sintesis.com.bo/realms/middleware-qr/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=ulqr-backend" \
  -d "client_secret=YOUR_SECRET" \
  -d "grant_type=client_credentials" \
  -d "scope=openid" | jq -r '.access_token')

# 2. Usar el token en las requests
curl -X POST https://api.prod.sintesis.com.bo/api/certificates \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{...}'
```

### Verificar Auditoría

```bash
# Ver que created_by tiene el username del JWT (no "system")
curl -X GET https://api.prod.sintesis.com.bo/api/certificates/1 \
  -H "Authorization: Bearer $TOKEN" | jq '.createdBy'

# Debería retornar: "admin@sintesis.com" (o el username del JWT)
# NO debería retornar: "system"
```

---

## 📊 Diagrama de Flujo

### Desarrollo (Actual)

```
Request → SecurityFilterChain (permitAll)
       → Controller (Principal = null)
       → Service
       → Repository.save()
       → JPA Auditing → AuditorAware
                      → SecurityContext (no auth)
                      → Retorna "system"
       → DB: created_by = "system" ✅
```

### Producción (Futuro con OAuth2)

```
Request + JWT → SecurityFilterChain (OAuth2 enabled)
             → JWT validation
             → Extract roles from JWT
             → Controller (Principal = JwtAuthenticationToken)
             → Service
             → Repository.save()
             → JPA Auditing → AuditorAware
                           → SecurityContext (authenticated)
                           → Retorna username del JWT
             → DB: created_by = "john.doe@sintesis.com" ✅
```

---

## ✅ Checklist de Producción

- [ ] Descomentar `@EnableMethodSecurity` en `SecurityConfiguration.java`
- [ ] Descomentar `.oauth2ResourceServer()` en `securityFilterChain()`
- [ ] Cambiar `.anyRequest().permitAll()` a `.anyRequest().authenticated()`
- [ ] Descomentar las reglas de autorización por rol
- [ ] Configurar `issuer-uri` y `jwk-set-uri` en `application-prod.yml`
- [ ] Crear roles en Keycloak: ADMIN, OPERATOR, API_CLIENT, AUDITOR
- [ ] Asignar roles a usuarios/clientes
- [ ] Probar endpoints con tokens JWT
- [ ] Verificar que `created_by` contiene el username del JWT (no "system")
- [ ] Verificar que `@PreAuthorize` funciona correctamente
- [ ] Documentar proceso de obtención de tokens para clientes API

---

## 🔗 Referencias

- **SecurityConfiguration.java**: `ulqr-ms-decrypt/src/main/java/bo/com/sintesis/ulqr/config/SecurityConfiguration.java`
- **AuditingConfiguration.java**: `ulqr-ms-decrypt/src/main/java/bo/com/sintesis/ulqr/config/AuditingConfiguration.java`
- **AbstractAuditingEntity.java**: `ulqr-ms-decrypt/src/main/java/bo/com/sintesis/ulqr/domain/AbstractAuditingEntity.java`
- **Spring Security OAuth2**: https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html
- **JPA Auditing**: https://docs.spring.io/spring-data/jpa/reference/auditing.html

---

## 📝 Notas Adicionales

1. **NO cambiar** la lógica de `AuditorAware` - ya está preparada para ambos escenarios
2. **NO remover** el fallback a "system" - es necesario para desarrollo local
3. **SÍ probar** que los roles funcionan correctamente antes de deployar a producción
4. **SÍ actualizar** la documentación de la API con ejemplos de autenticación JWT
5. **SÍ coordinar** con el equipo de DevOps para configurar Keycloak en producción

---

**Fecha de implementación:** 01 Junio 2026
**Responsable:** Backend Team
**Estado:** ✅ Funcionando en desarrollo (sin OAuth2)
**Pendiente:** Habilitar OAuth2 en producción
