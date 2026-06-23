package bo.com.sintesis.mdqr.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

import static org.assertj.core.api.Assertions.assertThat;

class NativeHintsTest {

    @Test
    @DisplayName("All required reflection hints are registered")
    void allHintsRegistered() {
        var hints = new RuntimeHints();
        new NativeHints.Registrar().registerHints(hints, getClass().getClassLoader());

        var reflection = RuntimeHintsPredicates.reflection();

        // Spring Cloud Vault
        assertThat(reflection.onType(org.springframework.cloud.vault.config.VaultProperties.class))
            .accepts(hints);

        // Spring Cloud Consul
        assertThat(reflection.onType(org.springframework.cloud.consul.ConsulProperties.class))
            .accepts(hints);
        assertThat(reflection.onType(org.springframework.cloud.consul.config.ConsulConfigProperties.class))
            .accepts(hints);
        assertThat(reflection.onType(org.springframework.cloud.consul.discovery.ConsulDiscoveryProperties.class))
            .accepts(hints);

        // Spring Cloud Gateway
        assertThat(reflection.onType(org.springframework.cloud.gateway.config.GatewayProperties.class))
            .accepts(hints);
        assertThat(reflection.onType(org.springframework.cloud.gateway.route.RouteDefinition.class))
            .accepts(hints);

        // Custom ApplicationProperties
        assertThat(reflection.onType(ApplicationProperties.class)).accepts(hints);
        assertThat(reflection.onType(ApplicationProperties.Keycloak.class)).accepts(hints);
        assertThat(reflection.onType(ApplicationProperties.Cors.class)).accepts(hints);
        assertThat(reflection.onType(ApplicationProperties.Gateway.class)).accepts(hints);

        // Jackson DTOs
        assertThat(reflection.onType(bo.com.sintesis.mdqr.gateway.service.dto.TokenResponse.class))
            .accepts(hints);
        assertThat(reflection.onType(bo.com.sintesis.mdqr.gateway.web.rest.request.LoginRequest.class))
            .accepts(hints);
    }
}
