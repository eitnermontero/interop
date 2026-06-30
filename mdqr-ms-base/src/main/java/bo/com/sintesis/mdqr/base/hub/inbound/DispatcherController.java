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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Controlador genérico del motor inbound del hub de interoperabilidad.
 *
 * <p>Expone dos endpoints:
 * <ul>
 *   <li>{@code POST /api/inbound/{product}/{version}} — crear recurso.</li>
 *   <li>{@code PATCH /api/inbound/{product}/{version}/{id}} — editar recurso.
 *       Resuelve el contrato {@code {product}_EDITAR/{version}} e inyecta el
 *       {@code {id}} del path bajo el campo {@link ContractDefinition#resourceIdField()}.</li>
 * </ul>
 *
 * <p>Flujo común (POST y PATCH):
 * <ol>
 *   <li>Resolución del contrato en {@link ContractRegistry} — 403 si no existe.</li>
 *   <li>Validación del payload contra el contrato — 400 con violations si falla.</li>
 *   <li>Registro de {@code hub.audit.product} en el request para
 *       {@link bo.com.sintesis.mdqr.base.hub.HubAuditInterceptor}.</li>
 *   <li>Delegación al {@link ForwardingGateway}.</li>
 *   <li>Construcción del {@link ApiResponse} y propagación de {@code X-Correlation-ID}.</li>
 * </ol>
 */
@Slf4j
@RestController
@RequestMapping("/api/inbound")
@RequiredArgsConstructor
public class DispatcherController {

    /** Atributo de request que informa el producto al interceptor de auditoría. */
    static final String ATTR_AUDIT_PRODUCT = "hub.audit.product";

    /** Sufijo de convención para contratos de edición. */
    private static final String EDITAR_SUFFIX = "_EDITAR";

    private final ContractRegistry contractRegistry;
    private final ContractValidator contractValidator;
    private final ForwardingGateway forwardingGateway;

    // ─── POST: crear ──────────────────────────────────────────────────────────

    @PostMapping("/{product}/{version}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> post(
            @PathVariable String product,
            @PathVariable String version,
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "X-Partner-Id",      required = false) String partnerId,
            @RequestHeader(value = "X-Correlation-ID",  required = false) String correlationId,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        String cid = resolverCorrelationId(correlationId);
        log.info("Inbound POST: product={}/{} partner={} correlationId={}", product, version, partnerId, cid);

        var contractOpt = contractRegistry.lookup(product, version);
        if (contractOpt.isEmpty()) {
            log.warn("Producto no registrado: {}/{} — partner={}", product, version, partnerId);
            return respuestaProductoNoAutorizado(product, version, cid, httpResponse);
        }

        httpRequest.setAttribute(ATTR_AUDIT_PRODUCT, product);
        return procesarDispatch(contractOpt.get(), payload, partnerId, cid, httpResponse);
    }

    // ─── PATCH: editar ────────────────────────────────────────────────────────

    /**
     * Editar recurso inbound.
     *
     * <p>Convención de routing: {@code PATCH /{product}/{version}/{id}} →
     * contrato {@code {product}_EDITAR/{version}}. El {@code {id}} del path se inyecta
     * en el payload bajo {@link ContractDefinition#resourceIdField()} como {@link Long}.
     * El partner no debe incluir ese campo en el body; si lo incluye, el valor del path
     * prevalece.
     */
    @PatchMapping("/{product}/{version}/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> patch(
            @PathVariable String product,
            @PathVariable String version,
            @PathVariable String id,
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "X-Partner-Id",      required = false) String partnerId,
            @RequestHeader(value = "X-Correlation-ID",  required = false) String correlationId,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        String cid = resolverCorrelationId(correlationId);
        String editProduct = product + EDITAR_SUFFIX;
        log.info("Inbound PATCH: product={}/{} id={} partner={} correlationId={}", product, version, id, partnerId, cid);

        // ── Lookup del contrato de edición ───────────────────────────────────
        var contractOpt = contractRegistry.lookup(editProduct, version);
        if (contractOpt.isEmpty()) {
            log.warn("Contrato de edición no registrado: {}/{} — partner={}", editProduct, version, partnerId);
            return respuestaProductoNoAutorizado(editProduct, version, cid, httpResponse);
        }

        // ── Inyección del ID del path en el payload ──────────────────────────
        ContractDefinition contract = contractOpt.get();
        Map<String, Object> efectivePayload = inyectarResourceId(contract, id, payload);
        if (efectivePayload == null) {
            log.warn("Path id no parseable como entero: id={} product={} partner={}", id, editProduct, partnerId);
            httpResponse.setHeader("X-Correlation-ID", cid);
            ApiError apiError = new ApiError(
                    "INVALID_RESOURCE_ID",
                    "El identificador de recurso en el path debe ser un número entero: " + id,
                    List.of()
            );
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(400, "Identificador inválido", apiError, cid));
        }

        httpRequest.setAttribute(ATTR_AUDIT_PRODUCT, editProduct);
        return procesarDispatch(contract, efectivePayload, partnerId, cid, httpResponse);
    }

    // ─── lógica compartida ────────────────────────────────────────────────────

    private ResponseEntity<ApiResponse<Map<String, Object>>> procesarDispatch(
            ContractDefinition contract,
            Map<String, Object> payload,
            String partnerId,
            String correlationId,
            HttpServletResponse httpResponse) {

        // ── Validación ───────────────────────────────────────────────────────
        List<ConstraintViolation> violations = contractValidator.validate(payload, contract);
        if (!violations.isEmpty()) {
            log.warn("Payload inválido para {}/{}: {} violaciones — partner={}",
                    contract.product(), contract.version(), violations.size(), partnerId);
            List<ApiViolation> apiViolations = violations.stream()
                    .map(v -> new ApiViolation(v.field(), v.message()))
                    .toList();
            ApiError apiError = new ApiError(
                    "VALIDATION_ERROR",
                    "El payload no cumple el contrato del producto " + contract.product() + "/" + contract.version(),
                    apiViolations
            );
            httpResponse.setHeader("X-Correlation-ID", correlationId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(400, "Error de validación", apiError, correlationId));
        }

        // ── Delegación al ForwardingGateway ──────────────────────────────────
        ForwardResult result = forwardingGateway.forward(
                contract.product(), contract.version(), payload, correlationId);

        httpResponse.setHeader("X-Correlation-ID", correlationId);

        if (result.ok()) {
            return ResponseEntity.status(result.httpStatus())
                    .body(ApiResponse.ok(result.httpStatus(), result.message(),
                            result.data(), correlationId));
        } else {
            ApiError apiError = new ApiError("FORWARD_ERROR", result.message(), List.of());
            return ResponseEntity.status(result.httpStatus())
                    .body(ApiResponse.error(result.httpStatus(), result.message(),
                            apiError, correlationId));
        }
    }

    /**
     * Inyecta el {@code id} del path en el payload bajo {@link ContractDefinition#resourceIdField()}.
     *
     * @return nuevo mapa con el ID inyectado, o {@code null} si {@code id} no es un Long válido
     */
    private static Map<String, Object> inyectarResourceId(ContractDefinition contract,
                                                           String id,
                                                           Map<String, Object> payload) {
        if (contract.resourceIdField() == null) {
            return payload;
        }
        try {
            long resourceId = Long.parseLong(id);
            Map<String, Object> mutable = new HashMap<>(payload);
            mutable.put(contract.resourceIdField(), resourceId);
            return mutable;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> respuestaProductoNoAutorizado(
            String product, String version, String correlationId, HttpServletResponse httpResponse) {
        ApiError apiError = new ApiError(
                "PRODUCT_NOT_AUTHORIZED",
                "El producto solicitado no está autorizado o no existe: " + product + "/" + version,
                List.of()
        );
        httpResponse.setHeader("X-Correlation-ID", correlationId);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(403, "Producto no autorizado", apiError, correlationId));
    }

    private static String resolverCorrelationId(String raw) {
        return (raw != null && !raw.isBlank()) ? raw : UUID.randomUUID().toString();
    }
}
