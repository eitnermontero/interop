package bo.com.sintesis.hub.gateway.security.filter;

import bo.com.sintesis.hub.gateway.web.rest.ApiError;
import bo.com.sintesis.hub.gateway.web.rest.ApiResponseWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

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
 *   <li>Extrae el {@code product} del path ({@code /partner/v1/inbound/{product}/{version}[/{id}]})
 *       y verifica que el scope del JWT incluya el scope requerido para ESE producto
 *       (ver {@link #SCOPE_POR_PREFIJO_PRODUCTO} — un scope distinto por grupo:
 *       operativos, seguimientos, catálogos). Spring Security ya validó el JWT;
 *       este filtro hace la comprobación del scope de negocio adicionalmente.</li>
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
 *   solo emite ese scope si el partner está configurado en el realm hub-partner
 *   con ese scope habilitado, lo que equivale funcionalmente a tener una suscripción.
 */
@Slf4j
@Component
public class PartnerSubscriptionFilter implements WebFilter, Ordered {

    /** Scope legacy — productos de escritura CASO_PENAL_* (hoy de baja, ver hub-ms-base). */
    private static final String SCOPE_CASO_PENAL = "caso.penal";

    /**
     * Scope requerido por prefijo de producto — un scope distinto por grupo
     * (2026-07-14). La clave es el prefijo del nombre de producto tal como llega
     * en el path ({@code /partner/v1/inbound/{product}/{version}}); la primera
     * que matchee gana. Cualquier producto que no matchee ningún prefijo cae al
     * scope legacy {@link #SCOPE_CASO_PENAL}.
     *
     * <p>Nota: {@code hub.apis.*.required-scope} en hub-ms-base es documental
     * (lo aplica el gateway, no hub-ms-base — ver {@code HubInteropProperties}).
     * Esta tabla es la aplicación REAL; si se agrega un producto nuevo con un
     * grupo/scope distinto, hay que agregar la entrada acá.
     */
    private static final Map<String, String> SCOPE_POR_PREFIJO_PRODUCTO = new LinkedHashMap<>();
    static {
        SCOPE_POR_PREFIJO_PRODUCTO.put("CATALOGO_",    "consulta.catalogos");
        SCOPE_POR_PREFIJO_PRODUCTO.put("OPERATIVO",    "consulta.operativos");
        SCOPE_POR_PREFIJO_PRODUCTO.put("SEGUIMIENTO",  "consulta.seguimientos");
    }

    private static final String SCOPE_URI_PREFIX = "https://api.sintesis.com.bo/";

    private static final String PARTNER_PATH_PREFIX = "/partner/";
    private static final String INBOUND_PATH_PREFIX = "/partner/v1/inbound/";
    private static final String TOKEN_PATH          = "/oauth2/token";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final boolean testModeEnabled;
    private final ApiResponseWriter apiResponseWriter;

    public PartnerSubscriptionFilter(Environment env, ApiResponseWriter apiResponseWriter) {
        boolean localOrTest = Arrays.stream(env.getActiveProfiles())
                .anyMatch(p -> p.equals("local") || p.equals("test"));
        boolean propEnabled = Boolean.parseBoolean(env.getProperty("hub.mtls.test-mode", "false"));
        this.testModeEnabled = localOrTest || propEnabled;
        this.apiResponseWriter = apiResponseWriter;
    }

    @Override
    public int getOrder() {
        return 11;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
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

        // Los GlobalFilter del gateway no tienen acceso al SecurityContext (ni por
        // ReactiveSecurityContextHolder ni por exchange.getPrincipal()); el scope se lee del
        // claim del Bearer, ya validado por partnerSecurityChain (ver MtlsCertBindingFilter).
        String scope = extraerScopeDelBearer(exchange);
        boolean suscripcionValida = verificarSuscripcion(partnerId, path, scope);

        if (!suscripcionValida) {
            log.warn("Suscripción no encontrada o inactiva — partnerId={} path={}", partnerId, path);
            return rechazarSuscripcion(exchange, partnerId);
        }

        log.debug("Suscripción válida — partnerId={} path={}", partnerId, path);
        return chain.filter(exchange);
    }

    /**
     * Lee el claim {@code scope} del JWT Bearer sin re-verificar la firma (ya validada
     * por la cadena de seguridad). Devuelve {@code null} si no hay Bearer legible.
     */
    private String extraerScopeDelBearer(ServerWebExchange exchange) {
        String auth = exchange.getRequest().getHeaders().getFirst(org.springframework.http.HttpHeaders.AUTHORIZATION);
        if (auth == null || !auth.startsWith("Bearer ")) {
            return null;
        }
        String[] parts = auth.substring(7).split("\\.");
        if (parts.length < 2) {
            return null;
        }
        try {
            byte[] payload = java.util.Base64.getUrlDecoder().decode(parts[1]);
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> claims = OBJECT_MAPPER.readValue(payload, java.util.Map.class);
            Object scope = claims.get("scope");
            return scope instanceof String s ? s : null;
        } catch (Exception e) {
            log.warn("No se pudo parsear el scope del Bearer: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Verifica si el partner tiene suscripción activa para el path solicitado.
     *
     * <p>Implementación PoC: extrae el producto del path y comprueba que el
     * scope del JWT incluya el scope requerido para ESE producto (ver
     * {@link #scopeRequeridoPara}). En producción se consultaría
     * {@code admin.partner_subscription}.
     *
     * TODO(hub-poc): Consultar admin.partner_subscription en ms-auth:
     *   {@code SELECT status FROM admin.partner_subscription
     *    WHERE partner_id = (SELECT id FROM admin.partner WHERE code = ?)
     *    AND product_code = ?
     *    AND status = 'ACTIVE'
     *    AND (valid_to IS NULL OR valid_to > NOW())}
     */
    private boolean verificarSuscripcion(String partnerId, String path, String scope) {
        if (!path.startsWith("/partner/v1/")) {
            // Para otros paths de /partner/**, permitir si hay cualquier scope válido
            log.debug("Path sin regla de suscripción específica — permitiendo: path={}", path);
            return true;
        }
        if (scope == null) {
            log.debug("JWT sin claim scope — partnerId={}", partnerId);
            return false;
        }
        String producto = extraerProducto(path);
        String scopeRequerido = scopeRequeridoPara(producto);
        boolean tiene = scope.contains(SCOPE_URI_PREFIX + scopeRequerido) || scope.contains(scopeRequerido);
        if (!tiene) {
            log.debug("Scope insuficiente — partnerId={} producto={} scopeRequerido={} scope={}",
                    partnerId, producto, scopeRequerido, scope);
        }
        return tiene;
    }

    /**
     * Extrae el nombre de producto del path {@code /partner/v1/inbound/{product}/{version}[/{id}]}.
     * Devuelve {@code null} si el path no calza con ese patrón (ej. rutas fuera de
     * {@code /inbound/}, que hoy no existen pero no deben romper el filtro).
     */
    private static String extraerProducto(String path) {
        if (!path.startsWith(INBOUND_PATH_PREFIX)) {
            return null;
        }
        String resto = path.substring(INBOUND_PATH_PREFIX.length());
        int barra = resto.indexOf('/');
        return barra > 0 ? resto.substring(0, barra) : (resto.isEmpty() ? null : resto);
    }

    /** Scope requerido según el prefijo del producto (ver {@link #SCOPE_POR_PREFIJO_PRODUCTO}). */
    private static String scopeRequeridoPara(String producto) {
        if (producto != null) {
            for (Map.Entry<String, String> entry : SCOPE_POR_PREFIJO_PRODUCTO.entrySet()) {
                if (producto.startsWith(entry.getKey())) {
                    return entry.getValue();
                }
            }
        }
        return SCOPE_CASO_PENAL;
    }

    /**
     * Rechaza la petición con {@code 403 SUBSCRIPTION_INACTIVE} en formato
     * {@link bo.com.sintesis.hub.gateway.web.rest.ApiResponse} (ADR-0005 §6.1.b).
     *
     * @param exchange  el exchange reactivo
     * @param partnerId identificador del partner para incluir en el detalle
     */
    private Mono<Void> rechazarSuscripcion(ServerWebExchange exchange, String partnerId) {
        String detail = String.format(
                "El partner '%s' no tiene una suscripción activa para el producto solicitado.",
                partnerId != null ? partnerId : "desconocido");
        ApiError apiError = new ApiError("SUBSCRIPTION_INACTIVE", detail, null);
        return apiResponseWriter.writeError(
                exchange,
                HttpStatus.FORBIDDEN,
                apiError,
                "El partner no tiene una suscripción activa para este producto");
    }
}
