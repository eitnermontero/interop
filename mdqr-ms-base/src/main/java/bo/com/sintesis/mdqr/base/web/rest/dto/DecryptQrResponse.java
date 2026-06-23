package bo.com.sintesis.mdqr.base.web.rest.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Response DTO para la desencriptación de un código QR.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DecryptQrResponse {

    /**
     * ID único del log de auditoría generado para esta desencriptación.
     */
    private String logId;

    /**
     * Datos desencriptados del QR.
     */
    private String decryptedData;

    /**
     * Código del certificado utilizado para desencriptar.
     */
    private String certificateCode;

    /**
     * ID de la entidad asociada al certificado.
     */
    private String entityId;

    /**
     * Tipo de QR detectado (inferido del contenido desencriptado).
     * Ej: "PAYMENT", "INVOICE", "ACCOUNT", "UNKNOWN"
     */
    private String qrType;

    /**
     * Tiempo de procesamiento en milisegundos.
     */
    private Long processingTimeMs;

    /**
     * Timestamp de la desencriptación.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
    private Instant decryptedAt;

    /**
     * Indica si el certificado fue obtenido desde caché.
     */
    private Boolean fromCache;
}
