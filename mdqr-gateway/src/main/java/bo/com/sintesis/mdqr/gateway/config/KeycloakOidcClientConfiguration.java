package bo.com.sintesis.mdqr.gateway.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import java.util.Map;

/**
 * Builds the OIDC client registration in code to avoid startup OIDC discovery over
 * the external Keycloak URL, which is unreachable from inside Docker containers.
 *
 * - Server-to-server calls (token, userinfo, jwks) use auth-server-url (internal).
 * - Browser-facing calls (authorization, logout) use external-url.
 */
@Configuration
@RequiredArgsConstructor
public class KeycloakOidcClientConfiguration {

    private final ApplicationProperties properties;

    @Bean
    public ReactiveClientRegistrationRepository clientRegistrationRepository() {
        var kc = properties.keycloak();
        String internal = kc.authServerUrl() + "/realms/" + kc.realm() + "/protocol/openid-connect";
        String external = kc.externalUrl() + "/realms/" + kc.realm() + "/protocol/openid-connect";

        ClientRegistration registration = ClientRegistration
            .withRegistrationId("oidc")
            .clientId(kc.resource())
            .clientSecret(kc.credentials().secret())
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/oidc")
            .scope("openid", "profile", "email")
            .authorizationUri(external + "/auth")
            .tokenUri(internal + "/token")
            .userInfoUri(internal + "/userinfo")
            .jwkSetUri(internal + "/certs")
            .userNameAttributeName("preferred_username")
            .providerConfigurationMetadata(Map.of(
                "end_session_endpoint", external + "/logout",
                "issuer", kc.externalUrl() + "/realms/" + kc.realm()
            ))
            .clientName("Keycloak")
            .build();

        return new InMemoryReactiveClientRegistrationRepository(registration);
    }
}
