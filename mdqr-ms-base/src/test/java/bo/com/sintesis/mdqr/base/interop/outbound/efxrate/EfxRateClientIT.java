package bo.com.sintesis.mdqr.base.interop.outbound.efxrate;

import bo.com.sintesis.mdqr.audit.hash.PayloadHasher;
import bo.com.sintesis.mdqr.audit.hub.HubAuditService;
import bo.com.sintesis.mdqr.base.config.ApplicationProperties;
import bo.com.sintesis.mdqr.base.interop.outbound.efxrate.config.EfxRateAutoConfiguration;
import bo.com.sintesis.mdqr.base.interop.outbound.efxrate.dto.EfxRateProviderRequest;
import bo.com.sintesis.mdqr.base.interop.outbound.efxrate.dto.EfxRateProviderResponse;
import bo.com.sintesis.mdqr.base.interop.outbound.efxrate.exception.ExchangeRateNotFoundException;
import bo.com.sintesis.mdqr.base.interop.outbound.efxrate.exception.ExchangeRateProviderException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.vault.core.VaultTemplate;
import java.math.BigDecimal;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests de integración del EfxRateClient contra WireMock embebido.
 *
 * Se carga únicamente el slice de contexto necesario para el adaptador
 * (EfxRateAutoConfiguration + sus colaboradores) sin arrancar el contexto
 * completo de la aplicación (sin JPA, sin Liquibase, sin Keycloak).
 *
 * WireMock se inicializa como campo estático para que el puerto esté disponible
 * cuando @DynamicPropertySource registra las propiedades (antes de que el
 * contexto de Spring arranque).
 *
 * @TestMethodOrder controla el orden porque el test del circuit breaker (Order=5)
 * se beneficia de los fallos acumulados en tests anteriores sobre el mismo contexto.
 */
