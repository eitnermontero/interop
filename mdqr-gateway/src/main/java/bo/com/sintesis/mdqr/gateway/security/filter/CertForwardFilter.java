package bo.com.sintesis.mdqr.gateway.security.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.SslInfo;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Base64;

/**
 * Reenvía el certificado X.509 del cliente (obtenido del handshake mTLS) al
 * upstream Keycloak en el token endpoint de partner.
 *
 * <p>Solo actúa sobre {@code POST /oauth2/token}. Keycloak lee el certificado
 * del header {@code X-SSL-Client-Cert} (URL-encoded PEM) cuando está configurado
 * con el X.509 Certificate Lookup Provider en modo proxy.
 *
 * <p>Esto permite que Keycloak emita tokens con el claim {@code cnf.x5t#S256}
 * (RFC 8705), que luego valida {@link MtlsCertBindingFilter}.
 *
 * <p>Orden = 5: corre antes del {@link MtlsCertBindingFilter} (orden 10) y
 * antes del auth filter de Spring Security sobre el upstream.
 */
@Slf4j
@Component
public class CertForwardFilter implements GlobalFilter, Ordered {

    static final String PARTNER_TOKEN_PATH = "/oauth2/token";
    static final String HEADER_X509_CERT = "X-SSL-Client-Cert";

    @Override
    public int getOrder() {
        return 5;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!PARTNER_TOKEN_PATH.equals(exchange.getRequest().getPath().value())) {
            return chain.filter(exchange);
        }

        SslInfo sslInfo = exchange.getRequest().getSslInfo();
        if (sslInfo == null) {
            return chain.filter(exchange);
        }

        X509Certificate[] peerCerts = sslInfo.getPeerCertificates();
        if (peerCerts == null || peerCerts.length == 0) {
            log.debug("CertForward: /oauth2/token sin certificado de cliente — mTLS no presentado");
            return chain.filter(exchange);
        }

        try {
            String pem = toUrlEncodedPem(peerCerts[0]);
            log.debug("CertForward: reenviando certificado de cliente a Keycloak para RFC 8705");
            ServerWebExchange mutated = exchange.mutate()
                    .request(r -> r.header(HEADER_X509_CERT, pem))
                    .build();
            return chain.filter(mutated);
        } catch (Exception e) {
            log.warn("CertForward: no se pudo encodear el certificado del cliente: {}", e.getMessage());
            return chain.filter(exchange);
        }
    }

    private String toUrlEncodedPem(X509Certificate cert) throws Exception {
        byte[] encoded = cert.getEncoded();
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                           .encodeToString(encoded);
        String pem = "-----BEGIN CERTIFICATE-----\n" + b64 + "\n-----END CERTIFICATE-----";
        return URLEncoder.encode(pem, StandardCharsets.UTF_8);
    }
}
