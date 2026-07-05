package bo.com.sintesis.hub.gateway.web.filter;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Filtro de correlación del hub (ADR-0005 §5.1).
 *
 * <p>Lee el header {@code X-Correlation-ID} del request entrante; si falta o
 * está en blanco, genera un UUID nuevo. El valor resultante se:
 * <ul>
 *   <li>Almacena en el atributo {@link #ATTR_CORRELATION_ID} del exchange para
 *       que los manejadores de error (entry points, access denied handler,
 *       exception handler) puedan incluirlo en la respuesta.</li>
 *   <li>Propaga hacia los servicios downstream mutando el request (así el
 *       microservicio lo recibe y puede registrarlo en {@code hub_audit_log}).</li>
 *   <li>Escribe en el header {@code X-Correlation-ID} de la respuesta para que
 *       el partner lo reciba en todos los caminos (éxito y error).</li>
 *   <li>Publica en el MDC bajo la clave {@code correlationId} para que el patrón
 *       {@code %X{correlationId}} de logback aparezca en todas las líneas de log
 *       del hilo de este request.</li>
 * </ul>
 *
 * <p>Orden {@link Ordered#HIGHEST_PRECEDENCE}: corre <em>antes</em> de cualquier
 * filtro de seguridad, garantizando que el {@code correlation_id} esté disponible
 * incluso cuando un error de seguridad rechaza el request.
 *
 * <p>{@link RequestIdFilter} (que maneja {@code X-Request-Id}) corre en
 * {@code HIGHEST_PRECEDENCE + 1} para un orden determinista.
 */
@Component
public class CorrelationIdFilter implements WebFilter, Ordered {

    /** Nombre del header HTTP de correlación del hub. */
    public static final String HEADER_CORRELATION_ID = "X-Correlation-ID";

    /**
     * Clave del atributo en el {@code ServerWebExchange} donde se almacena el
     * {@code correlationId} resuelto. Usada por {@link bo.com.sintesis.hub.gateway.web.rest.ApiResponseWriter}
     * y los manejadores de error del gateway.
     */
    public static final String ATTR_CORRELATION_ID = "hub.correlationId";

    /** Clave MDC para el patrón {@code %X{correlationId}} de logback. */
    public static final String MDC_KEY = "correlationId";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER_CORRELATION_ID);
        String correlationId = (incoming != null && !incoming.isBlank())
                ? incoming
                : UUID.randomUUID().toString();

        // Almacenar en atributo del exchange (disponible para manejadores de error)
        exchange.getAttributes().put(ATTR_CORRELATION_ID, correlationId);

        // Mutar el request para propagar downstream (ms-base lo audita)
        ServerWebExchange mutated = exchange.mutate()
                .request(r -> r.header(HEADER_CORRELATION_ID, correlationId))
                .build();

        // Escribir en el header de respuesta (siempre presente, éxito o error)
        mutated.getResponse().getHeaders().set(HEADER_CORRELATION_ID, correlationId);

        MDC.put(MDC_KEY, correlationId);
        return chain.filter(mutated)
                .doFinally(s -> MDC.remove(MDC_KEY));
    }
}
