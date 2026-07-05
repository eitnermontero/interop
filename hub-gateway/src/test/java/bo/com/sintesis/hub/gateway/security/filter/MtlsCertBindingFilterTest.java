package bo.com.sintesis.hub.gateway.security.filter;

import bo.com.sintesis.hub.gateway.web.rest.ApiResponseWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests unitarios de {@link MtlsCertBindingFilter}.
 *
 * <p>El filtro NO usa el SecurityContext reactivo: parsea los claims directamente
 * del header {@code Authorization: Bearer} (ya validado por la cadena de seguridad).
 * Por eso los tests construyen un JWT sintético (header.payload.sig, sin firma real)
 * y lo envían como Bearer. El certificado TLS se simula con el header
 * {@code X-Client-Cert-Thumbprint} (modo test/local).
 */
@ExtendWith(MockitoExtension.class)
class MtlsCertBindingFilterTest {

    /** Thumbprint de ejemplo en formato Base64url (mismo que usaría Keycloak). */
    private static final String THUMBPRINT_VALIDO    = "abc123XYZ_validThumbprintBase64urlPaddingFree";
    private static final String THUMBPRINT_DIFERENTE = "xyz456ABC_differentThumbprintBase64urlPadFree";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Writer real (sin dependencias) — no hace llamadas externas. */
    private final ApiResponseWriter writer = new ApiResponseWriter();

    private WebFilterChain chainMock;

    @BeforeEach
    void setUp() {
        chainMock = mock(WebFilterChain.class);
    }

