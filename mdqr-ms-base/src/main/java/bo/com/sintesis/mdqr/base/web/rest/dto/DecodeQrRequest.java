package bo.com.sintesis.mdqr.base.web.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Request para decodificar y desencriptar un código QR.
 * <p>
 * Soporta dos tipos de entrada:
 * - DECODED_DATA: El contenido del QR ya decodificado (texto plano)
 * - BASE64_IMAGE: Imagen del QR en formato Base64
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DecodeQrRequest {

    /**
     * Tipo de entrada del QR.
     * Valores permitidos: DECODED_DATA, BASE64_IMAGE
     */
    @NotNull(message = "El tipo de entrada es obligatorio")
    private InputType inputType;

    /**
     * Contenido del QR según el tipo:
     * - Si inputType = DECODED_DATA: contenido del QR ya leído (formato: ENCRYPTED_DATA|CERT_CODE)
     * - Si inputType = BASE64_IMAGE: imagen del QR en Base64
     */
    @NotBlank(message = "El contenido es obligatorio")
    private String content;

    /**
     * ID de la entidad que solicita la desencriptación (opcional).
     * Se usa para validación y auditoría.
     */
    private String entityIdRequest;

    /**
     * Referencia externa para trazabilidad (opcional).
     */
    private String externalReference;

    /**
     * Metadatos adicionales (opcional).
     */
    private Map<String, String> metadata;

    /**
     * Enum para tipos de entrada soportados.
     */
    public enum InputType {
        /**
         * El contenido del QR ya fue decodificado (texto plano).
         */
        DECODED_DATA,

        /**
         * Imagen del QR en formato Base64 (requiere decodificación con ZXing).
         */
        BASE64_IMAGE
    }
}