@SpringBootTest(
        classes = EfxRateClientIT.TestSliceConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EfxRateClientIT {

    // ─── WireMock — inicialización estática para @DynamicPropertySource ───────
    // @DynamicPropertySource se invoca antes de @BeforeAll; WireMock debe estar
    // activo antes de que Spring arranque el contexto para poder registrar la URL.

    static final WireMockServer WIRE_MOCK;

    static {
        WIRE_MOCK = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        WIRE_MOCK.start();
        com.github.tomakehurst.wiremock.client.WireMock.configureFor(
                "localhost", WIRE_MOCK.port());
    }

    @AfterEach
    void limpiarStubs() {
        WIRE_MOCK.resetAll();
    }

    // ─── Propiedades dinámicas ─────────────────────────────────────────────────

    @DynamicPropertySource
    static void configurarPropiedades(DynamicPropertyRegistry registry) {
        registry.add("hub.outbound.efxrate.url",
                () -> "http://localhost:" + WIRE_MOCK.port());
        // Tiempos reducidos para que los tests sean rápidos
        registry.add("hub.outbound.efxrate.timeout-ms", () -> "3000");
        registry.add("hub.outbound.efxrate.resilience4j.retry-max-attempts", () -> "3");
        registry.add("hub.outbound.efxrate.resilience4j.retry-wait-ms", () -> "100");
        registry.add("hub.outbound.efxrate.resilience4j.cb-failure-rate-threshold", () -> "50");
        // cb-wait-duration alto para que el CB no se cierre automáticamente entre tests
        registry.add("hub.outbound.efxrate.resilience4j.cb-wait-duration-ms", () -> "60000");
        registry.add("hub.outbound.efxrate.resilience4j.bulkhead-max-concurrent", () -> "10");
        // Evitar que Spring intente leer Vault o Consul
        registry.add("spring.config.import", () -> "");
    }

    // ─── Slice de contexto ────────────────────────────────────────────────────

    /**
     * Contexto mínimo para probar EfxRateClient:
     * - EfxRateAutoConfiguration (crea EfxRateClient, EfxRateMapper, EfxRateAdapter)
     * - ApplicationProperties (requerido por EfxRateAutoConfiguration para fallback local)
     * - Mocks de VaultTemplate, HubAuditService, PayloadHasher y RedisTemplate
     */
    @Configuration
    @Import(EfxRateAutoConfiguration.class)
    @EnableConfigurationProperties({EfxRateProperties.class, ApplicationProperties.class})
    static class TestSliceConfig {

        @Bean
        @Primary
        VaultTemplate mockVaultTemplate() {
            VaultTemplate mockVault = mock(VaultTemplate.class);
            org.springframework.vault.support.VaultResponse vaultResponse =
                    new org.springframework.vault.support.VaultResponse();
            vaultResponse.setData(Map.of("api-key", "test-api-key"));
            when(mockVault.read("mdqr-decode/data/efxrate/api-key"))
                    .thenReturn(vaultResponse);
            return mockVault;
        }

        @Bean
        HubAuditService mockHubAuditService() {
            HubAuditService mock = mock(HubAuditService.class);
            when(mock.record(org.mockito.ArgumentMatchers.any())).thenReturn("chain-hash-test");
            return mock;
        }

        @Bean
        PayloadHasher mockPayloadHasher() {
            PayloadHasher mock = mock(PayloadHasher.class);
            when(mock.hash(anyString())).thenReturn("a".repeat(64));
            return mock;
        }

        @Bean("efxRateStringRedisTemplate")
        @SuppressWarnings("unchecked")
        RedisTemplate<String, String> mockRedisTemplate() {
            // Caché siempre vacía; los tests del client se centran en la capa HTTP
            RedisTemplate<String, String> mockTemplate = mock(RedisTemplate.class);
            ValueOperations<String, String> mockOps = mock(ValueOperations.class);
            when(mockTemplate.opsForValue()).thenReturn(mockOps);
            when(mockOps.get(anyString())).thenReturn(null);
            return mockTemplate;
        }
    }

    // ─── Constantes de test ───────────────────────────────────────────────────

    private static final String RESPUESTA_200 = """
            {
              "cod_moneda_origen":  "BOB",
              "cod_moneda_destino": "UFV",
              "fecha_vigencia":     "20260622",
              "valor":              2.52017,
              "fuente":             "BCB",
              "timestamp_utc":      "2026-06-22T00:00:00Z"
            }
            """;

    private static final String ERROR_404 = """
            { "codigo": "ERR_RATE_001", "mensaje": "Tipo de cambio no disponible para la fecha" }
            """;

    private static final EfxRateProviderRequest REQUEST_BOB_UFV =
            new EfxRateProviderRequest("BOB", "UFV", "20260622");

    // ─── Bean bajo test ───────────────────────────────────────────────────────

    @Autowired
    private EfxRateClient efxRateClient;

    // ─── Tests ────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("Debería parsear correctamente la respuesta 200 del proveedor")
    void shouldFetchRateSuccessfully() {
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/v1/rate"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(RESPUESTA_200)));

        EfxRateProviderResponse response = efxRateClient.fetchRate(REQUEST_BOB_UFV);

        assertThat(response).isNotNull();
        assertThat(response.codMonedaOrigen()).isEqualTo("BOB");
        assertThat(response.codMonedaDestino()).isEqualTo("UFV");
        assertThat(response.valor()).isEqualByComparingTo(new BigDecimal("2.52017"));
        assertThat(response.fuente()).isEqualTo("BCB");
        assertThat(response.fechaVigencia()).isEqualTo("20260622");
    }

    @Test
    @Order(2)
    @DisplayName("Debería lanzar ExchangeRateNotFoundException cuando el proveedor responde 404")
    void shouldThrowNotFoundOnProviderError() {
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/v1/rate"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ERROR_404)));

        assertThatThrownBy(() -> efxRateClient.fetchRate(REQUEST_BOB_UFV))
                .isInstanceOf(ExchangeRateNotFoundException.class)
                .hasMessageContaining("404");

        // NotFoundException no dispara retry — exactamente 1 llamada a WireMock
        WIRE_MOCK.verify(1, getRequestedFor(urlPathEqualTo("/v1/rate")));
    }

    @Test
    @Order(3)
    @DisplayName("Debería lanzar ExchangeRateProviderException y reintentar cuando el proveedor responde 503")
    void shouldThrowProviderExceptionOn5xx() {
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/v1/rate"))
                .willReturn(aResponse()
                        .withStatus(503)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": \"servicio no disponible\"}")));

        assertThatThrownBy(() -> efxRateClient.fetchRate(REQUEST_BOB_UFV))
                .isInstanceOf(ExchangeRateProviderException.class);

        // Con retry-max-attempts=3, se intentaron exactamente 3 veces
        WIRE_MOCK.verify(3, getRequestedFor(urlPathEqualTo("/v1/rate")));
    }

    @Test
    @Order(4)
    @DisplayName("Debería reintentar en fallos transitorios y tener éxito en el 3er intento")
    void shouldRetryOnTransientFailure() {
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/v1/rate"))
                .inScenario("retry-scenario")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse()
                        .withStatus(503)
                        .withBody("{\"error\": \"transitorio-1\"}"))
                .willSetStateTo("segundo-intento"));

        WIRE_MOCK.stubFor(get(urlPathEqualTo("/v1/rate"))
                .inScenario("retry-scenario")
                .whenScenarioStateIs("segundo-intento")
                .willReturn(aResponse()
                        .withStatus(503)
                        .withBody("{\"error\": \"transitorio-2\"}"))
                .willSetStateTo("tercer-intento"));

        WIRE_MOCK.stubFor(get(urlPathEqualTo("/v1/rate"))
                .inScenario("retry-scenario")
                .whenScenarioStateIs("tercer-intento")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(RESPUESTA_200)));

        EfxRateProviderResponse response = efxRateClient.fetchRate(REQUEST_BOB_UFV);

        assertThat(response).isNotNull();
        assertThat(response.codMonedaOrigen()).isEqualTo("BOB");

        // Exactamente 3 llamadas: 2 fallidas + 1 exitosa
        WIRE_MOCK.verify(3, getRequestedFor(urlPathEqualTo("/v1/rate")));
    }

    @Test
    @Order(5)
    @DisplayName("Debería abrir el circuit breaker tras suficientes fallos consecutivos")
    void shouldOpenCircuitBreakerAfterFailures() {
        // cb-failure-rate-threshold=50, cb-minimum-number-of-calls=10 (propiedad configurada).
        // Order=3 contribuyó 1 fallo, Order=4 contribuyó 1 éxito → 2 eventos previos.
        // Con estas iteraciones superamos las 10 llamadas mínimas con >50% de fallos.
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/v1/rate"))
                .willReturn(aResponse()
                        .withStatus(503)
                        .withBody("{\"error\": \"fallo-cb\"}")));

        boolean cbAbio = false;
        for (int i = 0; i < 30; i++) {
            try {
                efxRateClient.fetchRate(REQUEST_BOB_UFV);
            } catch (CallNotPermittedException e) {
                cbAbio = true;
                break;
            } catch (ExchangeRateProviderException e) {
                // Fallo contabilizado en el CB; continuar acumulando
            }
        }

        assertThat(cbAbio)
                .as("El circuit breaker debe haberse abierto tras suficientes fallos consecutivos")
                .isTrue();
    }
}
