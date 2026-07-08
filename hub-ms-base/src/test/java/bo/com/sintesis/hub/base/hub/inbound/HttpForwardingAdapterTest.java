package bo.com.sintesis.hub.base.hub.inbound;

import bo.com.sintesis.hub.base.hub.inbound.config.HubInteropProperties;
import bo.com.sintesis.hub.base.hub.inbound.port.ForwardResult;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitarios de {@link HttpForwardingAdapter} contra WireMock embebido, sin contexto
 * Spring — mismo patrón que {@code EfxRateClientIT}.
 *
 * <p>Cubre la rama nueva del reenvío GET (sin body) y confirma que el comportamiento
 * existente de POST (con body) no se vio afectado por el cambio.
 */
@DisplayName("HttpForwardingAdapter — reenvío GET sin body vs POST con body")
class HttpForwardingAdapterTest {

    private static WireMockServer wireMock;

    private HttpForwardingAdapter adapter;
    private HubInteropProperties.ConnectorProps connector;

    @BeforeAll
    static void iniciarWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        configureFor("localhost", wireMock.port());
    }

    @AfterAll
    static void detenerWireMock() {
        wireMock.stop();
    }

    @AfterEach
    void limpiarStubs() {
        wireMock.resetAll();
    }

    @BeforeEach
    void setUp() {
        adapter = new HttpForwardingAdapter();
        connector = new HubInteropProperties.ConnectorProps();
        connector.setBaseUrl("http://localhost:" + wireMock.port());
        connector.setTimeoutMs(3000);
    }

    @Test
    @DisplayName("method=GET reenvía sin body al destino")
    void forward_conMethodGet_noEnviaBody() {
        wireMock.stubFor(get(urlEqualTo("/catalogos/unidades"))
                .withRequestBody(absent())
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"unidades\":[{\"id\":1}]}")));

        HubInteropProperties.ApiProps api = apiProps("GET", "/catalogos/unidades");

        ForwardResult result = adapter.forward(
                "wiremock-catalogo", connector, api, Map.of(), UUID.randomUUID().toString());

        assertThat(result.ok()).isTrue();
        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.data()).containsKey("unidades");

        wireMock.verify(1, getRequestedFor(urlEqualTo("/catalogos/unidades")));
    }

    @Test
    @DisplayName("method=GET propaga X-Correlation-ID aunque no envíe body")
    void forward_conMethodGet_propagaCorrelationId() {
        String correlationId = UUID.randomUUID().toString();
        wireMock.stubFor(get(urlEqualTo("/catalogos/unidades"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}")));

        HubInteropProperties.ApiProps api = apiProps("GET", "/catalogos/unidades");

        adapter.forward("wiremock-catalogo", connector, api, Map.of(), correlationId);

        wireMock.verify(1, getRequestedFor(urlEqualTo("/catalogos/unidades"))
                .withHeader("X-Correlation-ID", com.github.tomakehurst.wiremock.client.WireMock.equalTo(correlationId)));
    }

    @Test
    @DisplayName("method=POST (comportamiento existente) sí envía el payload como body")
    void forward_conMethodPost_siEnviaBody() {
        wireMock.stubFor(post(urlEqualTo("/denuncias"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":123}")));

        HubInteropProperties.ApiProps api = apiProps("POST", "/denuncias");
        Map<String, Object> payload = Map.of("codigo", "D-001");

        ForwardResult result = adapter.forward(
                "wiremock-denuncias", connector, api, payload, UUID.randomUUID().toString());

        assertThat(result.ok()).isTrue();
        assertThat(result.httpStatus()).isEqualTo(201);

        wireMock.verify(1, postRequestedFor(urlEqualTo("/denuncias"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath("$.codigo")));
    }

    private static HubInteropProperties.ApiProps apiProps(String method, String targetPath) {
        HubInteropProperties.ApiProps api = new HubInteropProperties.ApiProps();
        api.setMethod(method);
        api.setTargetPath(targetPath);
        return api;
    }
}
