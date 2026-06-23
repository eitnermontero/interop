package bo.com.sintesis.mdqr.gateway.security.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests unitarios de {@link MtlsCertBindingFilter}.
 *
 * <p>Prueba el comportamiento del filtro RFC 8705 en modo test/local usando
 * el header {@code X-Client-Cert-Thumbprint} como simulador del certificado TLS.
 *
 * <p>No levanta contexto de Spring — usa {@link MockServerWebExchange} para
 * construir los exchanges y {@link ReactiveSecurityContextHolder} para simular
 * la autenticación JWT.
 */
@ExtendWith(MockitoExtension.class)
class MtlsCertBindingFilterTest {

    /** Thumbprint de ejemplo en formato Base64url (mismo que usaría Keycloak). */
    private static final String THUMBPRINT_VALIDO    = "abc123XYZ_validThumbprintBase64urlPaddingFree==";
    private static final String THUMBPRINT_DIFERENTE = "xyz456ABC_differentThumbprintBase64urlPadFree==";

    private MtlsCertBindingFilter filtro;
    private GatewayFilterChain chainMock;

    @BeforeEach
    void setUp() {
        chainMock = mock(GatewayFilterChain.class);
        when(chainMock.filter(any())).thenReturn(Mono.empty());
    }

    // ─── Test 1: thumbprint coincide → pasa y propaga X-Partner-Id ───────────

    @Test
    void shouldAllowWhenThumbprintMatches() {
        // Arrange
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("local");
        filtro = new MtlsCertBindingFilter(env);

        String partnerId = "unilink-api";
        Jwt jwt = construirJwt(partnerId, THUMBPRINT_VALIDO);

        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.POST, "/partner/v1/qr/decode")
                .header(MtlsCertBindingFilter.HEADER_SIMULATED_THUMBPRINT, THUMBPRINT_VALIDO)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        SecurityContextImpl secCtx = new SecurityContextImpl(new JwtAuthenticationToken(jwt, List.of()));

