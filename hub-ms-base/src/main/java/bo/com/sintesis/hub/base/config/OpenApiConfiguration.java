package bo.com.sintesis.hub.base.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@Slf4j
public class OpenApiConfiguration {

    @Value("${spring.application.name:hub-ms-base}")
    private String applicationName;

    @Value("${server.port:8081}")
    private String serverPort;

    /**
     * Configura el bean OpenAPI para Swagger UI.
     *
     * @return OpenAPI configurado
     */
    @Bean
    public OpenAPI customOpenAPI() {
        log.info("Configurando OpenAPI/Swagger UI");

        return new OpenAPI()
            .info(apiInfo())
            .servers(apiServers())
            .components(apiComponents());
    }

    /**
     * Información general del API.
     */
    private Info apiInfo() {
        return new Info()
            .title("HUB — Hub de Interoperabilidad")
            .version("1.0.0")
            .description("""
                Hub de interoperabilidad de casos penales (POL/FELCN ↔ MP).

                ## Características

                - **Inbound**: Exposición de APIs internas a partners con mTLS + RFC 8705
                - **Outbound**: Llamadas a proveedores externos a través de adaptadores ACL
                - **Auditoría con cadena de hashes**: SHA-256 encadenado + firma Vault Transit
                - **Outbox transaccional**: Garantía at-least-once para eventos facturables
                - **Seguridad OAuth2**: JWT Bearer con Keycloak, mTLS partner, RBAC

                ## Roles

                - **API_CLIENT**: Acceso a endpoints de negocio (partners)
                - **ADMIN**: Administración del hub
                - **AUDITOR**: Consulta de auditorías (solo lectura)

                ## Autenticación

                Todas las APIs requieren autenticación con JWT Bearer token obtenido desde Keycloak.

                Para probar en Swagger:
                1. Obtén un token JWT de Keycloak
                2. Click en "Authorize" arriba
                3. Ingresa: `Bearer {tu_token_jwt}`
                4. Click en "Authorize" y cierra el modal
                """)
            .contact(apiContact())
            .license(apiLicense());
    }

    /**
     * Información de contacto.
     */
    private Contact apiContact() {
        return new Contact()
            .name("Equipo de Desarrollo - Sintesis")
            .email("soporte@sintesis.com.bo")
            .url("https://sintesis.com.bo");
    }

    /**
     * Información de licencia.
     */
    private License apiLicense() {
        return new License()
            .name("Propietario")
            .url("https://sintesis.com.bo/licenses");
    }

    /**
     * Servidores disponibles.
     */
    private List<Server> apiServers() {
        return List.of(
            new Server()
                .url("http://localhost:" + serverPort)
                .description("Servidor Local de Desarrollo"),
            new Server()
                .url("https://test.sintesis.com.bo/hub")
                .description("Servidor de Testing"),
            new Server()
                .url("https://api.sintesis.com.bo/hub")
                .description("Servidor de Producción")
        );
    }

    /**
     * Componentes de seguridad.
     */
    private Components apiComponents() {
        return new Components()
            .addSecuritySchemes("bearer-jwt", securitySchemeJWT());
    }

    /**
     * Esquema de seguridad JWT Bearer.
     */
    private SecurityScheme securitySchemeJWT() {
        return new SecurityScheme()
            .name("bearer-jwt")
            .type(SecurityScheme.Type.HTTP)
            .scheme("bearer")
            .bearerFormat("JWT")
            .in(SecurityScheme.In.HEADER)
            .description("""
                Token JWT obtenido desde Keycloak.

                **Para obtener un token:**

                ```bash
                curl -X POST 'http://localhost:8180/realms/hub-admin/protocol/openid-connect/token' \\
                  -H 'Content-Type: application/x-www-form-urlencoded' \\
                  -d 'grant_type=client_credentials' \\
                  -d 'client_id=hubadminservice' \\
                  -d 'client_secret=hubadminservice-secret'
                ```

                **Respuesta:**
                ```json
                {
                  "access_token": "eyJhbGciOiJSUzI1NiIs...",
                  "expires_in": 300,
                  "token_type": "Bearer"
                }
                ```

                **Usar en Swagger:**
                Ingresa el token completo en el campo de arriba (Authorize).
                """);
    }
}
