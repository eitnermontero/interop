package bo.com.sintesis.hub.base.hub.inbound.config;

import bo.com.sintesis.hub.base.hub.inbound.contract.FieldType;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plano de control declarativo del hub (ADR-0007): conectores (destinos HTTP)
 * y APIs (contratos inbound + ruteo) definidos por configuración YAML.
 *
 * <p>Agregar una API nueva = declarar un bloque en {@code hub.apis} — sin
 * controllers, DTOs ni cambios de código. Las claves de los mapas son nombres
 * lógicos (permiten merge entre perfiles); la identidad real de la API es
 * {@code (product, version)}.
 *
 * <pre>
 * hub:
 *   connectors:
 *     backend-penal:
 *       base-url: http://backend-penal:8080
 *       timeout-ms: 5000
 *   apis:
 *     caso-penal-v1:
 *       product: CASO_PENAL
 *       version: v1
 *       method: POST
 *       adapter-bean: stubInboundAdapter   # o connector: backend-penal
 *       fields:
 *         - { name: cud, type: STRING, required: true, max-length: 50 }
 * </pre>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "hub")
public class HubInteropProperties {

    /** Destinos HTTP que el hub invoca, por nombre lógico. */
    private Map<String, ConnectorProps> connectors = new LinkedHashMap<>();

    /** APIs inbound expuestas, por nombre lógico. */
    private Map<String, ApiProps> apis = new LinkedHashMap<>();

    @Getter
    @Setter
    public static class ConnectorProps {
        /** URL base del destino (sin path de operación). */
        private String baseUrl;
        /** Timeout de conexión y lectura, en ms. */
        private int timeoutMs = 5000;
        /** Autenticación hacia el destino. */
        private AuthProps auth = new AuthProps();
    }

    @Getter
    @Setter
    public static class AuthProps {
        /** NONE | API_KEY (API_KEY resuelve el secreto desde Vault, pendiente fase 2). */
        private String type = "NONE";
        /** Referencia al secreto en Vault — nunca el secreto en sí. */
        private String vaultPath;
    }

    @Getter
    @Setter
    public static class ApiProps {
        /** Código canónico del producto (clave del ContractRegistry). */
        private String product;
        /** Versión del contrato. */
        private String version = "v1";
        /**
         * Método HTTP hacia el destino, y también el verbo con el que el partner debe
         * invocar el producto en el dispatcher (POST | PATCH | PUT | GET). {@code GET}
         * identifica catálogos de solo lectura: sin body de entrada, sin
         * {@code X-Idempotency-Key} exigible, y sin invocar {@code ContractValidator}
         * más allá de un contrato sin campos.
         */
        private String method = "POST";
        /** Nombre del conector destino (usa el adaptador HTTP genérico). Excluyente con adapterBean. */
        private String connector;
        /** Nombre de bean InboundPort custom (válvula de escape). Excluyente con connector. */
        private String adapterBean;
        /** Path de la operación en el destino; admite placeholders {campo} resueltos desde el payload. */
        private String targetPath;
        /** Campo del payload donde el dispatcher inyecta el {id} del path (contratos de edición). */
        private String resourceIdField;
        /** Scope OAuth2 requerido (documental en v1; lo aplica el gateway). */
        private String requiredScope;
        /** Reglas de forma del payload. */
        private List<FieldProps> fields = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class FieldProps {
        private String name;
        private FieldType type;
        private boolean required;
        private Integer maxLength;
        private String format;
    }
}
