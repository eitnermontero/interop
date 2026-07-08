package bo.com.sintesis.hub.base.hub.inbound.port;

import java.util.List;
import java.util.Map;

/**
 * Resultado inmutable del reenvío inbound producido por un {@link InboundPort}.
 *
 * @param ok         {@code true} si el sistema destino aceptó la operación
 * @param httpStatus código HTTP que representa el resultado (201, 400, 503, etc.)
 * @param data       datos de respuesta del sistema destino; nunca {@code null},
 *                   puede ser {@link Map#of()} si no hay datos. La forma real depende del
 *                   destino: un objeto JSON deserializa a {@link Map}, un array JSON
 *                   deserializa a {@link List} (p. ej. catálogos GET que responden
 *                   {@code [...]} en vez de {@code {...}}) — de ahí el tipo {@link Object}
 *                   en vez de {@code Map<String, Object>}.
 * @param message    mensaje legible para el caller (éxito o causa del fallo)
 */
public record ForwardResult(
        boolean ok,
        int httpStatus,
        Object data,
        String message
) {}
