package bo.com.sintesis.mdqr.base.config;

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

    @Value("${spring.application.name:mdqr-ms-base}")
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
            .title("MDQR - QR Decryption Service")
            .version("1.0.0")
            .description("""
                API para desencriptación de códigos QR interoperables.

                ## Características

                - **Desencriptación RSA**: Desencripta códigos QR usando certificados públicos
                - **Gestión de Certificados**: Importar, listar y revocar certificados digitales
                - **Auditoría Completa**: Registro de todas las operaciones con filtros avanzados
                - **Caché Inteligente**: Redis cache con TTL configurable (24h default)
                - **Seguridad OAuth2**: Autenticación JWT con Keycloak y RBAC

                ## Roles

                - **API_CLIENT**: Puede desencriptar QR
                - **ADMIN**: Gestión completa de certificados y auditorías
                - **AUDITOR**: Consulta de auditorías (solo lectura)

                ## Formato QR

                Los códigos QR deben tener el formato: `{encrypted_base64}|{certificate_code}`

                Ejemplo: `qERbt4N7AL96...==|1C302639F6F0...`

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
                .url("https://test.sintesis.com.bo/mdqr")
                .description("Servidor de Testing"),
            new Server()
                .url("https://api.sintesis.com.bo/mdqr")
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
                curl -X POST 'http://localhost:8180/realms/mdqr-admin/protocol/openid-connect/token' \\
                  -H 'Content-Type: application/x-www-form-urlencoded' \\
                  -d 'grant_type=client_credentials' \\
                  -d 'client_id=mdqradminservice' \\
                  -d 'client_secret=mdqradminservice-secret'
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
