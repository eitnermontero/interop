package bo.com.sintesis.hub.gateway.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class OpenApiConfiguration {

    private final ApplicationProperties properties;

    @Bean
    public OpenAPI openAPI() {
        var kc = properties.keycloak();
        var oidcUrl = kc.externalUrl() + "/realms/" + kc.realm() + "/.well-known/openid-configuration";

        return new OpenAPI()
            .info(new Info()
                .title("MWC Gateway API")
                .description("Hub de interoperabilidad — endpoints del gateway")
                .version("1.0"))
            .components(new Components()
                .addSecuritySchemes("openid", new SecurityScheme()
                    .type(SecurityScheme.Type.OPENIDCONNECT)
                    .openIdConnectUrl(oidcUrl)))
            .addSecurityItem(new SecurityRequirement().addList("openid"));
    }
}
