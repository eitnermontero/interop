package bo.com.sintesis.hub.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "application")
public record ApplicationProperties(
    Keycloak keycloak,
    String gatewayUrl,
    Audit audit
) {
    public record Keycloak(
        String authServerUrl,
        String realm,
        String clientId,
        Credentials credentials,
        AdminClient admin
    ) {
        public record Credentials(String secret) {}

        /** Service-account credentials used to call Keycloak Admin API (client_credentials). */
        public record AdminClient(
            String authServerUrl,
            String realm,
            String clientId,
            String clientSecret,
            String grantType
        ) {}
    }

    /**
     * Audit ingestion settings.
     * - keycloakSecret: shared secret for the push webhook (retained; push endpoint still exists)
     * - exportMaxRows: cap for /audit/export to protect memory
     * - keycloakPoll: Keycloak Admin API polling settings for LOGIN/LOGOUT event ingestion
     */
    public record Audit(
        String keycloakSecret,
        Integer exportMaxRows,
        KeycloakPoll keycloakPoll
    ) {
        /** Polling config for Keycloak Admin API events. */
        public record KeycloakPoll(
            boolean enabled,
            long intervalMs,
            int maxEventsPerCycle
        ) {}
    }
}
