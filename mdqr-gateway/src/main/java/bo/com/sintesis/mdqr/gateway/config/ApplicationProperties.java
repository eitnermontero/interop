package bo.com.sintesis.mdqr.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

@ConfigurationProperties(prefix = "application")
public record ApplicationProperties(
    Keycloak keycloak,
    Keycloak partnerKeycloak,
    Cors cors,
    Gateway gateway,
    @DefaultValue Redis redis
) {
    public record Keycloak(
        String authServerUrl,
        String externalUrl,
        String realm,
        String resource,
        Credentials credentials
    ) {
        public record Credentials(String secret) {}
    }

    public record Cors(
        List<String> allowedOrigins,
        String allowedMethods,
        String allowedHeaders,
        String exposedHeaders,
        boolean allowCredentials,
        long maxAge
    ) {}

    public record Gateway(
        RateLimit rateLimit,
        String cartServiceName
    ) {
        public record RateLimit(long defaultRequestsPerMinute) {}
    }

    public record Redis(@DefaultValue Cluster cluster) {
        public record Cluster(
            @DefaultValue("false") boolean enabled,
            @DefaultValue List<String> nodes
        ) {}
    }
}
