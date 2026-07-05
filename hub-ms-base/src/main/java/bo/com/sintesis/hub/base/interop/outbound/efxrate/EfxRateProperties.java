package bo.com.sintesis.hub.base.interop.outbound.efxrate;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propiedades del adaptador outbound de EfxRate.
 * Prefijo separado ("hub") para no contaminar el prefijo legacy "application".
 */
@Data
@ConfigurationProperties(prefix = "hub.outbound.efxrate")
public class EfxRateProperties {

    /**
     * URL base del proveedor EfxRate.
     * En tests locales apunta al WireMock.
     */
    private String url = "https://api.efxrate.example.com";

    /**
     * Timeout de conexión y lectura en milisegundos.
     */
    private int timeoutMs = 5000;

    /**
     * Path en Vault KV donde reside el API key del proveedor.
     * La clave dentro del secreto se asume "api-key".
     */
    private String vaultPath = "hub-base/data/efxrate/api-key";

    /**
     * TTL en minutos del caché Redis para respuestas de tipo de cambio.
     */
    private int cacheTtlMinutes = 60;

    /**
     * Parámetros del pipeline de resiliencia.
     */
    private Resilience4j resilience4j = new Resilience4j();

    @Data
    public static class Resilience4j {

        /** Máximo de intentos del mecanismo de retry (incluye el intento inicial). */
        private int retryMaxAttempts = 3;

        /** Tiempo de espera entre reintentos en milisegundos. */
        private long retryWaitMs = 500;

        /**
         * Umbral de tasa de fallos en porcentaje que abre el circuit breaker.
         * Con valor 50, el CB se abre si el 50% o más de las llamadas fallan.
         */
        private float cbFailureRateThreshold = 50f;

        /** Tiempo en milisegundos que el circuit breaker permanece abierto antes de pasar a HALF_OPEN. */
        private long cbWaitDurationMs = 30_000L;

        /**
         * Número mínimo de llamadas registradas antes de que el CB calcule la tasa de fallos.
         * Resilience4j 2.x usa default=100; lo bajamos a 10 para entornos de baja carga.
         */
        private int cbMinimumNumberOfCalls = 10;

        /** Número máximo de llamadas concurrentes permitidas por el bulkhead. */
        private int bulkheadMaxConcurrent = 10;
    }
}
