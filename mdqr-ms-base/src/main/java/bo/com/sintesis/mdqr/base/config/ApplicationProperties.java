package bo.com.sintesis.mdqr.base.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Propiedades de configuración de la aplicación.
 * <p>
 * Lee las propiedades desde application.yml con el prefijo "application".
 * </p>
 */
@Component
@ConfigurationProperties(prefix = "application")
@Data
public class ApplicationProperties {

    private Tuxedo tuxedo = new Tuxedo();
    private Certificate certificate = new Certificate();
    private Qr qr = new Qr();

    /**
     * Configuración de la API de Tuxedo (Go API).
     */
    @Data
    public static class Tuxedo {
        /**
         * URL base de la API de Tuxedo.
         * Ejemplo: http://localhost:5050
         */
        private String apiUrl = "http://localhost:5050";

        /**
         * API Key para autenticación con Tuxedo API.
         * Se obtiene desde Vault.
         */
        private String apiKey;

        /**
         * Timeout en milisegundos para las llamadas a Tuxedo API.
         * Default: 30000 (30 segundos)
         */
        private int timeoutMs = 30000;
    }

    /**
     * Configuración de gestión de certificados.
     */
    @Data
    public static class Certificate {
        private Sync sync = new Sync();

        /**
         * Configuración de sincronización/detección de cambios en JKS.
         */
        @Data
        public static class Sync {
            /**
             * Habilitar detección automática de cambios en JKS.
             * Default: true
             */
            private boolean enabled = true;

            /**
             * Expresión cron para la detección de cambios.
             * Default: "0 0 * * * *" (cada hora)
             */
            private String cron = "0 0 * * * *";

            /**
             * Ejecutar sincronización al iniciar la aplicación.
             * Default: false
             */
            private boolean onStartup = false;
        }
    }

    /**
     * Configuración de desencriptación de QR.
     */
    @Data
    public static class Qr {
        private Decryption decryption = new Decryption();

        /**
         * Configuración de desencriptación.
         */
        @Data
        public static class Decryption {
            /**
             * Habilitar caché de certificados en Redis.
             * Default: true
             */
            private boolean cacheEnabled = true;

            /**
             * TTL del caché en minutos.
             * Default: 1440 (24 horas)
             */
            private int cacheTtlMinutes = 1440;

            /**
             * Habilitar auditoría de desencriptaciones.
             * Default: true
             */
            private boolean auditEnabled = true;
        }
    }
}
