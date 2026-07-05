package bo.com.sintesis.hub.gateway.web.rest.errors;

import bo.com.sintesis.hub.gateway.web.rest.ApiResponseWriter;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * @deprecated Usar {@link ApiResponseAuthEntryPoint}. Esta clase se mantiene
 *             únicamente para evitar romper referencias externas durante la
 *             transición; delega al nuevo entry point.
 */
@Deprecated(since = "ADR-0005", forRemoval = true)
@Component
public class ProblemDetailAuthEntryPoint implements ServerAuthenticationEntryPoint {

    private final ApiResponseAuthEntryPoint delegate;

    public ProblemDetailAuthEntryPoint(ApiResponseWriter writer) {
        this.delegate = new ApiResponseAuthEntryPoint(writer);
    }

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        return delegate.commence(exchange, ex);
    }
}