        // Act
        Mono<Void> resultado = filtro.filter(exchange, chainMock)
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(secCtx)));

        // Assert: no debe emitir error y el chain debe ser llamado con el exchange mutado
        StepVerifier.create(resultado)
                .verifyComplete();

        // El chainMock recibió un exchange con X-Partner-Id
        // Verificamos que el filter chain fue invocado (el mock devuelve Mono.empty())
        // La verificación del header mutado requiere capturar el argumento del chain:
        org.mockito.ArgumentCaptor<ServerWebExchange> captor =
                org.mockito.ArgumentCaptor.forClass(ServerWebExchange.class);
        org.mockito.Mockito.verify(chainMock).filter(captor.capture());
        String propagatedPartnerId = captor.getValue().getRequest().getHeaders()
                .getFirst(MtlsCertBindingFilter.HEADER_PARTNER_ID);
        assertThat(propagatedPartnerId)
                .as("X-Partner-Id debe propagarse con el sub del JWT")
                .isEqualTo(partnerId);
    }

    // ─── Test 2: thumbprint no coincide → 401 certificate_binding_mismatch ──

    @Test
    void shouldRejectWhenThumbprintMismatch() {
        // Arrange
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("local");
        filtro = new MtlsCertBindingFilter(env);

        Jwt jwt = construirJwt("unilink-api", THUMBPRINT_VALIDO);

        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.POST, "/partner/v1/qr/decode")
                // El header tiene un thumbprint DIFERENTE al claim cnf del JWT
                .header(MtlsCertBindingFilter.HEADER_SIMULATED_THUMBPRINT, THUMBPRINT_DIFERENTE)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        SecurityContextImpl secCtx = new SecurityContextImpl(new JwtAuthenticationToken(jwt, List.of()));

        // Act
        Mono<Void> resultado = filtro.filter(exchange, chainMock)
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(secCtx)));

        StepVerifier.create(resultado)
                .verifyComplete();

        // Assert: debe devolver 401
        assertThat(exchange.getResponse().getStatusCode())
                .as("Debe retornar 401 cuando los thumbprints no coinciden")
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // El chain no debe haber sido llamado
        org.mockito.Mockito.verify(chainMock, org.mockito.Mockito.never()).filter(any());
    }

    // ─── Test 3: perfil local con header simulado → pasa ─────────────────────

    @Test
    void shouldAllowInLocalProfileWithSimulatedHeader() {
        // Arrange — perfil "local" activo, propiedad hub.mtls.test-mode no seteada
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("local");
        filtro = new MtlsCertBindingFilter(env);

        String thumbprint = "simulated-thumbprint-local-profile-test";
        Jwt jwt = construirJwt("partner-local", thumbprint);

        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.POST, "/partner/v1/qr/decode")
                .header(MtlsCertBindingFilter.HEADER_SIMULATED_THUMBPRINT, thumbprint)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        SecurityContextImpl secCtx = new SecurityContextImpl(new JwtAuthenticationToken(jwt, List.of()));

        // Act
        Mono<Void> resultado = filtro.filter(exchange, chainMock)
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(secCtx)));

        StepVerifier.create(resultado)
                .verifyComplete();

        // Assert: el chain fue llamado (thumbprint coincide en perfil local)
        org.mockito.Mockito.verify(chainMock).filter(any());
        // Status no debe ser error
        assertThat(exchange.getResponse().getStatusCode()).isNull(); // sin status seteado = pasa
    }

    // ─── Test adicional: ruta no-partner pasa sin inspección ─────────────────

    @Test
    void shouldSkipNonPartnerPaths() {
        // Arrange
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("local");
        filtro = new MtlsCertBindingFilter(env);

        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.GET, "/services/mdqradminservice/admin/users")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        // Act — sin SecurityContext seteado (no importa, no es ruta de partner)
        Mono<Void> resultado = filtro.filter(exchange, chainMock);

        StepVerifier.create(resultado)
                .verifyComplete();

        // Assert: el chain fue llamado directamente sin inspección
        org.mockito.Mockito.verify(chainMock).filter(exchange);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    // ─── Test adicional: JWT sin claim cnf → 401 ──────────────────────────────

    @Test
    void shouldRejectWhenJwtHasNoCnfClaim() {
        // Arrange
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("local");
        filtro = new MtlsCertBindingFilter(env);

        // JWT sin claim 'cnf'
        Jwt jwt = Jwt.withTokenValue("token-sin-cnf")
                .header("alg", "RS256")
                .subject("partner-sin-cnf")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.POST, "/partner/v1/qr/decode")
                .header(MtlsCertBindingFilter.HEADER_SIMULATED_THUMBPRINT, THUMBPRINT_VALIDO)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        SecurityContextImpl secCtx = new SecurityContextImpl(new JwtAuthenticationToken(jwt, List.of()));

        // Act
        Mono<Void> resultado = filtro.filter(exchange, chainMock)
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(secCtx)));

        StepVerifier.create(resultado)
                .verifyComplete();

        // Assert: debe devolver 401 por falta de claim cnf
        assertThat(exchange.getResponse().getStatusCode())
                .as("Debe retornar 401 cuando el JWT no tiene claim cnf")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    /**
     * Construye un JWT de prueba con el claim {@code cnf.x5t#S256} configurado.
     *
     * @param subject     valor del claim {@code sub} (partner_id)
     * @param thumbprint  valor del thumbprint en {@code cnf.x5t#S256}
     */
    private Jwt construirJwt(String subject, String thumbprint) {
        return Jwt.withTokenValue("test-token-" + subject)
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("cnf", Map.of("x5t#S256", thumbprint))
                .claim("scope", "https://api.sintesis.com.bo/qr.decode")
                .claim("azp", subject)
                .build();
    }
}
