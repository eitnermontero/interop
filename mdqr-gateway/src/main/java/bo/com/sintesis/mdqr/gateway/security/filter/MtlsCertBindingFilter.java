package bo.com.sintesis.mdqr.gateway.security.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.SslInfo;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

/**
 * Filtro RFC 8705: verifica que el thumbprint SHA-256 del certificado TLS de cliente
 * coincida con el claim {@code cnf.x5t#S256} del JWT ya validado.
 *
 * <p>Flujo:
 * <ol>
 *   <li>Extrae el {@code cnf.x5t#S256} del JWT (claim de binding del certificado).</li>
 *   <li>En producción: obtiene el certificado real de {@link SslInfo}.</li>
 *   <li>En modo test/local: acepta el thumbprint desde el header {@code X-Client-Cert-Thumbprint}.</li>
 *   <li>Compara los thumbprints. Si no coinciden → 401.</li>
 *   <li>Propaga {@code X-Partner-Id} (claim {@code sub} del JWT) como header downstream.</li>
 * </ol>
 *
 * <p>Solo aplica a rutas {@code /partner/**}; las demás pasan sin inspección.
 *
 * <p>Orden = 10: corre después de la autenticación JWT (Spring Security) pero antes
 * de {@link PartnerSubscriptionFilter} (orden 11).
 */
@Slf4j
@Component
public class MtlsCertBindingFilter implements GlobalFilter, Ordered {

    /** Header usado en modo test/local para simular el thumbprint del certificado. */
    public static final String HEADER_SIMULATED_THUMBPRINT = "X-Client-Cert-Thumbprint";

    /** Header propagado downstream con el identificador del partner. */
    public static final String HEADER_PARTNER_ID = "X-Partner-Id";

    /** Ruta de partner que activa este filtro. */
    private static final String PARTNER_PATH_PREFIX = "/partner/";

    private static final String ERROR_BODY_MISMATCH  = "{\"error\":\"certificate_binding_mismatch\"}";
    private static final String ERROR_BODY_NO_CNF     = "{\"error\":\"missing_cnf_claim\"}";
    private static final String ERROR_BODY_NO_CERT    = "{\"error\":\"missing_client_certificate\"}";

    private final boolean testModeEnabled;

    public MtlsCertBindingFilter(Environment env) {
        // El modo test se activa con el perfil "local" o "test", o si hay
        // la propiedad explícita hub.mtls.test-mode=true.
        // TODO(hub-poc): en producción, retirar el soporte de perfil "local" aquí
        //   y forzar siempre la extracción del cert real de SslInfo.
        boolean localOrTest = Arrays.asList(env.getActiveProfiles()).stream()
                .anyMatch(p -> p.equals("local") || p.equals("test"));
        boolean propEnabled = Boolean.parseBoolean(env.getProperty("hub.mtls.test-mode", "false"));
        this.testModeEnabled = localOrTest || propEnabled;
        log.info("MtlsCertBindingFilter iniciado — testMode={}", this.testModeEnabled);
    }

    @Override
    public int getOrder() {
        // Correr después del rate limit (orden 3) y antes del filtro de suscripción (orden 11).
        return 10;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith(PARTNER_PATH_PREFIX)) {
            // Solo aplica a rutas de partner
            return chain.filter(exchange);
        }

