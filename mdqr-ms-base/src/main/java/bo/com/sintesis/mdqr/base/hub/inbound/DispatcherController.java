package bo.com.sintesis.mdqr.base.hub.inbound;

import bo.com.sintesis.mdqr.base.hub.inbound.contract.ConstraintViolation;
import bo.com.sintesis.mdqr.base.hub.inbound.contract.ContractDefinition;
import bo.com.sintesis.mdqr.base.hub.inbound.contract.ContractRegistry;
import bo.com.sintesis.mdqr.base.hub.inbound.contract.ContractValidator;
import bo.com.sintesis.mdqr.base.hub.inbound.port.ForwardResult;
import bo.com.sintesis.mdqr.base.web.rest.ApiError;
import bo.com.sintesis.mdqr.base.web.rest.ApiResponse;
import bo.com.sintesis.mdqr.base.web.rest.ApiViolation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Controlador genérico del motor inbound del hub de interoperabilidad.
 *
 * <p>Expone {@code POST /api/inbound/{product}/{version}} como punto de entrada
 * único para todos los productos inbound. El flujo para cada llamada es:
 * <ol>
 *   <li>Resolución del contrato en {@link ContractRegistry} — 403 si no existe.</li>
 *   <li>Validación del payload contra el contrato — 400 con violations si falla.</li>
 *   <li>Registro del atributo {@code hub.audit.product} en el request para que
 *       {@link bo.com.sintesis.mdqr.base.hub.HubAuditInterceptor} lo recupere.</li>
 *   <li>Delegación al {@link ForwardingGateway} — usa el resultado para determinar el status HTTP.</li>
 *   <li>Construcción del {@link ApiResponse} y propagación de {@code X-Correlation-ID}.</li>
 * </ol>
 *
 * <p>Auditoría: el {@link bo.com.sintesis.mdqr.base.hub.HubAuditInterceptor} intercepta
 * todas las rutas {@code /api/inbound/**} en {@code afterCompletion()} y registra
 * el hash del request, el hash del response, la latencia y el evento de outbox.
 */
@Slf4j
@RestController
@RequestMapping("/api/inbound")
@RequiredArgsConstructor
public class DispatcherController {

    /** Nombre del atributo de request que informa el producto al interceptor de auditoría. */
    static final String ATTR_AUDIT_PRODUCT = "hub.audit.product";

    private final ContractRegistry contractRegistry;
    private final ContractValidator contractValidator;
    private final ForwardingGateway forwardingGateway;

    /**
     * Endpoint genérico inbound del hub.
     *
     * @param product        código del producto (path variable, ej. {@code CASO_PENAL})
     * @param version        versión del contrato (path variable, ej. {@code v1})
     * @param payload        body del request ya parseado como mapa
     * @param partnerId      identificador del partner (header {@code X-Partner-Id})
     * @param correlationId  ID de correlación (header {@code X-Correlation-ID}); se genera si ausente
     * @param idempotencyKey clave de idempotencia (header {@code X-Idempotency-Key})
     * @param httpRequest    request servlet para establecer atributos que leerá el interceptor
     * @param httpResponse   response servlet para agregar {@code X-Correlation-ID}
     * @return respuesta envuelta en {@link ApiResponse}
     */
    @PostMapping("/{product}/{version}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> dispatch(
            @PathVariable String product,
            @PathVariable String version,
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "X-Partner-Id", required = false) String partnerId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        // ── 1. Generar correlationId si el caller no lo envió ─────────────────
        String effectiveCorrelationId = (correlationId != null && !correlationId.isBlank())
                ? correlationId
                : UUID.randomUUID().toString();

        log.info("Inbound dispatch: product={} version={} partner={} correlationId={}",
                product, version, partnerId, effectiveCorrelationId);

        // ── 2. Establecer atributo para el interceptor de auditoría ───────────
        httpRequest.setAttribute(ATTR_AUDIT_PRODUCT, product);

        // ── 3. Lookup del contrato ────────────────────────────────────────────
        var contractOpt = contractRegistry.lookup(product, version);
        if (contractOpt.isEmpty()) {
            log.warn("Producto no registrado: {}/{} — partner={}", product, version, partnerId);
            ApiError apiError = new ApiError(
                    "PRODUCT_NOT_AUTHORIZED",
                    "El producto solicitado no está autorizado o no existe: " + product + "/" + version,
                    List.of()
            );
            httpResponse.setHeader("X-Correlation-ID", effectiveCorrelationId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error(403,
                            "Producto no autorizado", apiError, effectiveCorrelationId));
        }

        ContractDefinition contract = contractOpt.get();

        // ── 4. Validación del payload ─────────────────────────────────────────
        List<ConstraintViolation> violations = contractValidator.validate(payload, contract);
        if (!violations.isEmpty()) {
            log.warn("Payload inválido para {}/{}: {} violaciones — partner={}",
                    product, version, violations.size(), partnerId);
            List<ApiViolation> apiViolations = violations.stream()
                    .map(v -> new ApiViolation(v.field(), v.message()))
                    .toList();
            ApiError apiError = new ApiError(
                    "VALIDATION_ERROR",
                    "El payload no cumple el contrato del producto " + product + "/" + version,
                    apiViolations
            );
            httpResponse.setHeader("X-Correlation-ID", effectiveCorrelationId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(400, "Error de validación", apiError, effectiveCorrelationId));
        }

        // ── 5. Delegación al ForwardingGateway ───────────────────────────────
        ForwardResult result = forwardingGateway.forward(product, version, payload, effectiveCorrelationId);

        // ── 6. Construir respuesta ────────────────────────────────────────────
        httpResponse.setHeader("X-Correlation-ID", effectiveCorrelationId);

        if (result.ok()) {
            return ResponseEntity.status(result.httpStatus())
                    .body(ApiResponse.ok(result.httpStatus(), result.message(),
                            result.data(), effectiveCorrelationId));
        } else {
            ApiError apiError = new ApiError(
                    "FORWARD_ERROR",
                    result.message(),
                    List.of()
            );
            return ResponseEntity.status(result.httpStatus())
                    .body(ApiResponse.error(result.httpStatus(), result.message(),
                            apiError, effectiveCorrelationId));
        }
    }
}
