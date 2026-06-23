package bo.com.sintesis.mdqr.base.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request para subir un nuevo certificado público.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadCertificateRequest {

    /**
     * Contenido del certificado en formato PEM.
     * Debe incluir "-----BEGIN CERTIFICATE-----" y "-----END CERTIFICATE-----"
     */
    @NotBlank(message = "El contenido PEM no puede estar vacío")
    @Size(min = 100, max = 10000, message = "El contenido PEM debe tener entre 100 y 10000 caracteres")
    private String pemContent;

    /**
     * ID de la entidad bancaria.
     * Ej: "1017" (Banco Sol), "1001" (BCP)
     */
    @NotBlank(message = "El entity ID no puede estar vacío")
    @Size(min = 2, max = 50, message = "El entity ID debe tener entre 2 y 50 caracteres")
    private String entityId;

    /**
     * Nombre de la entidad bancaria (opcional).
     */
    @Size(max = 200, message = "El nombre de la entidad no puede exceder 200 caracteres")
    private String entityName;

    /**
     * Descripción o notas sobre el certificado (opcional).
     */
    @Size(max = 1000, message = "La descripción no puede exceder 1000 caracteres")
    private String description;

    /**
     * Tags para categorizar el certificado (opcional).
     */
    private String[] tags;

    /**
     * Emails para notificaciones de expiración (opcional).
     */
    private String[] notificationEmails;
}