    private MtlsCertBindingFilter filtroEnModoTest() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("local");
        return new MtlsCertBindingFilter(env, writer);
    }

    private MtlsCertBindingFilter filtroEnModoProd() {
        return new MtlsCertBindingFilter(new MockEnvironment(), writer);
    }

    // ─── Test 1: thumbprint coincide → pasa y propaga X-Partner-Id ───────────

    @Test
    void shouldAllowWhenThumbprintMatches() {
        when(chainMock.filter(any())).thenReturn(Mono.empty());
        MtlsCertBindingFilter filtro = filtroEnModoTest();

        String partnerId = "unilink-api";
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.POST, "/partner/v1/inbound/caso-penal/v1")
                .header(HttpHeaders.AUTHORIZATION, bearer(partnerId, THUMBPRINT_VALIDO))
                .header(MtlsCertBindingFilter.HEADER_SIMULATED_THUMBPRINT, THUMBPRINT_VALIDO)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filtro.filter(exchange, chainMock))
                .verifyComplete();

        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chainMock).filter(captor.capture());

        // El X-Partner-Id debe propagarse con el azp del JWT (no el sub)
        String propagatedPartnerId = captor.getValue().getRequest().getHeaders()
                .getFirst(MtlsCertBindingFilter.HEADER_PARTNER_ID);
        assertThat(propagatedPartnerId)
                .as("X-Partner-Id debe propagarse con el azp (client_id) del JWT")
                .isEqualTo(partnerId);
    }

    // ─── Test 2: thumbprint no coincide → 401 ApiResponse AUTHENTICATION_REQUIRED ──

    @Test
    void shouldRejectWhenThumbprintMismatch() throws Exception {
        MtlsCertBindingFilter filtro = filtroEnModoTest();

        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.POST, "/partner/v1/inbound/caso-penal/v1")
                .header(HttpHeaders.AUTHORIZATION, bearer("unilink-api", THUMBPRINT_VALIDO))
                // El header simula un certificado con thumbprint DIFERENTE al claim cnf
                .header(MtlsCertBindingFilter.HEADER_SIMULATED_THUMBPRINT, THUMBPRINT_DIFERENTE)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filtro.filter(exchange, chainMock))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode())
                .as("Debe retornar 401 cuando los thumbprints no coinciden")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(chainMock, never()).filter(any());

        // El body debe ser ApiResponse con success=false y code=AUTHENTICATION_REQUIRED
        JsonNode body = leerBodyJson(exchange);
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("status").asInt()).isEqualTo(401);
        assertThat(body.path("error").path("code").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
    }

    // ─── Test 3: modo test SIN claim cnf → pasa (Keycloak local no hace binding) ──

    @Test
    void shouldAllowWithoutCnfClaimInTestMode() {
        when(chainMock.filter(any())).thenReturn(Mono.empty());
        MtlsCertBindingFilter filtro = filtroEnModoTest();

        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.POST, "/partner/v1/inbound/caso-penal/v1")
                .header(HttpHeaders.AUTHORIZATION, bearer("partner-local", null))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filtro.filter(exchange, chainMock))
                .verifyComplete();

        // En test-mode el JWT sin cnf se acepta y se propaga el partner-id por azp
        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chainMock).filter(captor.capture());
        assertThat(captor.getValue().getRequest().getHeaders()
                .getFirst(MtlsCertBindingFilter.HEADER_PARTNER_ID))
                .isEqualTo("partner-local");
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    // ─── Test 4: ruta no-partner pasa sin inspección ─────────────────────────

    @Test
    void shouldSkipNonPartnerPaths() {
        when(chainMock.filter(any())).thenReturn(Mono.empty());
        MtlsCertBindingFilter filtro = filtroEnModoTest();

        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.GET, "/services/hubadminservice/admin/users")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filtro.filter(exchange, chainMock))
                .verifyComplete();

        verify(chainMock).filter(exchange);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    // ─── Test 5: PRODUCCIÓN — JWT sin claim cnf → 401 ApiResponse ─────────────

    @Test
    void shouldRejectWhenJwtHasNoCnfClaimInProd() throws Exception {
        MtlsCertBindingFilter filtro = filtroEnModoProd();

        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.POST, "/partner/v1/inbound/caso-penal/v1")
                .header(HttpHeaders.AUTHORIZATION, bearer("partner-sin-cnf", null))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filtro.filter(exchange, chainMock))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode())
                .as("En producción, JWT sin claim cnf debe rechazarse con 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(chainMock, never()).filter(any());

        // El body debe ser ApiResponse con success=false y code=AUTHENTICATION_REQUIRED
        JsonNode body = leerBodyJson(exchange);
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("error").path("code").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
    }

    // ─── Test 6: X-Partner-Id usa azp con fallback a sub ─────────────────────

    @Test
    void shouldUseAzpAsPartnerIdWithSubFallback() {
        when(chainMock.filter(any())).thenReturn(Mono.empty());
        MtlsCertBindingFilter filtro = filtroEnModoTest();

        // JWT con azp distinto de sub — azp debe prevalecer
        String azpValue = "unilink-api-client";
        String subValue = "550e8400-e29b-41d4-a716-446655440000"; // UUID típico de Keycloak
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.POST, "/partner/v1/inbound/caso-penal/v1")
                .header(HttpHeaders.AUTHORIZATION, bearerConAzpYSub(azpValue, subValue, THUMBPRINT_VALIDO))
                .header(MtlsCertBindingFilter.HEADER_SIMULATED_THUMBPRINT, THUMBPRINT_VALIDO)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filtro.filter(exchange, chainMock)).verifyComplete();

        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chainMock).filter(captor.capture());
        String propagatedPartnerId = captor.getValue().getRequest().getHeaders()
                .getFirst(MtlsCertBindingFilter.HEADER_PARTNER_ID);
        assertThat(propagatedPartnerId)
                .as("azp debe usarse como X-Partner-Id (es el client_id legible)")
                .isEqualTo(azpValue);
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Construye un Bearer JWT sintético (sin firma válida — el filtro no verifica
     * firma, eso ya lo hizo la cadena de seguridad) con {@code sub}, {@code azp},
     * {@code scope} y, si {@code thumbprint != null}, el claim {@code cnf.x5t#S256}.
     * El {@code azp} es igual al {@code subject} para simplificar los tests base.
     */
    private static String bearer(String subject, String thumbprint) {
        try {
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("sub", subject);
            claims.put("scope", "https://api.sintesis.com.bo/caso.penal");
            claims.put("azp", subject);
            if (thumbprint != null) {
                claims.put("cnf", Map.of("x5t#S256", thumbprint));
            }
            Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
            String header = b64.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8));
            String payload = b64.encodeToString(MAPPER.writeValueAsBytes(claims));
            return "Bearer " + header + "." + payload + ".firma-de-prueba";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Construye un Bearer con azp y sub distintos para verificar la prioridad. */
    private static String bearerConAzpYSub(String azp, String sub, String thumbprint) {
        try {
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("sub", sub);
            claims.put("azp", azp);
            claims.put("scope", "https://api.sintesis.com.bo/caso.penal");
            if (thumbprint != null) {
                claims.put("cnf", Map.of("x5t#S256", thumbprint));
            }
            Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
            String header = b64.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8));
            String payload = b64.encodeToString(MAPPER.writeValueAsBytes(claims));
            return "Bearer " + header + "." + payload + ".firma-de-prueba";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Lee el body de la respuesta del exchange y lo parsea como {@link JsonNode}.
     * Usa StepVerifier para suscribirse al {@code Mono<String>} que devuelve
     * {@code MockServerHttpResponse.getBodyAsString()}.
     */
    private static JsonNode leerBodyJson(MockServerWebExchange exchange) throws Exception {
        // getBodyAsString() devuelve Mono<String> en Spring WebFlux reactive mock
        String[] holder = new String[1];
        StepVerifier.create(exchange.getResponse().getBodyAsString())
                .consumeNextWith(s -> holder[0] = s)
                .verifyComplete();
        return MAPPER.readTree(holder[0]);
    }
}
