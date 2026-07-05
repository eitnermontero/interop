package bo.com.sintesis.hub.gateway.web.filter;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Una linea por request — reemplaza el spam de DEBUG de Spring Cloud Gateway.
 * Niveles:
 * <ul>
 *   <li>{@code TRACE} para health endpoints (ruido constante de Consul/Docker probes,
 *       el LB lo necesita pero no aporta a logs operativos).</li>
 *   <li>{@code INFO} para requests normales (2xx/3xx).</li>
 *   <li>{@code WARN} para 4xx (errores de cliente) y 5xx (errores de server).</li>
 * </ul>
 * Corre despues de {@link RequestIdFilter} para tener el requestId en MDC.
 */
@Slf4j
@Component
public class AccessLogFilter implements WebFilter, Ordered {

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long start = System.currentTimeMillis();
        String method = exchange.getRequest().getMethod().name();
        String path = exchange.getRequest().getURI().getRawPath();
        String requestId = (String) exchange.getAttributes().get(RequestIdFilter.MDC_KEY);
        boolean isHealth = path != null && (path.startsWith("/management/") || path.startsWith("/actuator/"));

        return chain.filter(exchange).doFinally(signal -> {
            long elapsed = System.currentTimeMillis() - start;
            int status = exchange.getResponse().getStatusCode() != null
                ? exchange.getResponse().getStatusCode().value()
                : 0;
            if (requestId != null) {
                MDC.put(RequestIdFilter.MDC_KEY, requestId);
            }
            try {
                // Health endpoints loguean SIEMPRE en TRACE. Las transiciones up/down de
                // upstreams las reporta ServiceAvailabilityLogger una vez por cambio.
                if (isHealth) {
                    log.trace("{} {} -> {} ({}ms)", method, path, status, elapsed);
                } else if (status >= 400) {
                    log.warn("{} {} -> {} ({}ms)", method, path, status, elapsed);
                } else {
                    log.info("{} {} -> {} ({}ms)", method, path, status, elapsed);
                }
            } finally {
                MDC.remove(RequestIdFilter.MDC_KEY);
            }
        });
    }
}
