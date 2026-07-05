package bo.com.sintesis.hub.auth.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
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

    @Value("${server.port:8083}")
    private String serverPort;

    @Bean
    public OpenAPI customOpenAPI() {
        log.info("Configurando OpenAPI/Swagger UI");

        return new OpenAPI()
            .info(apiInfo())
            .servers(apiServers())
            .components(apiComponents())
            .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"));
    }

    private Info apiInfo() {
        return new Info()
            .title("HUB - Admin Service API")
            .version("1.0.0")
            .description("""
                API de administración del sistema HUB de interoperabilidad.

                ## Módulos

                - **Autenticación**: Login/logout/refresh con Keycloak, perfil del usuario autenticado
                - **Usuarios**: Gestión CRUD de usuarios del sistema en Keycloak
                - **Roles**: Definición y asignación de roles RBAC
                - **Menús**: Árbol de navegación por rol
                - **Acciones**: Permisos granulares por módulo
                - **Auditoría**: Registro de todas las operaciones administrativas

                ## Autenticación

                Todas las APIs requieren token JWT Bearer del realm `hub-admin` de Keycloak.

                Para probar en Swagger:
                1. Obtén un token de Keycloak:
                ```bash
                curl -X POST 'http://localhost:8180/realms/hub-admin/protocol/openid-connect/token' \\
                  -H 'Content-Type: application/x-www-form-urlencoded' \\
                  -d 'grant_type=client_credentials&client_id=hubadminservice&client_secret=hubadminservice-secret'
                ```
                2. Click en **Authorize** → ingresa el token completo → **Authorize**
                """)
            .contact(new Contact()
                .name("Equipo de Desarrollo - Sintesis")
                .email("soporte@sintesis.com.bo")
                .url("https://sintesis.com.bo"))
            .license(new License()
                .name("Propietario")
                .url("https://sintesis.com.bo/licenses"));
    }

    private List<Server> apiServers() {
        return List.of(
            new Server()
                .url("http://localhost:" + serverPort)
                .description("Servidor Local de Desarrollo"),
            new Server()
                .url("http://localhost:8080/services/hubadminservice")
                .description("Vía Gateway Local")
        );
    }

    private Components apiComponents() {
        return new Components()
            .addSecuritySchemes("bearer-jwt", new SecurityScheme()
                .name("bearer-jwt")
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .in(SecurityScheme.In.HEADER)
                .description("Token JWT del realm hub-admin de Keycloak."));
    }
}
