package bo.com.sintesis.mdqr.base.interop.outbound.efxrate;

import bo.com.sintesis.mdqr.base.interop.outbound.efxrate.dto.EfxRateProviderRequest;
import bo.com.sintesis.mdqr.base.interop.outbound.efxrate.dto.EfxRateProviderResponse;
import bo.com.sintesis.mdqr.base.interop.outbound.efxrate.exception.ExchangeRateNotFoundException;
import bo.com.sintesis.mdqr.base.interop.outbound.efxrate.exception.ExchangeRateProviderException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.decorators.Decorators;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.lang.Nullable;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * Cliente HTTP outbound para el proveedor EfxRate.
 *
 * Pipeline de resiliencia programático (sin anotaciones AOP) en el orden:
 * Bulkhead → CircuitBreaker → Retry → llamada HTTP.
 *
 * El timeout de conexión y lectura está configurado en SimpleClientHttpRequestFactory,
 * lo que garantiza que cada llamada HTTP individual se corta al superar timeoutMs.
 * No se usa TimeLimiter separado de Resilience4j para evitar la complejidad de
 * ScheduledExecutorService en un contexto MVC sincrónico — el timeout del factory
 * cubre el mismo requisito.
 *
 * La lectura del API key desde Vault ocurre en cada invocación para soportar
 * rotación de credenciales sin reinicio de la aplicación.
 *
 * TODO(hub-poc): implementar caché local de 5min para el API key de Vault
 * cuando el volumen de llamadas haga que la latencia de Vault sea un cuello de botella.
 */
@Slf4j
public class EfxRateClient {

    private static final String NOMBRE_CB       = "efxrate";
    private static final String NOMBRE_RETRY    = "efxrate";
    private static final String NOMBRE_BULKHEAD = "efxrate";

    /**
     * Nombre del campo dentro del secreto Vault KV que contiene el API key.
     */
    private static final String VAULT_SECRET_FIELD = "api-key";

    private final EfxRateProperties properties;
    private final RestClient restClient;
    private final String localFallbackApiKey;

    @Nullable
    private final VaultTemplate vaultTemplate;

    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final Bulkhead bulkhead;

    /**
     * @param properties          configuración del proveedor
     * @param vaultTemplate       cliente de Vault; {@code null} en perfil local
     *                            (vault.enabled=false)
     * @param localFallbackApiKey API key literal de fallback para perfil local.
     *                            TODO(hub-poc): en producción Vault siempre debe estar disponible;
     *                            este fallback es exclusivo del perfil local.
     */
    public EfxRateClient(EfxRateProperties properties,
                         @Nullable VaultTemplate vaultTemplate,
                         String localFallbackApiKey) {
        this.properties = properties;
        this.vaultTemplate = vaultTemplate;
        this.localFallbackApiKey = localFallbackApiKey;

        // SimpleClientHttpRequestFactory — mismo mecanismo que TuxedoApiClient
        // (compatible con Spring Boot 4 / Spring 6.x sin dependencia de Apache HttpClient)
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getTimeoutMs());
        factory.setReadTimeout(properties.getTimeoutMs());

        this.restClient = RestClient.builder()
                .baseUrl(properties.getUrl())
                .requestFactory(factory)
                .build();

        EfxRateProperties.Resilience4j r4j = properties.getResilience4j();

        // Circuit Breaker — solo ExchangeRateProviderException abre el CB;
        // NotFoundException es un resultado de negocio que no indica fallo de infraestructura
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(r4j.getCbFailureRateThreshold())
                .waitDurationInOpenState(Duration.ofMillis(r4j.getCbWaitDurationMs()))
                .minimumNumberOfCalls(r4j.getCbMinimumNumberOfCalls())
                .recordException(t -> t instanceof ExchangeRateProviderException)
                .build();
        this.circuitBreaker = CircuitBreakerRegistry.of(cbConfig)
                .circuitBreaker(NOMBRE_CB);

        // Retry — solo errores transitorios; NotFoundException no es retriable
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(r4j.getRetryMaxAttempts())
                .waitDuration(Duration.ofMillis(r4j.getRetryWaitMs()))
                .retryExceptions(ExchangeRateProviderException.class)
                .ignoreExceptions(ExchangeRateNotFoundException.class)
                .build();
        this.retry = RetryRegistry.of(retryConfig).retry(NOMBRE_RETRY);

        // Bulkhead — limita llamadas concurrentes al proveedor
        BulkheadConfig bulkheadConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(r4j.getBulkheadMaxConcurrent())
                .build();
        this.bulkhead = BulkheadRegistry.of(bulkheadConfig)
                .bulkhead(NOMBRE_BULKHEAD);

