package bo.com.sintesis.mdqr.base.service;

import bo.com.sintesis.mdqr.base.integration.currencyengine.CurrencyEngineClient;
import bo.com.sintesis.mdqr.base.integration.currencyengine.dto.ExchangeRateDto;
import bo.com.sintesis.mdqr.base.web.rest.errors.ExchangeRateUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExchangeRateServiceTest {

    @Mock
    private CurrencyEngineClient client;

    @InjectMocks
    private ExchangeRateService exchangeRateService;

    @Test
    @DisplayName("getCurrentRate returns rate from CurrencyEngineClient")
    void getCurrentRate_delegatesToClient() {
        ExchangeRateDto expected = new ExchangeRateDto();
        expected.setBidPrice(new BigDecimal("6.960000000000"));
        expected.setAskPrice(new BigDecimal("7.040000000000"));
        expected.setLastUpdated(Instant.now());

        when(client.getCurrentRate()).thenReturn(expected);

        ExchangeRateDto result = exchangeRateService.getCurrentRate();

        assertThat(result).isSameAs(expected);
        assertThat(result.getBidPrice()).isEqualByComparingTo("6.96");
    }

    @Test
    @DisplayName("getCurrentRate propagates ExchangeRateUnavailableException when client throws")
    void getCurrentRate_clientDown_propagatesException() {
        when(client.getCurrentRate()).thenThrow(new ExchangeRateUnavailableException("Connection refused"));

        assertThatThrownBy(() -> exchangeRateService.getCurrentRate())
            .isInstanceOf(ExchangeRateUnavailableException.class)
            .hasMessageContaining("Connection refused");
    }

    @Test
    @DisplayName("getCurrentRate propagates stale rate exception from client")
    void getCurrentRate_staleRate_propagates503() {
        when(client.getCurrentRate()).thenThrow(
            new ExchangeRateUnavailableException("Rate is stale (10 min old, max allowed: 5 min)"));

        assertThatThrownBy(() -> exchangeRateService.getCurrentRate())
            .isInstanceOf(ExchangeRateUnavailableException.class)
            .hasMessageContaining("stale");
    }
}
