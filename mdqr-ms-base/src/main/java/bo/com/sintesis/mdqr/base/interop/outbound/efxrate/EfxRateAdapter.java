package bo.com.sintesis.mdqr.base.interop.outbound.efxrate;

import bo.com.sintesis.mdqr.audit.hash.PayloadHasher;
import bo.com.sintesis.mdqr.audit.hub.HubAuditCommand;
import bo.com.sintesis.mdqr.audit.hub.HubAuditService;
import bo.com.sintesis.mdqr.base.interop.canonical.ExchangeRateRequest;
import bo.com.sintesis.mdqr.base.interop.canonical.ExchangeRateResponse;
import bo.com.sintesis.mdqr.base.interop.outbound.efxrate.dto.EfxRateProviderRequest;
import bo.com.sintesis.mdqr.base.interop.outbound.efxrate.dto.EfxRateProviderResponse;
import bo.com.sintesis.mdqr.base.interop.outbound.efxrate.exception.ExchangeRateNotFoundException;
import bo.com.sintesis.mdqr.base.interop.outbound.efxrate.mapper.EfxRateMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Adaptador outbound del hub para el proveedor de tipo de cambio EfxRate.
 * Implementa el patrón Anti-Corruption Layer (ACL) / Facade del hub.
 *
 * Flujo completo por llamada:
 * 1. Traducir contrato canónico → DTO del proveedor.
 * 2. Verificar caché Redis (lectura no bloqueante; si falla, continuar sin caché).
 * 3. Llamar al proveedor vía EfxRateClient (con resiliencia).
 * 4. Hashear request/response reales (los que viajaron por el cable).
 * 5. Registrar auditoría + outbox en la misma transacción (HubAuditService).
 * 6. Guardar en caché Redis el resultado canónico.
 * 7. Traducir respuesta del proveedor → contrato canónico.
 *
 * Si la auditoría falla no se propaga la excepción; el negocio continúa
 * pero se registra un WARN visible para alertas operativas.
 */
@Slf4j
public class EfxRateAdapter {

    private static final String PRODUCT = "EXCHANGE_RATE";
    private static final String ENDPOINT = "/v1/rate";
    private static final String HTTP_METHOD = "GET";
    private static final String PARTNER_ID = "efxrate";
    private static final String AGGREGATE_TYPE = "HUB_TRANSACTION";
    private static final String CACHE_KEY_PREFIX = "hub:efxrate:rate:";

