package bo.com.sintesis.mdqr.gateway.web.filter;

import org.bson.types.ObjectId;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Acepta o genera el correlation id global del request.
 * <p>
 * - Si el cliente (o el api-gateway externo) manda {@code X-Request-Id}, se respeta.
 * - Si no, se genera un UUID.
 * - Se propaga al backend (request mutado) y al cliente (response header).
 * - Se publica en MDC bajo {@code requestId} para que el pattern {@code %X{requestId}}
 *   del logback lo imprima en cada log linea del thread del filter.
 * <p>
 * Order muy bajo (Ordered.HIGHEST_PRECEDENCE) para correr antes del resto de filters
 * — asi los demas logs ya tienen el id en MDC.
 */
@Component
public class RequestIdFilter implements WebFilter, Ordered {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
        // Cuando el cliente no manda X-Request-Id, generamos uno con el mismo formato
        // que el cart-service usa para sus tx codes: BSON ObjectId hex (24 chars).
        String requestId = (incoming != null && !incoming.isBlank()) ? incoming : new ObjectId().toHexString();

        ServerWebExchange mutated = exchange.mutate()
            .request(b -> b.header(HEADER, requestId))
            .build();
        mutated.getResponse().getHeaders().set(HEADER, requestId);
        mutated.getAttributes().put(MDC_KEY, requestId);

        MDC.put(MDC_KEY, requestId);
        return chain.filter(mutated)
            .doFinally(s -> MDC.remove(MDC_KEY));
    }
}
