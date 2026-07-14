package bo.com.sintesis.hub.base.hub.inbound;

import bo.com.sintesis.hub.base.hub.inbound.contract.ConstraintViolation;
import bo.com.sintesis.hub.base.hub.inbound.contract.ContractDefinition;
import bo.com.sintesis.hub.base.hub.inbound.contract.ContractRegistry;
import bo.com.sintesis.hub.base.hub.inbound.contract.ContractValidator;
import bo.com.sintesis.hub.base.hub.inbound.port.ForwardResult;
import bo.com.sintesis.hub.base.web.rest.ApiError;
import bo.com.sintesis.hub.base.web.rest.ApiResponse;
import bo.com.sintesis.hub.base.web.rest.ApiViolation;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Controlador genérico del motor inbound del hub de interoperabilidad.
 *
 * <p>Expone tres endpoints:
 * <ul>
 *   <li>{@code POST /api/inbound/{product}/{version}} — crear recurso.</li>
 *   <li>{@code PATCH /api/inbound/{product}/{version}/{id}} — editar recurso.
 *       Resuelve el contrato {@code {product}_EDITAR/{version}} e inyecta el
 *       {@code {id}} del path bajo el campo {@link ContractDefinition#resourceIdField()}.</li>
 *   <li>{@code GET /api/inbound/{product}/{version}} — consultar recurso de solo
 *       lectura. Sin body de entrada; los query params se validan y propagan al
 *       destino (como placeholder de {@code target-path} o como query string —
 *       ver {@link #get}). Solo atiende contratos declarados con {@code method: GET}
 *       ({@link ContractDefinition#isReadOnly()}). No exige
 *       {@code X-Idempotency-Key} (ver {@link bo.com.sintesis.hub.base.hub.HubAuditInterceptor}).</li>
 * </ul>
 *
 * <p>Flujo común (POST, PATCH y GET):
 * <ol>
 *   <li>Resolución del contrato en {@link ContractRegistry} — 403 si no existe.</li>
 *   <li>Validación del payload contra el contrato — 400 con violations si falla.</li>
 *   <li>Registro de {@code hub.audit.product} en el request para
 *       {@link bo.com.sintesis.hub.base.hub.HubAuditInterceptor}.</li>
 *   <li>Delegación al {@link ForwardingGateway}.</li>
 *   <li>Construcción del {@link ApiResponse} y propagación de {@code X-Correlation-ID}.</li>
 * </ol>
 *
 * <p><b>Swagger:</b> este controller está marcado con {@link Hidden} porque los paths
 * genéricos ({@code /{product}/{version}}) serían ambiguos en el spec. Los paths
 * concretos por producto los genera {@link bo.com.sintesis.hub.base.hub.inbound.config.InboundContractOpenApiCustomizer}
 * dinámicamente desde el {@link ContractRegistry}.
 */
@Hidden
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

    /**
     * Crear recurso inbound.
     *
     * <p>Solo atiende contratos que <b>no</b> sean de solo lectura
     * ({@link ContractDefinition#isReadOnly()}). Un producto declarado
     * {@code method: GET} (catálogo) no debe poder invocarse por POST — de lo
     * contrario, al no tener {@code fields} que validar, el body (con o sin
     * contenido) pasaría la validación trivialmente y honraría
     * {@code X-Idempotency-Key} para una operación que en realidad es una
     * lectura pura, contradiciendo el resto del diseño (ver también
     * {@link #get}, que aplica la verificación simétrica).
     */
    @PostMapping("/{product}/{version}")
    public ResponseEntity<ApiResponse<Object>> post(
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
        if (contractOpt.isEmpty() || contractOpt.get().isReadOnly()) {
            log.warn("Producto no registrado como POST: {}/{} — partner={}", product, version, partnerId);
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
    public ResponseEntity<ApiResponse<Object>> patch(
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

    // ─── GET: consultar catálogo (solo lectura) ──────────────────────────────

    /**
     * Consulta de catálogo de solo lectura.
     *
     * <p>Sin cuerpo de petición: el contrato resuelto se reenvía al backend configurado
     * en {@code hub.connectors}. Los query params de la petición se validan contra
     * {@code fields} del contrato (igual que el body en POST/PATCH) y se propagan al
     * destino — como placeholder de {@code target-path} (ej. {@code /operativos/{cud}},
     * consulta de detalle) o, si no calzan con ningún placeholder, como query string
     * (ej. {@code pagina}/{@code limite} de un listado) — ver
     * {@link HttpForwardingAdapter#forward}. No exige {@code X-Idempotency-Key}
     * porque GET es idempotente por naturaleza; el header ni siquiera se declara
     * en este método, y {@link bo.com.sintesis.hub.base.hub.HubAuditInterceptor}
     * ignora cualquier clave que igualmente llegue en la petición.
     *
     * <p>Solo atiende contratos declarados con {@code method: GET} en {@code hub.apis}
     * ({@link ContractDefinition#isReadOnly()}). Si el {@code product}/{@code version}
     * no está registrado, o está registrado con otro verbo (POST/PATCH), se responde
     * 403 igual que un producto no autorizado — evita que un GET dispare
     * accidentalmente un contrato de escritura con un payload vacío sin validar.
     */
    @GetMapping("/{product}/{version}")
    public ResponseEntity<ApiResponse<Object>> get(
            @PathVariable String product,
            @PathVariable String version,
            @RequestParam Map<String, String> queryParams,
            @RequestHeader(value = "X-Partner-Id",     required = false) String partnerId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        String cid = resolverCorrelationId(correlationId);
        log.info("Inbound GET: product={}/{} partner={} correlationId={}", product, version, partnerId, cid);

        var contractOpt = contractRegistry.lookup(product, version);
        if (contractOpt.isEmpty() || !contractOpt.get().isReadOnly()) {
            log.warn("Producto no registrado como catálogo GET: {}/{} — partner={}", product, version, partnerId);
            return respuestaProductoNoAutorizado(product, version, cid, httpResponse);
        }

        httpRequest.setAttribute(ATTR_AUDIT_PRODUCT, product);
        return procesarDispatch(contractOpt.get(), new HashMap<>(queryParams), partnerId, cid, httpResponse);
    }

    // ─── lógica compartida ────────────────────────────────────────────────────

    private ResponseEntity<ApiResponse<Object>> procesarDispatch(
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

    private ResponseEntity<ApiResponse<Object>> respuestaProductoNoAutorizado(
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
