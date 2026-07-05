package bo.com.sintesis.hub.audit.hub;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Command inmutable con todos los datos necesarios para la escritura atómica
 * de un registro de auditoría del hub junto con su evento de outbox.
 *
 * <p>El caller es responsable de:
 * <ul>
 *   <li>Generar el {@code id} (UUID v4) antes de llamar a {@link HubAuditService}.</li>
 *   <li>Calcular {@code requestHash} y {@code responseHash} con {@link bo.com.sintesis.hub.audit.hash.PayloadHasher}.</li>
 *   <li>Proporcionar {@code idempotencyKey} si el endpoint lo soporta; {@code null} si no aplica.</li>
 * </ul>
 *
 * @param id              UUID generado por el caller antes de llamar al servicio
 * @param direction       dirección del tráfico: {@code "IN"} (partner llama al hub)
 *                        o {@code "OUT"} (hub llama a proveedor externo)
 * @param partnerId       identificador del partner (clave de la cadena de hashes)
 * @param product         código del producto del hub (p.ej. {@code "QR_DECODE"})
 * @param endpoint        endpoint HTTP invocado
 * @param httpMethod      método HTTP (GET, POST, PUT, etc.)
 * @param httpStatus      código de respuesta HTTP
 * @param requestHash     SHA-256 hex (64 chars) del payload canonicalizado de la petición
 * @param responseHash    SHA-256 hex (64 chars) del payload canonicalizado de la respuesta
 * @param latencyMs       latencia total en milisegundos (>= 0)
 * @param billableUnits   unidades facturables (>= 0; default 1 para la mayoría de operaciones)
 * @param idempotencyKey  clave de idempotencia presentada por el caller; puede ser {@code null}
 * @param correlationId   ID de correlación para tracing distribuido; puede ser {@code null}
 * @param timestamp       instante exacto de la transacción
 * @param aggregateType   tipo del agregado para el evento de outbox (p.ej. {@code "HUB_TRANSACTION"})
 * @param aggregateId     ID del agregado para el evento de outbox
 * @param outboxPayload   datos de medición para el evento de facturación (partner, product, units, ts)
 */
public record HubAuditCommand(
        UUID id,
        String direction,
        String partnerId,
        String product,
        String endpoint,
        String httpMethod,
        int httpStatus,
        String requestHash,
        String responseHash,
        int latencyMs,
        int billableUnits,
        String idempotencyKey,
        String correlationId,
        Instant timestamp,
        String aggregateType,
        String aggregateId,
        Map<String, Object> outboxPayload
) {
}
