package bo.com.sintesis.mdqr.gateway.security.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.SslInfo;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateFactory;
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
public class MtlsCertBindingFilter implements WebFilter, Ordered {

    /** Header usado en modo test/local para simular el thumbprint del certificado. */
    public static final String HEADER_SIMULATED_THUMBPRINT = "X-Client-Cert-Thumbprint";

    /** Header propagado downstream con el identificador del partner. */
    public static final String HEADER_PARTNER_ID = "X-Partner-Id";

    /** Ruta de partner que activa este filtro. */
    private static final String PARTNER_PATH_PREFIX = "/partner/";

    private static final String ERROR_BODY_MISMATCH  = "{\"error\":\"certificate_binding_mismatch\"}";
    private static final String ERROR_BODY_NO_CNF     = "{\"error\":\"missing_cnf_claim\"}";
    private static final String ERROR_BODY_NO_CERT    = "{\"error\":\"missing_client_certificate\"}";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
        // WebFilter: corre después de la cadena de Spring Security (orden -100) para tener el
        // JWT ya validado, y antes del filtro de suscripción (orden 11) que consume X-Partner-Id.
        return 10;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith(PARTNER_PATH_PREFIX)) {
            // Solo aplica a rutas de partner
            return chain.filter(exchange);
        }

        // El JWT ya fue validado (firma/issuer/exp) por partnerSecurityChain ANTES de este
        // filtro; si la petición no estuviera autenticada, Spring habría respondido 401.
        // Los GlobalFilter del gateway NO tienen acceso al SecurityContext en resource server
        // stateless: ni ReactiveSecurityContextHolder ni exchange.getPrincipal() lo exponen
        // (el Reactor Context no se propaga a la cadena de routing). Por eso leemos los claims
        // directamente del Bearer — es seguro porque el token ya viene verificado.
        Map<String, Object> claims = parseClaimsDelBearer(exchange);
        if (claims == null) {
            // Sin bearer legible → Spring ya rechazó, o ruta sin auth. Continuar.
            return chain.filter(exchange);
        }

        String cnfThumbprint = extraerCnfThumbprint(claims);
        String partnerId = claims.get("sub") instanceof String s ? s : null;

        if (cnfThumbprint == null) {
            if (testModeEnabled) {
                // En test-mode Keycloak emite tokens sin certificate-bound (sin mTLS real
                // en el token endpoint). Aceptar y propagar el partner-id sin validar binding.
                log.warn("Test-mode: JWT sin cnf.x5t#S256 — saltando RFC 8705 para path={}", path);
                return chain.filter(propagarPartnerId(exchange, partnerId));
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
        log.debug("RFC 8705 OK — partnerId={} path={}", partnerId, path);
        return chain.filter(propagarPartnerId(exchange, partnerId));
    }

    /** Muta el exchange agregando el header {@code X-Partner-Id} downstream. */
    private ServerWebExchange propagarPartnerId(ServerWebExchange exchange, String partnerId) {
        if (partnerId == null) {
            return exchange;
        }
        return exchange.mutate()
                .request(r -> r.header(HEADER_PARTNER_ID, partnerId))
                .build();
    }

    /**
     * Extrae los claims del JWT Bearer del header {@code Authorization} sin re-verificar
     * la firma (ya validada por la cadena de seguridad). Devuelve {@code null} si no hay
     * un Bearer con estructura de JWT.
     */
    private Map<String, Object> parseClaimsDelBearer(ServerWebExchange exchange) {
        String auth = exchange.getRequest().getHeaders().getFirst(org.springframework.http.HttpHeaders.AUTHORIZATION);
        if (auth == null || !auth.startsWith("Bearer ")) {
            return null;
        }
        String[] parts = auth.substring(7).split("\\.");
        if (parts.length < 2) {
            return null;
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            @SuppressWarnings("unchecked")
            Map<String, Object> claims = OBJECT_MAPPER.readValue(payload, Map.class);
            return claims;
        } catch (IllegalArgumentException | java.io.IOException e) {
            log.warn("No se pudieron parsear los claims del Bearer: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extrae el thumbprint del claim {@code cnf.x5t#S256} del JWT (RFC 8705).
     * El claim tiene la forma: {@code {"cnf": {"x5t#S256": "<base64url-sha256>"}}}
     */
    private String extraerCnfThumbprint(Map<String, Object> claims) {
        Object cnfClaim = claims.get("cnf");
        if (!(cnfClaim instanceof Map<?, ?> cnfMap)) {
            return null;
        }
        Object thumbprint = cnfMap.get("x5t#S256");
        return thumbprint instanceof String s ? s : null;
    }

    /**
     * Obtiene el thumbprint SHA-256 del certificado presentado por el cliente.
     *
     * <p>En modo test: lee el header {@code X-Client-Cert-Thumbprint}.
     *
     * <p>En producción (dos fuentes, en orden):
     * <ol>
     *   <li>Conexión directa: {@link SslInfo} del handshake TLS.</li>
     *   <li>Reverse proxy de confianza (nginx mTLS): header {@code X-SSL-Client-Cert}
     *       con el PEM URL-encoded inyectado por nginx. nginx siempre sobreescribe
     *       este header con el cert real del TLS, por lo que un partner no puede
     *       falsificarlo si el gateway solo es alcanzable vía nginx (127.0.0.1).</li>
     * </ol>
     */
    private String obtenerThumbprintPresentado(ServerWebExchange exchange) {
        if (testModeEnabled) {
            String header = exchange.getRequest().getHeaders().getFirst(HEADER_SIMULATED_THUMBPRINT);
            if (header != null) {
                log.debug("Modo test: usando thumbprint del header {}", HEADER_SIMULATED_THUMBPRINT);
            }
            return header;
        }

        // Fuente 1: conexión directa (sin reverse proxy)
        SslInfo sslInfo = exchange.getRequest().getSslInfo();
        if (sslInfo != null) {
            X509Certificate[] peerCerts = sslInfo.getPeerCertificates();
            if (peerCerts != null && peerCerts.length > 0) {
                log.debug("RFC 8705: cert obtenido de SslInfo");
                return calcularThumbprintBase64url(peerCerts[0]);
            }
        }

        // Fuente 2: header inyectado por nginx tras mTLS en el reverse proxy.
        // nginx configura: proxy_set_header X-SSL-Client-Cert $ssl_client_escaped_cert;
        // Esto sobreescribe cualquier header que el cliente intente enviar directamente.
        String certHeader = exchange.getRequest().getHeaders().getFirst(CertForwardFilter.HEADER_X509_CERT);
        if (certHeader != null && !certHeader.isBlank()) {
            log.debug("RFC 8705: cert obtenido del header X-SSL-Client-Cert (nginx mTLS proxy)");
            return calcularThumbprintDesdeHeader(certHeader);
        }

        log.warn("No se pudo obtener el thumbprint del certificado de cliente — SslInfo vacío y sin header X-SSL-Client-Cert");
        return null;
    }

    /**
     * Parsea un PEM URL-encoded (formato inyectado por nginx con {@code $ssl_client_escaped_cert})
     * y calcula el thumbprint SHA-256 en Base64url sin padding.
     */
    private String calcularThumbprintDesdeHeader(String urlEncodedPem) {
        try {
            String pem = URLDecoder.decode(urlEncodedPem, StandardCharsets.UTF_8);
            String b64 = pem
                    .replace("-----BEGIN CERTIFICATE-----", "")
                    .replace("-----END CERTIFICATE-----", "")
                    .replaceAll("\\s+", "");
            byte[] der = Base64.getDecoder().decode(b64);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
            return calcularThumbprintBase64url(cert);
        } catch (Exception e) {
            log.error("Error al parsear certificado del header X-SSL-Client-Cert: {}", e.getMessage());
            return null;
        }
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
