package bo.com.sintesis.hub.base.config;

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

    /**
     * Token de desarrollo local usado como fallback por adaptadores outbound
     * cuando Vault no está disponible (vault.enabled=false en perfil local).
     * En producción Vault siempre provee las credenciales; este bloque solo
     * aplica al perfil local.
     */
    private LocalDevToken localDevToken = new LocalDevToken();

    /**
     * Credencial de desarrollo local (fallback sin Vault).
     */
    @Data
    public static class LocalDevToken {
        /**
         * API Key de fallback para entornos locales sin Vault.
         * En producción este valor se ignora; el adaptador lee desde Vault.
         */
        private String apiKey;
    }

}
