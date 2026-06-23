package bo.com.sintesis.mdqr.base.web.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Request DTO para desencriptar un código QR.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DecryptQrRequest {

    /**
     * String del código QR en formato: {encrypted_base64}|{certificate_code}
     */
    @NotBlank(message = "El QR string no puede estar vacío")
    @Size(min = 10, max = 10000, message = "El QR string debe tener entre 10 y 10000 caracteres")
    private String qrString;

    /**
     * ID de entidad solicitante (opcional).
     * Ej: "BANCOSOL", "BCP", "UNILINK"
     */
    @Size(max = 50, message = "El entity ID no puede exceder 50 caracteres")
    private String entityIdRequest;

    /**
     * Referencia externa del sistema que solicita la desencriptación (opcional).
     * Útil para correlación y debugging.
     */
    @Size(max = 100, message = "La referencia externa no puede exceder 100 caracteres")
    private String externalReference;

    /**
     * Metadata adicional del request (opcional).
     * Puede incluir información contextual como origen, versión, etc.
     */
    private Map<String, String> metadata;
}
