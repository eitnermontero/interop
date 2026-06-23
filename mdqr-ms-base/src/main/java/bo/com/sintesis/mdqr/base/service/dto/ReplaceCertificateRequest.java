package bo.com.sintesis.mdqr.base.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request para reemplazar un certificado con una nueva versión.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReplaceCertificateRequest {

    /**
     * Contenido PEM del nuevo certificado.
     */
    @NotBlank(message = "El contenido PEM no puede estar vacío")
    @Size(min = 100, max = 10000, message = "El contenido PEM debe tener entre 100 y 10000 caracteres")
    private String newPemContent;

    /**
     * Razón del reemplazo.
     * Ej: "Renovación programada", "Certificado comprometido", "Actualización de seguridad"
     */
    @NotBlank(message = "La razón del cambio no puede estar vacía")
    @Size(max = 500, message = "La razón no puede exceder 500 caracteres")
    private String changeReason;
}
