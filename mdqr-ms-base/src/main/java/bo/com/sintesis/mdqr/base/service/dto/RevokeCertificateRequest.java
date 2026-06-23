package bo.com.sintesis.mdqr.base.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request para revocar un certificado.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RevokeCertificateRequest {

    /**
     * Razón de la revocación.
     * Ej: "Certificado comprometido", "Solicitud del banco", "Error en emisión"
     */
    @NotBlank(message = "La razón de revocación no puede estar vacía")
    @Size(max = 500, message = "La razón no puede exceder 500 caracteres")
    private String reason;
}
