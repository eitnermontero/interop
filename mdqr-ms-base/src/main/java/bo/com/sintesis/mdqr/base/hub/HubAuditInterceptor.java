package bo.com.sintesis.mdqr.base.hub;

import bo.com.sintesis.mdqr.audit.hash.PayloadHasher;
import bo.com.sintesis.mdqr.audit.hub.HubAuditCommand;
import bo.com.sintesis.mdqr.audit.hub.HubAuditService;
import bo.com.sintesis.mdqr.audit.hub.IdempotencyKeyConflictException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Interceptor Spring MVC que envuelve {@code POST /api/qr/decode} para registrar
 * auditoría completa del hub: hash del request, hash del response, latencia,
 * partner_id, correlation_id e idempotency_key, todo en una sola transacción
 * junto con el evento de outbox para facturación.
 *
 * <p>Garantías de resiliencia: si {@link HubAuditService#record(HubAuditCommand)}
 * lanza cualquier excepción (incluido fallo de BD o Vault), se registra en WARN
 * y la respuesta de negocio sale intacta. El negocio no falla por fallo de auditoría.
 *
 * <p>Los wrappers {@link ContentCachingRequestWrapper} y
 * {@link ContentCachingResponseWrapper} son aplicados por {@link HubWebMvcConfig}
 * mediante un filtro de servlet dedicado, de modo que el body sea legible aquí
 * sin consumir el stream original.
 *
 * <p>Atributos de request usados como mecanismo de paso entre pre y post:
 * <ul>
 *   <li>{@code hub.audit.startMs} — timestamp de inicio en milisegundos.</li>
 *   <li>{@code hub.audit.auditId} — UUID generado en pre para correlacionar.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HubAuditInterceptor implements HandlerInterceptor {

    /** Atributo de request: timestamp de inicio (Long). */
    private static final String ATTR_START_MS  = "hub.audit.startMs";
    /** Atributo de request: UUID del registro de auditoría (UUID). */
    private static final String ATTR_AUDIT_ID  = "hub.audit.auditId";

    /** Header puesto por el gateway con el identificador del partner. */
    private static final String HEADER_PARTNER_ID     = "X-Partner-Id";
    /** Header puesto por RequestIdFilter del gateway para correlación distribuida (ADR-0005). */
    private static final String HEADER_CORRELATION_ID = "X-Correlation-ID";
    /** Header de idempotencia enviado por el caller (ADR-0005). */
    private static final String HEADER_IDEMPOTENCY    = "X-Idempotency-Key";

    /** Valor por defecto cuando el gateway no propagó el partner_id. */
    private static final String ANONYMOUS_PARTNER = "anonymous";

    /**
     * Atributo de request que pone {@link bo.com.sintesis.mdqr.base.hub.inbound.DispatcherController}
     * con el código del producto (ej. {@code "CASO_PENAL"}).
     * Si no está presente se usa {@code "UNKNOWN"} como fallback.
     */
    private static final String ATTR_AUDIT_PRODUCT = "hub.audit.product";

    private static final String DIRECTION    = "IN";
    private static final String AGGREGATE    = "HUB_TRANSACTION";

    private final HubAuditService hubAuditService;
    private final PayloadHasher payloadHasher;

    // ─── Pre-handle ──────────────────────────────────────────────────────────

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        request.setAttribute(ATTR_START_MS, System.currentTimeMillis());
        request.setAttribute(ATTR_AUDIT_ID, UUID.randomUUID());
        return true;
    }

    // ─── After-completion ────────────────────────────────────────────────────

    /**
     * Se llama siempre al terminar el handler (incluso si hay excepción).
     * Aquí se construye el {@link HubAuditCommand} y se llama a
     * {@link HubAuditService#record(HubAuditCommand)}.
     *
     * <p>Si {@code ex} no es nulo significa que el handler lanzó excepción;
     * aun así se registra la auditoría con el HTTP status resultante
     * (el {@link ContentCachingResponseWrapper} ya tiene el status definitivo).
     */
    @Override
    public void afterCompletion(@NonNull HttpServletRequest request,
                                @NonNull HttpServletResponse response,
                                @NonNull Object handler,
                                @Nullable Exception ex) {
        try {
            registrarAuditoria(request, response);
        } catch (IdempotencyKeyConflictException idem) {
            // Propagamos esta excepción para que llegue al GlobalExceptionHandler
            // y devuelva 409 al caller. El negocio ya procesó la respuesta, pero
            // el registro de auditoría duplicado está bloqueado correctamente.
            log.warn("Idempotency key duplicada en auditoría hub: {}", idem.getIdempotencyKey());
            throw idem;
        } catch (Exception e) {
            // Cualquier otro fallo de auditoría → no bloquear la respuesta de negocio
            log.warn("Fallo no crítico en auditoría hub — response de negocio no afectada: {}", e.getMessage(), e);
        }
    }

    // ─── Lógica privada ───────────────────────────────────────────────────────

    private void registrarAuditoria(HttpServletRequest request, HttpServletResponse response) {
        // Recuperar tiempos y UUIDs registrados en preHandle
        Long startMs = (Long) request.getAttribute(ATTR_START_MS);
        UUID auditId = (UUID) request.getAttribute(ATTR_AUDIT_ID);
        if (startMs == null || auditId == null) {
            log.debug("Atributos de auditoría hub no encontrados — omitiendo registro");
            return;
        }

        int latencyMs = (int) Math.min(System.currentTimeMillis() - startMs, Integer.MAX_VALUE);
        int httpStatus = response.getStatus();

        // Headers de correlación y negocio
        String partnerId = obtenerHeader(request, HEADER_PARTNER_ID, ANONYMOUS_PARTNER);
        String correlationId = obtenerHeader(request, HEADER_CORRELATION_ID, null);
        String idempotencyKey = obtenerHeader(request, HEADER_IDEMPOTENCY, null);

        // Producto dinámico: lo pone DispatcherController como atributo del request.
        // Fallback a "UNKNOWN" si el interceptor se activa fuera del dispatcher genérico.
        Object productAttr = request.getAttribute(ATTR_AUDIT_PRODUCT);
        String product = (productAttr instanceof String s && !s.isBlank()) ? s : "UNKNOWN";
        String endpoint = request.getRequestURI();

        // Hashes del cuerpo — usa ContentCachingWrapper puesto por HubWebMvcConfig
        String requestHash  = calcularHashRequest(request);
        String responseHash = calcularHashResponse(response);

        Map<String, Object> outboxPayload = Map.of(
                "partner",   partnerId,
                "product",   product,
                "units",     1,
                "status",    httpStatus,
                "ts",        Instant.now().toString()
        );

        HubAuditCommand cmd = new HubAuditCommand(
                auditId,
                DIRECTION,
                partnerId,
                product,
                endpoint,
                request.getMethod(),
                httpStatus,
                requestHash,
                responseHash,
                latencyMs,
                1,                // billableUnits: 1 por operación inbound
                idempotencyKey,
                correlationId,
                Instant.now(),
                AGGREGATE,
                auditId.toString(),
                outboxPayload
        );

        log.debug("Registrando auditoría hub: auditId={} partnerId={} status={} latencyMs={}",
                auditId, partnerId, httpStatus, latencyMs);

        hubAuditService.record(cmd);

        log.debug("Auditoría hub registrada: auditId={}", auditId);
    }

    /**
     * Calcula el hash SHA-256 del cuerpo del request.
     * Usa {@link ContentCachingRequestWrapper} aplicado por el filtro de servlet
     * configurado en {@link HubWebMvcConfig}.
     * Si el body está vacío (ej. GET, o body ya consumido), retorna hash de "{}".
     */
    private String calcularHashRequest(HttpServletRequest request) {
        if (request instanceof ContentCachingRequestWrapper wrapper) {
            byte[] body = wrapper.getContentAsByteArray();
            if (body.length > 0) {
                try {
                    return payloadHasher.hash(body);
                } catch (Exception e) {
                    // Body no es JSON válido (multipart, binario, etc.) → hash raw
                    log.debug("Body del request no es JSON canónico — usando hashRaw: {}", e.getMessage());
                    return payloadHasher.hashRaw(body);
                }
            }
        }
        // Sin body: hash de objeto JSON vacío
        return payloadHasher.hash("{}");
    }

    /**
     * Calcula el hash SHA-256 del cuerpo del response.
     * El {@link ContentCachingResponseWrapper} capturó el cuerpo durante la escritura.
     * Importante: llamar a {@code copyBodyToResponse()} está a cargo del filtro
     * wrapper ({@link HubWebMvcConfig#cachingFilter()}), no de este interceptor.
     */
    private String calcularHashResponse(HttpServletResponse response) {
        if (response instanceof ContentCachingResponseWrapper wrapper) {
            byte[] body = wrapper.getContentAsByteArray();
            if (body.length > 0) {
                try {
                    return payloadHasher.hash(body);
                } catch (Exception e) {
                    log.debug("Body del response no es JSON canónico — usando hashRaw: {}", e.getMessage());
                    return payloadHasher.hashRaw(body);
                }
            }
        }
        return payloadHasher.hash("{}");
    }

    private String obtenerHeader(HttpServletRequest request, String headerName, String defaultValue) {
        String value = request.getHeader(headerName);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }
}
