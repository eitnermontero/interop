package bo.com.sintesis.hub.auth.config;

import lombok.RequiredArgsConstructor;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.RealmResource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@RequiredArgsConstructor
public class KeycloakAdminConfiguration {

    private final ApplicationProperties props;

    /**
     * RestClient pre-configured with the Keycloak realm base URL.
     * Used by AuthService to proxy login / refresh / logout to the
     * realm's OpenID Connect endpoints.
     */
    @Bean
    public RestClient keycloakTokenClient() {
        String baseUrl = props.keycloak().authServerUrl() + "/realms/" + props.keycloak().realm();
        return RestClient.builder().baseUrl(baseUrl).build();
    }

    @Bean
    public Keycloak keycloakAdminClient() {
        var admin = props.keycloak().admin();

        var builder = KeycloakBuilder.builder()
            .serverUrl(admin.authServerUrl())
            .realm(admin.realm())
            .clientId(admin.clientId());

        String grantType = admin.grantType() == null || admin.grantType().isBlank()
            ? OAuth2Constants.CLIENT_CREDENTIALS
            : admin.grantType();
        builder.grantType(grantType);

        if (admin.clientSecret() != null && !admin.clientSecret().isBlank()) {
            builder.clientSecret(admin.clientSecret());
        }
        return builder.build();
    }

    @Bean
    public RealmResource keycloakRealmResource(Keycloak keycloak) {
        return keycloak.realm(props.keycloak().realm());
    }
}