        log.info("EfxRateClient inicializado. URL: {}, timeout: {}ms, vault: {}",
                properties.getUrl(),
                properties.getTimeoutMs(),
                vaultTemplate != null ? "activo" : "deshabilitado (perfil local)");
    }

    /**
     * Consulta el tipo de cambio en el proveedor externo con el pipeline
     * de resiliencia: Bulkhead → CircuitBreaker → Retry.
     *
     * @param request parámetros ya traducidos al formato del proveedor
     * @return respuesta del proveedor deserializada
     * @throws ExchangeRateNotFoundException           si el proveedor responde 4xx
     * @throws ExchangeRateProviderException           si el proveedor responde 5xx o hay timeout/red
     * @throws io.github.resilience4j.bulkhead.BulkheadFullException       si el bulkhead está saturado
     * @throws io.github.resilience4j.circuitbreaker.CallNotPermittedException si el CB está abierto
     */
    public EfxRateProviderResponse fetchRate(EfxRateProviderRequest request) {
        String apiKey = resolverApiKey();

        Callable<EfxRateProviderResponse> llamadaHttp =
                () -> ejecutarLlamadaHttp(request, apiKey);

        // Pipeline de resiliencia programático (Bulkhead → CircuitBreaker → Retry)
        // El orden de los decoradores en Decorators.ofCallable() es de afuera hacia adentro:
        // el último en agregarse es el primero en ejecutarse.
        Callable<EfxRateProviderResponse> decorated = Decorators
                .ofCallable(llamadaHttp)
                .withRetry(retry)
                .withCircuitBreaker(circuitBreaker)
                .withBulkhead(bulkhead)
                .decorate();

        try {
            return decorated.call();
        } catch (ExchangeRateNotFoundException | ExchangeRateProviderException e) {
            // Excepciones de dominio del hub — propagar sin envolver
            throw e;
        } catch (io.github.resilience4j.bulkhead.BulkheadFullException |
                 io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
            // Señales de resiliencia — el caller decide cómo manejarlas
            throw e;
        } catch (Exception e) {
            // Cualquier otra excepción inesperada se envuelve para que el CB la registre
            log.warn("Excepción inesperada en pipeline de resiliencia EfxRate: {}",
                    e.getMessage());
            throw new ExchangeRateProviderException(
                    "Error inesperado en el pipeline EfxRate", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Métodos privados
    // ─────────────────────────────────────────────────────────────────────────

    private EfxRateProviderResponse ejecutarLlamadaHttp(EfxRateProviderRequest req,
                                                         String apiKey) {
        try {
            log.debug("Consultando EfxRate: moneda_origen={}, moneda_destino={}, fecha={}",
                    req.monedaOrigen(), req.monedaDestino(), req.fecha());

            return restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1/rate")
                            .queryParam("moneda_origen", req.monedaOrigen())
                            .queryParam("moneda_destino", req.monedaDestino())
                            .queryParam("fecha", req.fecha())
                            .build())
                    .header("X-Api-Key", apiKey)
                    .retrieve()
                    .body(EfxRateProviderResponse.class);

        } catch (HttpClientErrorException e) {
            // 4xx → el proveedor respondió de forma definitiva; NO es retriable
            log.warn("EfxRate respondió con error del cliente: status={}, body={}",
                    e.getStatusCode().value(), e.getResponseBodyAsString());
            throw new ExchangeRateNotFoundException(
                    "Tipo de cambio no disponible en EfxRate: " + e.getStatusCode().value());

        } catch (HttpServerErrorException e) {
            // 5xx → error de infraestructura del proveedor; retriable
            log.warn("EfxRate respondió con error del servidor: status={}",
                    e.getStatusCode().value());
            throw new ExchangeRateProviderException(
                    "Error del servidor EfxRate: " + e.getStatusCode().value(), e);

        } catch (ResourceAccessException e) {
            // Timeout o red caída → retriable
            log.warn("Error de red o timeout al consultar EfxRate: {}", e.getMessage());
            throw new ExchangeRateProviderException(
                    "Error de red al consultar EfxRate", e);

        } catch (Exception e) {
            log.error("Error inesperado al llamar EfxRate: {}", e.getMessage(), e);
            throw new ExchangeRateProviderException(
                    "Error inesperado al consultar EfxRate", e);
        }
    }

    @SuppressWarnings("unchecked")
    private String resolverApiKey() {
        if (vaultTemplate == null) {
            log.debug("VaultTemplate no disponible (perfil local); usando API key de fallback");
            return localFallbackApiKey != null ? localFallbackApiKey : "";
        }

        try {
            org.springframework.vault.support.VaultResponse response =
                    vaultTemplate.read(properties.getVaultPath());

            if (response == null || response.getData() == null) {
                throw new IllegalStateException(
                        "Vault no devolvió datos en path: " + properties.getVaultPath());
            }

            Object valor = response.getData().get(VAULT_SECRET_FIELD);
            if (valor == null) {
                throw new IllegalStateException(
                        "Campo '" + VAULT_SECRET_FIELD + "' ausente en Vault path: "
                        + properties.getVaultPath());
            }

            return valor.toString();

        } catch (Exception e) {
            log.error("No se pudo obtener el API key de Vault [path={}]: {}",
                    properties.getVaultPath(), e.getMessage());
            throw new ExchangeRateProviderException(
                    "Error al obtener credenciales del proveedor EfxRate desde Vault", e);
        }
    }
}
