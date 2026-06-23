package bo.com.sintesis.mdqr.base.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO para importar un nuevo certificado.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportCertificateRequest {

    /**
     * Alias del certificado en el JKS.
     * Debe ser único.
     */
    @NotBlank(message = "El alias no puede estar vacío")
    @Size(min = 3, max = 100, message = "El alias debe tener entre 3 y 100 caracteres")
    private String alias;

    /**
     * Contenido del certificado en formato PEM.
     * Debe comenzar con "-----BEGIN CERTIFICATE-----"
     */
    @NotBlank(message = "El contenido PEM no puede estar vacío")
    @Size(min = 100, max = 10000, message = "El contenido PEM debe tener entre 100 y 10000 caracteres")
    private String pemContent;

    /**
     * ID de la entidad asociada al certificado.
     * Ej: "BANCOSOL", "BCP", "UNILINK"
     */
    @NotBlank(message = "El entity ID no puede estar vacío")
    @Size(min = 2, max = 50, message = "El entity ID debe tener entre 2 y 50 caracteres")
    private String entityId;

    /**
     * Descripción o notas adicionales sobre el certificado (opcional).
     */
    @Size(max = 500, message = "La descripción no puede exceder 500 caracteres")
    private String description;
}
