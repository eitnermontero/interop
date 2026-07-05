package bo.com.sintesis.hub.base.interop.outbound.efxrate;

import bo.com.sintesis.hub.audit.hash.PayloadHasher;
import bo.com.sintesis.hub.audit.hub.HubAuditCommand;
import bo.com.sintesis.hub.audit.hub.HubAuditService;
import bo.com.sintesis.hub.base.interop.canonical.ExchangeRateRequest;
import bo.com.sintesis.hub.base.interop.canonical.ExchangeRateResponse;
import bo.com.sintesis.hub.base.interop.outbound.efxrate.dto.EfxRateProviderResponse;
import bo.com.sintesis.hub.base.interop.outbound.efxrate.exception.ExchangeRateNotFoundException;
import bo.com.sintesis.hub.base.interop.outbound.efxrate.mapper.EfxRateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests unitarios puros del EfxRateAdapter.
 * Sin Spring context — todos los colaboradores son mocks de Mockito.
 */
@ExtendWith(MockitoExtension.class)
class EfxRateAdapterTest {

    @Mock private EfxRateClient             client;
    @Mock private HubAuditService           hubAuditService;
    @Mock private PayloadHasher             payloadHasher;
    @Mock private EfxRateProperties         properties;
    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private EfxRateMapper   mapper;
    private EfxRateAdapter  adapter;

    private static final LocalDate   FECHA        = LocalDate.of(2026, 6, 22);
    private static final BigDecimal  VALOR_RATE   = new BigDecimal("2.52017");

    @BeforeEach
    void setUp() {
        mapper = new EfxRateMapper();

        // Redis no cachea nada por defecto en estos tests
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        // Properties mínimas para escritura en caché — lenient porque cuando el client
        // lanza excepción nunca se llega a escribirEnCache(), y el stub quedaría sin usar.
        lenient().when(properties.getCacheTtlMinutes()).thenReturn(60);

        adapter = new EfxRateAdapter(client, mapper, hubAuditService,
                payloadHasher, properties, redisTemplate);
    }

    @Test
    @DisplayName("Debería retornar la respuesta canónica correctamente en el happy path")
    void shouldReturnCanonicalResponseOnSuccess() {
        // Arrange
        ExchangeRateRequest request = new ExchangeRateRequest(
                "BOB", "UFV", FECHA, "idem-key-001");

        EfxRateProviderResponse providerResponse = new EfxRateProviderResponse(
                "BOB", "UFV", "20260622", VALOR_RATE, "BCB", "2026-06-22T00:00:00Z");

        when(client.fetchRate(any())).thenReturn(providerResponse);
        when(payloadHasher.hash(anyString())).thenReturn("a".repeat(64), "b".repeat(64));
        when(hubAuditService.record(any())).thenReturn("chain-hash-abc");

        // Act
        ExchangeRateResponse response = adapter.getExchangeRate(request);

        // Assert — contrato canónico
        assertThat(response.rate()).isEqualByComparingTo(VALOR_RATE);
        assertThat(response.sourceCurrency()).isEqualTo("BOB");
        assertThat(response.targetCurrency()).isEqualTo("UFV");
        assertThat(response.dataSource()).isEqualTo("BCB");
        assertThat(response.date()).isEqualTo(FECHA);
        assertThat(response.retrievedAt()).isNotNull();

        // Assert — auditoría llamada exactamente una vez con direction="OUT"
        ArgumentCaptor<HubAuditCommand> cmdCaptor = ArgumentCaptor.forClass(HubAuditCommand.class);
        verify(hubAuditService, times(1)).record(cmdCaptor.capture());
        HubAuditCommand cmd = cmdCaptor.getValue();
        assertThat(cmd.direction()).isEqualTo("OUT");
        assertThat(cmd.product()).isEqualTo("EXCHANGE_RATE");
        assertThat(cmd.partnerId()).isEqualTo("efxrate");
        assertThat(cmd.httpStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Debería retornar la respuesta de negocio incluso cuando la auditoría falla")
    void shouldReturnCanonicalResponseEvenWhenAuditFails() {
        // Arrange
        ExchangeRateRequest request = new ExchangeRateRequest(
                "BOB", "UFV", FECHA, null);

        EfxRateProviderResponse providerResponse = new EfxRateProviderResponse(
                "BOB", "UFV", "20260622", VALOR_RATE, "BCB", "2026-06-22T00:00:00Z");

        when(client.fetchRate(any())).thenReturn(providerResponse);
        when(payloadHasher.hash(anyString())).thenReturn("a".repeat(64), "b".repeat(64));
        // La auditoría lanza excepción
        doThrow(new RuntimeException("Postgres no disponible"))
                .when(hubAuditService).record(any());

        // Act — no debe lanzar excepción
        ExchangeRateResponse response = adapter.getExchangeRate(request);

        // Assert — el negocio continúa
        assertThat(response).isNotNull();
        assertThat(response.rate()).isEqualByComparingTo(VALOR_RATE);

        // La auditoría fue intentada
        verify(hubAuditService, times(1)).record(any());
    }

    @Test
    @DisplayName("Debería propagar ExchangeRateNotFoundException desde el client sin envolver")
    void shouldPropagateNotFoundExceptionFromClient() {
        // Arrange
        ExchangeRateRequest request = new ExchangeRateRequest(
                "BOB", "UFV", FECHA, null);

        when(client.fetchRate(any()))
                .thenThrow(new ExchangeRateNotFoundException("Tipo de cambio no disponible"));

        // Act + Assert
        assertThatThrownBy(() -> adapter.getExchangeRate(request))
                .isInstanceOf(ExchangeRateNotFoundException.class)
                .hasMessageContaining("Tipo de cambio no disponible");

        // La auditoría NO debe registrarse si el proveedor no devolvió respuesta
        verify(hubAuditService, never()).record(any());
    }

    @Test
    @DisplayName("La idempotency key del request debe propagarse al HubAuditCommand")
    void shouldUseIdempotencyKeyFromRequest() {
        // Arrange
        String idempotencyKey = "mi-clave-idempotente-xyz-123";
        ExchangeRateRequest request = new ExchangeRateRequest(
                "BOB", "UFV", FECHA, idempotencyKey);

        EfxRateProviderResponse providerResponse = new EfxRateProviderResponse(
                "BOB", "UFV", "20260622", VALOR_RATE, "BCB", "2026-06-22T00:00:00Z");

        when(client.fetchRate(any())).thenReturn(providerResponse);
        when(payloadHasher.hash(anyString())).thenReturn("a".repeat(64), "b".repeat(64));

        // Act
        adapter.getExchangeRate(request);

        // Assert — la idempotency key llega al comando de auditoría
        ArgumentCaptor<HubAuditCommand> cmdCaptor = ArgumentCaptor.forClass(HubAuditCommand.class);
        verify(hubAuditService).record(cmdCaptor.capture());
        assertThat(cmdCaptor.getValue().idempotencyKey()).isEqualTo(idempotencyKey);
    }
}
