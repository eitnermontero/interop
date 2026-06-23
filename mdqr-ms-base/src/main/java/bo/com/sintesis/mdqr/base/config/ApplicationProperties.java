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

}