        return ReactiveSecurityContextHolder.getContext()
                .flatMap(ctx -> {
                    if (!(ctx.getAuthentication() instanceof JwtAuthenticationToken jwtAuth)) {
                        // Sin autenticación JWT previa → Spring Security ya rechazó antes
                        return chain.filter(exchange);
                    }

                    Jwt jwt = (Jwt) jwtAuth.getPrincipal();
                    String cnfThumbprint = extraerCnfThumbprint(jwt);

                    if (cnfThumbprint == null) {
                        if (testModeEnabled) {
                            // En test-mode Keycloak emite tokens sin certificate-bound (sin mTLS real
                            // en el token endpoint). Aceptar y propagar el partner-id sin validar binding.
                            log.warn("Test-mode: JWT sin cnf.x5t#S256 — saltando RFC 8705 para path={}", path);
                            String partnerId = jwt.getSubject();
                            ServerWebExchange mutated = exchange.mutate()
                                    .request(r -> r.header(HEADER_PARTNER_ID, partnerId))
                                    .build();
                            return chain.filter(mutated);
                        }
                        // Producción: JWT sin cnf.x5t#S256 → rechazar
                        log.warn("JWT sin claim cnf.x5t#S256 para path={}", path);
                        return rechazar(exchange, HttpStatus.UNAUTHORIZED, ERROR_BODY_NO_CNF);
                    }

                    String presentedThumbprint = obtenerThumbprintPresentado(exchange);

                    if (presentedThumbprint == null) {
                        log.warn("No se pudo obtener el thumbprint del certificado de cliente — path={}", path);
                        return rechazar(exchange, HttpStatus.UNAUTHORIZED, ERROR_BODY_NO_CERT);
                    }

                    if (!cnfThumbprint.equalsIgnoreCase(presentedThumbprint)) {
                        log.warn("RFC 8705: thumbprint mismatch — jwt_cnf={} cert={} path={}",
                                cnfThumbprint, presentedThumbprint, path);
                        return rechazar(exchange, HttpStatus.UNAUTHORIZED, ERROR_BODY_MISMATCH);
                    }

                    // Thumbprint coincide: propagar el partner_id downstream
                    String partnerId = jwt.getSubject();
                    log.debug("RFC 8705 OK — partnerId={} path={}", partnerId, path);

                    ServerWebExchange mutated = exchange.mutate()
                            .request(r -> r.header(HEADER_PARTNER_ID, partnerId))
                            .build();

                    return chain.filter(mutated);
                })
                .switchIfEmpty(chain.filter(exchange));
    }

    /**
     * Extrae el thumbprint del claim {@code cnf.x5t#S256} del JWT (RFC 8705).
     * El claim tiene la forma: {@code {"cnf": {"x5t#S256": "<base64url-sha256>"}}}
     */
    @SuppressWarnings("unchecked")
    private String extraerCnfThumbprint(Jwt jwt) {
        Object cnfClaim = jwt.getClaims().get("cnf");
        if (!(cnfClaim instanceof Map<?, ?> cnfMap)) {
            return null;
        }
        Object thumbprint = cnfMap.get("x5t#S256");
        return thumbprint instanceof String s ? s : null;
    }

    /**
     * Obtiene el thumbprint SHA-256 del certificado presentado por el cliente.
     *
     * <p>En modo test (perfil local o {@code hub.mtls.test-mode=true}): lee el header
     * {@code X-Client-Cert-Thumbprint}. Esto permite que los tests de integración
     * simulen mTLS sin infraestructura TLS real.
     *
     * <p>En modo producción: extrae el certificado de {@link SslInfo} y calcula
     * el thumbprint SHA-256 codificado en Base64url (sin padding), que es el formato
     * que usa Keycloak en el claim {@code cnf.x5t#S256}.
     */
    private String obtenerThumbprintPresentado(ServerWebExchange exchange) {
        if (testModeEnabled) {
            String header = exchange.getRequest().getHeaders().getFirst(HEADER_SIMULATED_THUMBPRINT);
            if (header != null) {
                log.debug("Modo test: usando thumbprint del header {}", HEADER_SIMULATED_THUMBPRINT);
            }
            return header;
        }

        // Producción: extraer del handshake TLS
        SslInfo sslInfo = exchange.getRequest().getSslInfo();
        if (sslInfo == null) {
            log.warn("SslInfo nulo — la conexión no es TLS o el servidor no expone la info del cert");
            return null;
        }
        X509Certificate[] peerCerts = sslInfo.getPeerCertificates();
        if (peerCerts == null || peerCerts.length == 0) {
            log.warn("No se encontraron certificados de cliente en SslInfo");
            return null;
        }
        return calcularThumbprintBase64url(peerCerts[0]);
    }

    /**
     * Calcula el thumbprint SHA-256 de un certificado X.509 codificado en Base64url
     * sin padding, según RFC 7517 / RFC 8705.
     *
     * @param cert certificado X.509 del cliente
     * @return thumbprint en Base64url sin padding, o {@code null} si falla
     */
    private String calcularThumbprintBase64url(X509Certificate cert) {
        try {
            byte[] encoded = cert.getEncoded();
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha256.digest(encoded);
            // Base64url sin padding — mismo formato que usa Keycloak en cnf.x5t#S256
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (CertificateEncodingException | NoSuchAlgorithmException e) {
            log.error("Error al calcular thumbprint del certificado: {}", e.getMessage());
            return null;
        }
    }

    private Mono<Void> rechazar(ServerWebExchange exchange, HttpStatus status, String body) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        org.springframework.core.io.buffer.DataBuffer buf =
                exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buf));
    }
}
