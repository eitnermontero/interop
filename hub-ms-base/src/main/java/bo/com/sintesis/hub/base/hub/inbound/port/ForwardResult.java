package bo.com.sintesis.hub.base.hub.inbound.port;

import java.util.Map;

/**
 * Resultado inmutable del reenvío inbound producido por un {@link InboundPort}.
 *
 * @param ok         {@code true} si el sistema destino aceptó la operación
 * @param httpStatus código HTTP que representa el resultado (201, 400, 503, etc.)
 * @param data       mapa de datos de respuesta del sistema destino; nunca {@code null},
 *                   puede ser {@link Map#of()} si no hay datos
 * @param message    mensaje legible para el caller (éxito o causa del fallo)
 */
public record ForwardResult(
        boolean ok,
        int httpStatus,
        Map<String, Object> data,
        String message
) {}
