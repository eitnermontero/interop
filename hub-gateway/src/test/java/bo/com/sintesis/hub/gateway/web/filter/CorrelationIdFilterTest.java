package bo.com.sintesis.hub.gateway.web.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests unitarios de {@link CorrelationIdFilter}.
 */
@ExtendWith(MockitoExtension.class)
class CorrelationIdFilterTest {

    private WebFilterChain chainMock;
    private CorrelationIdFilter filtro;

    @BeforeEach
    void setUp() {
        chainMock = mock(WebFilterChain.class);
        filtro = new CorrelationIdFilter();
        // lenient: el test de orden no usa chainMock; el resto sí
        lenient().when(chainMock.filter(any())).thenReturn(Mono.empty());
    }

    // ─── Test 1: sin header → genera UUID y lo propaga ───────────────────────

    @Test
    @DisplayName("Sin X-Correlation-ID genera UUID y lo escribe en atributo, request y response")
    void shouldGenerateUuidWhenHeaderAbsent() {
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.GET, "/partner/v1/casos/123")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filtro.filter(exchange, chainMock))
                .verifyComplete();

        // Capturamos el exchange mutado que se pasó a la cadena
        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chainMock).filter(captor.capture());
        ServerWebExchange mutado = captor.getValue();

        // El atributo del exchange debe estar presente y ser un UUID válido
        String correlationId = (String) mutado.getAttributes().get(CorrelationIdFilter.ATTR_CORRELATION_ID);
        assertThat(correlationId)
                .as("correlationId debe ser un UUID generado")
                .isNotBlank()
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

        // El header downstream debe llevar el mismo valor
        String headerDownstream = mutado.getRequest().getHeaders()
                .getFirst(CorrelationIdFilter.HEADER_CORRELATION_ID);
        assertThat(headerDownstream).isEqualTo(correlationId);

        // El header de respuesta debe llevar el mismo valor
        String headerResponse = exchange.getResponse().getHeaders()
                .getFirst(CorrelationIdFilter.HEADER_CORRELATION_ID);
        assertThat(headerResponse).isEqualTo(correlationId);
    }

    // ─── Test 2: con header válido → respeta el valor enviado ────────────────

    @Test
    @DisplayName("Con X-Correlation-ID presente respeta el valor del cliente")
    void shouldRespectIncomingCorrelationId() {
        String idEnviado = "a1b2c3d4-e5f6-4071-8a9b-0c1d2e3f4a5b";
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.POST, "/partner/v1/casos")
                .header(CorrelationIdFilter.HEADER_CORRELATION_ID, idEnviado)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filtro.filter(exchange, chainMock))
                .verifyComplete();

        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chainMock).filter(captor.capture());
        ServerWebExchange mutado = captor.getValue();

        String correlationId = (String) mutado.getAttributes().get(CorrelationIdFilter.ATTR_CORRELATION_ID);
        assertThat(correlationId)
                .as("El filtro debe respetar el X-Correlation-ID enviado por el cliente")
                .isEqualTo(idEnviado);

        assertThat(mutado.getRequest().getHeaders().getFirst(CorrelationIdFilter.HEADER_CORRELATION_ID))
                .isEqualTo(idEnviado);

        assertThat(exchange.getResponse().getHeaders().getFirst(CorrelationIdFilter.HEADER_CORRELATION_ID))
                .isEqualTo(idEnviado);
    }

    // ─── Test 3: header en blanco → genera UUID ──────────────────────────────

    @Test
    @DisplayName("Con X-Correlation-ID en blanco genera un UUID nuevo")
    void shouldGenerateUuidWhenHeaderIsBlank() {
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.GET, "/partner/v1/casos")
                .header(CorrelationIdFilter.HEADER_CORRELATION_ID, "   ")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filtro.filter(exchange, chainMock))
                .verifyComplete();

        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chainMock).filter(captor.capture());
        String correlationId = (String) captor.getValue().getAttributes()
                .get(CorrelationIdFilter.ATTR_CORRELATION_ID);

        assertThat(correlationId)
                .as("Un header en blanco debe generar un UUID nuevo")
                .isNotBlank()
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    // ─── Test 4: dos requests consecutivos generan UUIDs distintos ───────────

    @Test
    @DisplayName("Dos requests sin header generan UUIDs distintos")
    void shouldGenerateDifferentUuidsForDifferentRequests() {
        MockServerHttpRequest req1 = MockServerHttpRequest
                .method(HttpMethod.GET, "/partner/v1/casos/1").build();
        MockServerHttpRequest req2 = MockServerHttpRequest
                .method(HttpMethod.GET, "/partner/v1/casos/2").build();

        MockServerWebExchange ex1 = MockServerWebExchange.from(req1);
        MockServerWebExchange ex2 = MockServerWebExchange.from(req2);

        StepVerifier.create(filtro.filter(ex1, chainMock)).verifyComplete();
        StepVerifier.create(filtro.filter(ex2, chainMock)).verifyComplete();

        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chainMock, org.mockito.Mockito.times(2)).filter(captor.capture());

        var exchanges = captor.getAllValues();
        String id1 = (String) exchanges.get(0).getAttributes().get(CorrelationIdFilter.ATTR_CORRELATION_ID);
        String id2 = (String) exchanges.get(1).getAttributes().get(CorrelationIdFilter.ATTR_CORRELATION_ID);

        assertThat(id1).isNotEqualTo(id2);
    }

    // ─── Test 5: el orden del filtro es HIGHEST_PRECEDENCE ───────────────────

    @Test
    @DisplayName("El orden del filtro es HIGHEST_PRECEDENCE")
    void shouldHaveHighestPrecedenceOrder() {
        assertThat(filtro.getOrder())
                .as("CorrelationIdFilter debe tener HIGHEST_PRECEDENCE")
                .isEqualTo(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);
    }
}
