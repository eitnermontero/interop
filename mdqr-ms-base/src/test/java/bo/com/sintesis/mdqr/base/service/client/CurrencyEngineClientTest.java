package bo.com.sintesis.mdqr.base.service.client;

import bo.com.sintesis.mdqr.base.integration.currencyengine.CurrencyEngineClient;
import bo.com.sintesis.mdqr.base.integration.currencyengine.dto.ExchangeRateDto;
import bo.com.sintesis.mdqr.base.web.rest.errors.ExchangeRateUnavailableException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrencyEngineClientTest {

    private static WireMockServer wireMock;
    private CurrencyEngineClient client;

    private static final String PRODUCT_CODE = "CARRITO_SUS";

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        client = new CurrencyEngineClient(
            wireMock.baseUrl(),
            PRODUCT_CODE,
            5000,
            5
        );
    }

    @Test
    @DisplayName("Returns exchange rate on successful 200 response")
    void getCurrentRate_success() {
        String now = Instant.now().toString();
        wireMock.stubFor(get(urlPathEqualTo("/currency-engine/api/v1/products/" + PRODUCT_CODE))
            .willReturn(okJson("""
                {
                    "name": "CARRITO_SUS",
                    "bidPrice": 6.960000000000,
                    "askPrice": 7.040000000000,
                    "bidSpreadPercent": 0.01,
                    "askSpreadPercent": 0.01,
                    "currency": "BOB",
                    "symbol": "BOB/USDT",
                    "lastUpdated": "%s"
                }
                """.formatted(now))));

        ExchangeRateDto rate = client.getCurrentRate();

        assertThat(rate).isNotNull();
        assertThat(rate.getBidPrice()).isEqualByComparingTo("6.96");
        assertThat(rate.getAskPrice()).isEqualByComparingTo("7.04");
        assertThat(rate.getName()).isEqualTo("CARRITO_SUS");

        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/currency-engine/api/v1/products/" + PRODUCT_CODE)));
    }

    @Test
    @DisplayName("Throws ExchangeRateUnavailableException on HTTP 500")
    void getCurrentRate_serverError() {
        wireMock.stubFor(get(urlPathEqualTo("/currency-engine/api/v1/products/" + PRODUCT_CODE))
            .willReturn(serverError().withBody("Internal Server Error")));

        assertThatThrownBy(() -> client.getCurrentRate())
            .isInstanceOf(ExchangeRateUnavailableException.class)
            .hasMessageContaining("HTTP 500");
    }

    @Test
    @DisplayName("Throws ExchangeRateUnavailableException on connection timeout")
    void getCurrentRate_timeout() {
        wireMock.stubFor(get(urlPathEqualTo("/currency-engine/api/v1/products/" + PRODUCT_CODE))
            .willReturn(ok().withFixedDelay(15000)));

        assertThatThrownBy(() -> client.getCurrentRate())
            .isInstanceOf(ExchangeRateUnavailableException.class);
    }

    @Test
    @DisplayName("Throws ExchangeRateUnavailableException when rate is stale (> maxStaleMinutes)")
    void getCurrentRate_staleRate() {
        String staleTime = Instant.now().minus(10, ChronoUnit.MINUTES).toString();
        wireMock.stubFor(get(urlPathEqualTo("/currency-engine/api/v1/products/" + PRODUCT_CODE))
            .willReturn(okJson("""
                {
                    "name": "CARRITO_SUS",
                    "bidPrice": 6.960000000000,
                    "askPrice": 7.040000000000,
                    "currency": "BOB",
                    "symbol": "BOB/USDT",
                    "lastUpdated": "%s"
                }
                """.formatted(staleTime))));

        assertThatThrownBy(() -> client.getCurrentRate())
            .isInstanceOf(ExchangeRateUnavailableException.class)
            .hasMessageContaining("stale");
    }

    @Test
    @DisplayName("Throws ExchangeRateUnavailableException when response has null bidPrice")
    void getCurrentRate_nullBidPrice() {
        wireMock.stubFor(get(urlPathEqualTo("/currency-engine/api/v1/products/" + PRODUCT_CODE))
            .willReturn(okJson("""
                {
                    "name": "CARRITO_SUS",
                    "bidPrice": null,
                    "askPrice": 7.04,
                    "currency": "BOB"
                }
                """)));

        assertThatThrownBy(() -> client.getCurrentRate())
            .isInstanceOf(ExchangeRateUnavailableException.class)
            .hasMessageContaining("null rate");
    }

    @Test
    @DisplayName("Accepts fresh rate when lastUpdated is within maxStaleMinutes")
    void getCurrentRate_freshRate_accepted() {
        String freshTime = Instant.now().minus(2, ChronoUnit.MINUTES).toString();
        wireMock.stubFor(get(urlPathEqualTo("/currency-engine/api/v1/products/" + PRODUCT_CODE))
            .willReturn(okJson("""
                {
                    "name": "CARRITO_SUS",
                    "bidPrice": 6.96,
                    "askPrice": 7.04,
                    "currency": "BOB",
                    "symbol": "BOB/USDT",
                    "lastUpdated": "%s"
                }
                """.formatted(freshTime))));

        ExchangeRateDto rate = client.getCurrentRate();

        assertThat(rate.getBidPrice()).isEqualByComparingTo("6.96");
    }
}
