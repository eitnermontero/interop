package bo.com.sintesis.mdqr.base.config;

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

        // Custom ApplicationProperties
        assertThat(reflection.onType(ApplicationProperties.class)).accepts(hints);
        assertThat(reflection.onType(ApplicationProperties.Keycloak.class)).accepts(hints);
        assertThat(reflection.onType(ApplicationProperties.Genesis.class)).accepts(hints);
        assertThat(reflection.onType(ApplicationProperties.ExchangeRate.class)).accepts(hints);
        assertThat(reflection.onType(ApplicationProperties.Payment.class)).accepts(hints);

        // Jackson DTOs
        assertThat(reflection.onType(bo.com.sintesis.mdqr.base.service.dto.TokenResponse.class))
            .accepts(hints);
        assertThat(reflection.onType(bo.com.sintesis.mdqr.base.integration.currencyengine.dto.ExchangeRateDto.class))
            .accepts(hints);

        // JPA Entities
        assertThat(reflection.onType(bo.com.sintesis.mdqr.base.domain.Partner.class)).accepts(hints);
        assertThat(reflection.onType(bo.com.sintesis.mdqr.base.domain.PaymentTransaction.class)).accepts(hints);
        assertThat(reflection.onType(bo.com.sintesis.mdqr.base.domain.ApiKeyRegistry.class)).accepts(hints);
    }
}
