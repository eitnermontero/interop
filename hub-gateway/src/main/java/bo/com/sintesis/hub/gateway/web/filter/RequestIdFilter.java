package bo.com.sintesis.hub.gateway.web.filter;

import org.bson.types.ObjectId;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Acepta o genera el identificador de request del cliente ({@code X-Request-Id}).
 * <p>
 * - Si el cliente (o el api-gateway externo) manda {@code X-Request-Id}, se respeta.
 * - Si no, se genera un ID con formato BSON ObjectId hex (24 chars).
 * - Se propaga al backend (request mutado) y al cliente (response header).
 * - Se publica en MDC bajo {@code requestId} para que el pattern {@code %X{requestId}}
 *   del logback lo imprima en cada log linea del thread del filter.
 * <p>
 * Orden {@code HIGHEST_PRECEDENCE + 1}: corre inmediatamente después del
 * {@link CorrelationIdFilter} (que tiene {@code HIGHEST_PRECEDENCE}) para mantener
 * un orden determinista. {@code X-Request-Id} es el ID del cliente para sus propios
 * logs; {@code X-Correlation-ID} es el ID maestro del hub (ADR-0005 §9).
 */
@Component
public class RequestIdFilter implements WebFilter, Ordered {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
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