    private final EfxRateClient client;
    private final EfxRateMapper mapper;
    private final HubAuditService hubAuditService;
    private final PayloadHasher payloadHasher;
    private final EfxRateProperties properties;
    @Nullable
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public EfxRateAdapter(EfxRateClient client,
                          EfxRateMapper mapper,
                          HubAuditService hubAuditService,
                          PayloadHasher payloadHasher,
                          EfxRateProperties properties,
                          @Nullable RedisTemplate<String, String> redisTemplate) {
        this.client = client;
        this.mapper = mapper;
        this.hubAuditService = hubAuditService;
        this.payloadHasher = payloadHasher;
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        // ObjectMapper propio para serialización interna (hasheo + caché)
        // JavaTimeModule para serializar LocalDate/Instant correctamente
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /**
     * Consulta el tipo de cambio aplicando el flujo completo del hub:
     * caché → proveedor → auditoría → retorno canónico.
     *
     * @param request petición canónica de la aplicación interna
     * @return respuesta canónica con el tipo de cambio
     * @throws ExchangeRateNotFoundException si el proveedor no tiene datos para esa fecha
     * @throws bo.com.sintesis.mdqr.base.interop.outbound.efxrate.exception.ExchangeRateProviderException
     *         si hay error de infraestructura del proveedor (propagado por EfxRateClient)
     */
    public ExchangeRateResponse getExchangeRate(ExchangeRateRequest request) {
        String cacheKey = construirCacheKey(request);

        // Lectura de caché — fallo no bloquea el flujo principal
        ExchangeRateResponse cached = leerDeCache(cacheKey);
        if (cached != null) {
            log.debug("Cache hit para tipo de cambio: key={}", cacheKey);
            return cached;
        }

        // Traducción canónico → proveedor
        EfxRateProviderRequest providerRequest = mapper.toProviderRequest(request);

        // Serialización del request para hasheo (lo que viajó al proveedor)
        String requestJson = serializarParaHash(providerRequest,
                "request EfxRate [" + request.baseCurrency() + "/" + request.targetCurrency() + "]");

        Instant inicio = Instant.now();
        EfxRateProviderResponse providerResponse = client.fetchRate(providerRequest);
        int latenciaMs = (int) Duration.between(inicio, Instant.now()).toMillis();

        // Serialización de la respuesta para hasheo (lo que llegó del proveedor)
        String responseJson = serializarParaHash(providerResponse,
                "response EfxRate [" + request.baseCurrency() + "/" + request.targetCurrency() + "]");

        // Hasheo de lo que viajó por el cable (RFC 8785 canonicalizado internamente por PayloadHasher)
        String requestHash  = payloadHasher.hash(requestJson);
        String responseHash = payloadHasher.hash(responseJson);

        // Registro de auditoría + outbox (tolerante a fallos: WARN, no excepción)
        registrarAuditoria(request, requestHash, responseHash, latenciaMs, 200);

        // Traducción proveedor → canónico
        ExchangeRateResponse respuestaCanonica = mapper.toCanonical(providerResponse);

        // Escritura en caché — fallo no bloquea el retorno al caller
        // TODO(hub-poc): no cachear si el dato es sensible (PII). UFV/BOB no lo es.
        escribirEnCache(cacheKey, respuestaCanonica);

        return respuestaCanonica;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Métodos privados
    // ─────────────────────────────────────────────────────────────────────────

    private String construirCacheKey(ExchangeRateRequest req) {
        return CACHE_KEY_PREFIX
                + req.baseCurrency() + ":"
                + req.targetCurrency() + ":"
                + req.date().toString(); // formato ISO-8601 (2026-06-22)
    }

    private ExchangeRateResponse leerDeCache(String key) {
        if (redisTemplate == null) {
            return null;
        }
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                return null;
            }
            return objectMapper.readValue(json, ExchangeRateResponse.class);
        } catch (Exception e) {
            log.warn("Error al leer tipo de cambio desde caché Redis [key={}]: {}",
                    key, e.getMessage());
            return null;
        }
    }

    private void escribirEnCache(String key, ExchangeRateResponse response) {
        if (redisTemplate == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(key, json,
                    Duration.ofMinutes(properties.getCacheTtlMinutes()));
        } catch (Exception e) {
            log.warn("Error al escribir tipo de cambio en caché Redis [key={}]: {}",
                    key, e.getMessage());
        }
    }

    private String serializarParaHash(Object objeto, String contexto) {
        try {
            return objectMapper.writeValueAsString(objeto);
        } catch (JsonProcessingException e) {
            // No debería ocurrir con DTOs bien formados; usar representación vacía
            // para no romper el flujo de negocio — el hash será del string "{}"
            log.warn("No se pudo serializar {} para hasheo: {}", contexto, e.getMessage());
            return "{}";
        }
    }

    private void registrarAuditoria(ExchangeRateRequest request,
                                    String requestHash,
                                    String responseHash,
                                    int latenciaMs,
                                    int httpStatus) {
        try {
            UUID auditId = UUID.randomUUID();

            Map<String, Object> outboxPayload = Map.of(
                    "partner_id", PARTNER_ID,
                    "product", PRODUCT,
                    "billable_units", 1,
                    "ts", Instant.now().toString()
            );

            HubAuditCommand cmd = new HubAuditCommand(
                    auditId,
                    "OUT",
                    PARTNER_ID,
                    PRODUCT,
                    ENDPOINT,
                    HTTP_METHOD,
                    httpStatus,
                    requestHash,
                    responseHash,
                    latenciaMs,
                    1, // 1 unidad facturable por consulta de tipo de cambio
                    request.idempotencyKey(),
                    null, // correlationId: no disponible en el scope de este adaptador
                    Instant.now(),
                    AGGREGATE_TYPE,
                    auditId.toString(),
                    outboxPayload
            );

            hubAuditService.record(cmd);
            log.debug("Auditoría registrada para consulta EfxRate: auditId={}", auditId);

        } catch (Exception e) {
            // La auditoría no debe detener el flujo de negocio.
            // Un WARN aquí debe disparar alerta operativa (p.ej. PagerDuty o Grafana).
            log.warn("No se pudo registrar la auditoría de la consulta EfxRate: {}",
                    e.getMessage());
        }
    }
}
