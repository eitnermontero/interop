package bo.com.sintesis.hub.gateway.web.rest.errors;

import bo.com.sintesis.hub.gateway.web.rest.ApiError;
import bo.com.sintesis.hub.gateway.web.rest.ApiResponseWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Manejador de acceso denegado del gateway (ADR-0005 §6.1.b).
 *
 * <p>Emite {@code 403 INSUFFICIENT_SCOPE} en formato {@link bo.com.sintesis.hub.gateway.web.rest.ApiResponse}
 * cuando un token válido y autenticado no contiene el scope requerido por el
 * recurso solicitado.
 *
 * <p>Se registra en {@code partnerSecurityChain} y en la parte API de
 * {@code adminSecurityChain} (ver {@link bo.com.sintesis.hub.gateway.config.SecurityConfiguration}).
 *
 * <p>El {@code correlation_id} se obtiene del atributo del exchange puesto por
 * {@link bo.com.sintesis.hub.gateway.web.filter.CorrelationIdFilter}. Si no está
 * disponible (defensa en profundidad), {@link ApiResponseWriter} genera uno nuevo.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiResponseAccessDeniedHandler implements ServerAccessDeniedHandler {

    private static final String CODE    = "INSUFFICIENT_SCOPE";
    private static final String MESSAGE = "El token no autoriza el acceso a este recurso";
    private static final String DETAIL  =
            "El token presentado no incluye el scope requerido por este endpoint.";

    private final ApiResponseWriter writer;

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException ex) {
        log.debug("Acceso denegado — path={} causa={}",
                exchange.getRequest().getPath().value(), ex.getMessage());

        ApiError apiError = new ApiError(CODE, DETAIL, null);
        return writer.writeError(exchange, HttpStatus.FORBIDDEN, apiError, MESSAGE);
    }
}
