package bo.com.sintesis.mdqr.gateway.config;

import bo.com.sintesis.mdqr.gateway.web.rest.errors.ProblemDetailAuthEntryPoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.client.oidc.web.server.logout.OidcClientInitiatedServerLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationEntryPoint;
import org.springframework.security.web.server.util.matcher.OrServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;

@Slf4j
@Configuration
@EnableWebFluxSecurity
@RequiredArgsConstructor
public class SecurityConfiguration {

    private final ProblemDetailAuthEntryPoint authEntryPoint;
    private final ReactiveClientRegistrationRepository clientRegistrationRepository;
    private final ApplicationProperties properties;

    // ─── Chain 1: Partner (external APIs) — Order=1 ───────────────────────
    // Aplica a /partner/** y al token endpoint /oauth2/token.
    // Solo JWT Bearer (resource server). Sin OIDC browser login.
    // Valida tokens del realm mdqr-partner.
    @Bean
    @Order(1)
    public SecurityWebFilterChain partnerSecurityChain(
            ServerHttpSecurity http,
            @Qualifier("partnerJwtDecoder") ReactiveJwtDecoder partnerDecoder) {

        http
            .securityMatcher(new OrServerWebExchangeMatcher(
                new PathPatternParserServerWebExchangeMatcher("/partner/**"),
                new PathPatternParserServerWebExchangeMatcher("/oauth2/token")
            ))
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .cors(Customizer.withDefaults())
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(authEntryPoint)
            )
            .authorizeExchange(auth -> auth
                // El token endpoint es público — es donde los partners obtienen el JWT
                .pathMatchers("/oauth2/token").permitAll()
                // Todas las rutas /partner/** requieren un JWT válido de mdqr-partner
                .pathMatchers("/partner/**").authenticated()
                .anyExchange().denyAll()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtDecoder(partnerDecoder))
                .authenticationEntryPoint(authEntryPoint)
            );

        return http.build();
    }

    // ─── Chain 2: Admin / Internal (web app) — Order=2 ────────────────────
    // Aplica a todo lo que no capturó el chain de partner.
    // Soporta JWT Bearer (desde keycloak-js de la SPA) y OIDC browser flow.
    // Valida tokens del realm mdqr-admin.
    @Bean
    @Order(2)
    public SecurityWebFilterChain adminSecurityChain(
            ServerHttpSecurity http,
            @Qualifier("adminJwtDecoder") ReactiveJwtDecoder adminDecoder) {

        http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .cors(Customizer.withDefaults())
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(browserOrApiEntryPoint())
            )
            .authorizeExchange(auth -> auth
                // Auth endpoints de ms-auth: el frontend necesita login/refresh/logout sin JWT previo
                .pathMatchers(
                    "/services/*/admin/auth/login",
                    "/services/*/admin/auth/refresh",
                    "/services/*/admin/auth/logout"
                ).permitAll()
                // Health y management
                .pathMatchers("/management/health/**", "/management/info").permitAll()
                // Swagger / OpenAPI
                .pathMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/webjars/**").permitAll()
                // OIDC browser flow callbacks
                .pathMatchers("/oauth2/**", "/login/oauth2/code/**", "/logout").permitAll()
                // Todo lo demás bajo /services/** requiere autenticación
                .pathMatchers("/services/**").authenticated()
                .anyExchange().denyAll()
            )
            // OIDC browser login (fallback para acceso directo vía browser)
            .oauth2Login(Customizer.withDefaults())
            // JWT Bearer (camino principal desde la SPA con keycloak-js)
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtDecoder(adminDecoder))
                .authenticationEntryPoint(authEntryPoint)
            )
            .logout(logout -> logout
                .logoutSuccessHandler(oidcLogoutSuccessHandler())
            );

        return http.build();
    }

    // ─── JWT Decoders ─────────────────────────────────────────────────────

    @Bean("adminJwtDecoder")
    public ReactiveJwtDecoder adminJwtDecoder() {
        var kc = properties.keycloak();
        String jwkSetUri = kc.authServerUrl() + "/realms/" + kc.realm()
            + "/protocol/openid-connect/certs";
        String issuerUri = kc.externalUrl() + "/realms/" + kc.realm();

        log.info("Admin JWT decoder — issuer: {}, jwks: {}", issuerUri, jwkSetUri);

        NimbusReactiveJwtDecoder decoder = new NimbusReactiveJwtDecoder(jwkSetUri);
        OAuth2TokenValidator<Jwt> validator = JwtValidators.createDefaultWithIssuer(issuerUri);
        decoder.setJwtValidator(validator);
        return decoder;
    }

    @Bean("partnerJwtDecoder")
    public ReactiveJwtDecoder partnerJwtDecoder() {
        var kc = properties.partnerKeycloak();
        String jwkSetUri = kc.authServerUrl() + "/realms/" + kc.realm()
            + "/protocol/openid-connect/certs";
        String issuerUri = kc.externalUrl() + "/realms/" + kc.realm();

        log.info("Partner JWT decoder — issuer: {}, jwks: {}", issuerUri, jwkSetUri);

        NimbusReactiveJwtDecoder decoder = new NimbusReactiveJwtDecoder(jwkSetUri);
        // NO usar JwtValidators.createDefaultWithIssuer: en Spring Security 6.5+ agrega
        // automáticamente X509CertificateThumbprintValidator cuando el token trae el claim
        // cnf.x5t#S256 (RFC 8705). Ese validador SOLO busca el certificado en SslInfo del
        // request, que está vacío cuando el mTLS lo termina nginx y el cert llega por el
        // header X-SSL-Client-Cert → falla con "Unable to obtain X509Certificate".
        // El binding RFC 8705 lo enforcea MtlsCertBindingFilter (lee SslInfo O el header),
        // así que aquí validamos solo timestamp + issuer.
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new JwtIssuerValidator(issuerUri));
        decoder.setJwtValidator(validator);
        return decoder;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    // Browsers envían "text/html" en Accept → redirect a Keycloak login page.
    // Clientes API (curl, JS fetch, mobile) → 401 JSON.
    private ServerAuthenticationEntryPoint browserOrApiEntryPoint() {
        var oidcEntryPoint = new RedirectServerAuthenticationEntryPoint("/oauth2/authorization/oidc");
        return (exchange, ex) -> {
            String accept = exchange.getRequest().getHeaders().getFirst(HttpHeaders.ACCEPT);
            boolean isBrowser = accept != null && accept.contains(MediaType.TEXT_HTML_VALUE);
            return isBrowser
                ? oidcEntryPoint.commence(exchange, ex)
                : authEntryPoint.commence(exchange, ex);
        };
    }

    @Bean
    OidcClientInitiatedServerLogoutSuccessHandler oidcLogoutSuccessHandler() {
        var handler = new OidcClientInitiatedServerLogoutSuccessHandler(clientRegistrationRepository);
        handler.setPostLogoutRedirectUri("{baseUrl}");
        return handler;
    }
}
