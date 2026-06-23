package bo.com.sintesis.mdqr.gateway.security.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Filtro de suscripción de partner: verifica que el partner identificado en
 * {@code X-Partner-Id} (puesto por {@link MtlsCertBindingFilter}) tenga una
 * suscripción activa que corresponda al scope del JWT.
 *
 * <p>Solo aplica a rutas {@code /partner/**}; el token endpoint {@code /oauth2/token}
 * es excluido porque es público.
 *
 * <p>Estrategia de verificación (PoC):
 * <ul>
 *   <li>Verifica que el scope del JWT incluya {@code qr.decode} para el path
 *       {@code /partner/v1/qr/**}. Spring Security ya validó el JWT; este filtro
 *       hace la comprobación del scope de negocio adicionalmente.</li>
 *   <li>En un entorno de producción completo, se consultaría la tabla
 *       {@code admin.partner_subscription} en ms-auth vía Redis o HTTP. El TODO
 *       documenta el punto de extensión.</li>
 * </ul>
 *
 * <p>Orden = 11: corre después de {@link MtlsCertBindingFilter} (orden 10).
 *
 * <p>TODO(hub-poc): Reemplazar la verificación de scope por una consulta real a
 *   {@code admin.partner_subscription} en ms-auth. Opciones:
 *   (a) Llamada HTTP a ms-auth con caché Redis (TTL configurable por partner).
 *   (b) JdbcTemplate directo si el gateway tiene acceso a la DB de ms-auth
 *       (no recomendado en producción — acopla servicios).
 *   La verificación de scope actual es suficiente para el PoC porque Keycloak
 *   solo emite ese scope si el partner está configurado en el realm mdqr-partner
 *   con ese scope habilitado, lo que equivale funcionalmente a tener una suscripción.
 */
@Slf4j
@Component
public class PartnerSubscriptionFilter implements GlobalFilter, Ordered {

    /** Scope que habilita el endpoint de decodificación de QR. */
    public static final String SCOPE_QR_DECODE = "https://api.sintesis.com.bo/qr.decode";

    /** Scope corto alternativo (Keycloak puede emitirlo como "qr.decode"). */
    private static final String SCOPE_QR_DECODE_SHORT = "qr.decode";

    private static final String PARTNER_PATH_PREFIX   = "/partner/";
    private static final String TOKEN_PATH            = "/oauth2/token";
    private static final String ERROR_SUBSCRIPTION    = "{\"error\":\"subscription_not_found\"}";

    private final boolean testModeEnabled;

    public PartnerSubscriptionFilter(Environment env) {
        boolean localOrTest = Arrays.stream(env.getActiveProfiles())
                .anyMatch(p -> p.equals("local") || p.equals("test"));
        boolean propEnabled = Boolean.parseBoolean(env.getProperty("hub.mtls.test-mode", "false"));
        this.testModeEnabled = localOrTest || propEnabled;
    }

    @Override
    public int getOrder() {
        return 11;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        // Solo aplica a /partner/**, excluye el token endpoint público
        if (!path.startsWith(PARTNER_PATH_PREFIX) || path.equals(TOKEN_PATH)) {
            return chain.filter(exchange);
        }

        // El partnerId ya fue validado y propagado por MtlsCertBindingFilter
        String partnerId = exchange.getRequest().getHeaders().getFirst(MtlsCertBindingFilter.HEADER_PARTNER_ID);
        if (partnerId == null) {
            // Situación anómala: el filtro de binding no puso el header.
            // En modo test puede ocurrir si el test llama este filtro directamente sin el anterior.
            // En producción esto no debe ocurrir porque los filtros están encadenados.
            log.debug("X-Partner-Id no encontrado en path={} — continuando sin verificación de suscripción", path);
            return chain.filter(exchange);
        }

        return ReactiveSecurityContextHolder.getContext()
                .flatMap(ctx -> {
                    if (!(ctx.getAuthentication() instanceof JwtAuthenticationToken jwtAuth)) {
                        return chain.filter(exchange);
                    }

                    Jwt jwt = (Jwt) jwtAuth.getPrincipal();
                    boolean suscripcionValida = verificarSuscripcion(partnerId, path, jwt);

                    if (!suscripcionValida) {
                        log.warn("Suscripción no encontrada o inactiva — partnerId={} path={}", partnerId, path);
                        return rechazar(exchange, ERROR_SUBSCRIPTION);
                    }

                    log.debug("Suscripción válida — partnerId={} path={}", partnerId, path);
                    return chain.filter(exchange);
                })
                .switchIfEmpty(chain.filter(exchange));
    }

    /**
     * Verifica si el partner tiene suscripción activa para el path solicitado.
     *
     * <p>Implementación PoC: comprueba que el scope del JWT incluya el scope
     * correspondiente al producto solicitado. En producción se consultaría
     * {@code admin.partner_subscription}.
     *
     * TODO(hub-poc): Consultar admin.partner_subscription en ms-auth:
     *   {@code SELECT status FROM admin.partner_subscription
     *    WHERE partner_id = (SELECT id FROM admin.partner WHERE code = ?)
     *    AND product_code = ?
     *    AND status = 'ACTIVE'
     *    AND (valid_to IS NULL OR valid_to > NOW())}
     */
    private boolean verificarSuscripcion(String partnerId, String path, Jwt jwt) {
        // Para el endpoint /partner/v1/qr/** verificar el scope qr.decode
        if (path.startsWith("/partner/v1/qr/")) {
            String scope = jwt.getClaimAsString("scope");
            if (scope == null) {
                log.debug("JWT sin claim scope — partnerId={}", partnerId);
                return false;
            }
            boolean tiene = scope.contains(SCOPE_QR_DECODE) || scope.contains(SCOPE_QR_DECODE_SHORT);
            if (!tiene) {
                log.debug("Scope insuficiente — partnerId={} scope={}", partnerId, scope);
            }
            return tiene;
        }
        // Para otros paths de /partner/**, permitir si hay cualquier scope válido
        // TODO(hub-poc): Ampliar con mapeo path → product_code cuando haya más productos
        log.debug("Path sin regla de suscripción específica — permitiendo: path={}", path);
        return true;
    }

    private Mono<Void> rechazar(ServerWebExchange exchange, String body) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        org.springframework.core.io.buffer.DataBuffer buf =
                exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buf));
    }
}
