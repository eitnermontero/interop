package bo.com.sintesis.mdqr.base.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.Instant;

/**
 * DTO para filtros de consulta de logs de auditoría.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogFilter {

    /**
     * Filtrar por Client ID de Keycloak.
     */
    private String keycloakClientId;

    /**
     * Filtrar por código de certificado utilizado.
     */
    private String certificateCode;

    /**
     * Filtrar por ID de entidad.
     */
    private String entityId;

    /**
     * Filtrar por estado de la desencriptación.
     * Valores: SUCCESS, ERROR
     */
    private String status;

    /**
     * Fecha desde (inclusive).
     */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private Instant fromDate;

    /**
     * Fecha hasta (inclusive).
     */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private Instant toDate;

    /**
     * Número de página (0-indexed).
     */
    @Builder.Default
    private Integer page = 0;

    /**
     * Tamaño de página.
     */
    @Builder.Default
    private Integer size = 20;

    /**
     * Campo para ordenamiento.
     * Default: "createdDate"
     */
    @Builder.Default
    private String sort = "createdDate";

    /**
     * Orden: "asc" o "desc".
     * Default: "desc"
     */
    @Builder.Default
    private String order = "desc";
}
