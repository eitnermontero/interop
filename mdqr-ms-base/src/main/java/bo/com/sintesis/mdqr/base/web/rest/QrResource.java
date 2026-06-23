package bo.com.sintesis.mdqr.base.web.rest;

import bo.com.sintesis.mdqr.base.domain.DecryptionLog;
import bo.com.sintesis.mdqr.base.service.AuditService;
import bo.com.sintesis.mdqr.base.service.QrDecryptionService;
import bo.com.sintesis.mdqr.base.service.QrImageDecoderService;
import bo.com.sintesis.mdqr.base.service.dto.AuditLogFilter;
import bo.com.sintesis.mdqr.base.web.rest.dto.DecodeQrRequest;
import bo.com.sintesis.mdqr.base.web.rest.dto.DecryptQrRequest;
import bo.com.sintesis.mdqr.base.web.rest.dto.DecryptQrResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.UUID;

/**
 * REST controller para operaciones de decodificación y desencriptación de códigos QR.
 * <p>
 * Endpoints:
 * - POST /api/qr/decode: Decodificar y desencriptar QR desde JSON (texto o Base64)
 * - POST /api/qr/decode/file: Decodificar y desencriptar QR desde archivo de imagen
 * - GET /api/qr/audits: Consultar auditorías de desencriptaciones
 * </p>
 */
@RestController
@RequestMapping("/api/qr")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "QR Decryption", description = "APIs para decodificación y desencriptación de códigos QR")
@SecurityRequirement(name = "bearer-jwt")
public class QrResource {

    private final QrDecryptionService qrDecryptionService;
    private final QrImageDecoderService qrImageDecoderService;
    private final AuditService auditService;

    /**
     * POST /api/qr/decode : Decodifica y desencripta un código QR.
     * <p>
     * Soporta dos tipos de entrada:
     * 1. DECODED_DATA: Contenido del QR ya leído (formato: ENCRYPTED_DATA|CERT_CODE)
     * 2. BASE64_IMAGE: Imagen del QR en Base64 (se decodifica automáticamente)
     * </p>
     * <p>
     * Requiere rol: API_CLIENT (temporalmente deshabilitado para testing)
     * </p>
     *
     * @param request Request con inputType y content
     * @return Response con los datos desencriptados
     */
    @PostMapping("/decode")
    // TODO: Habilitar para producción
    // @PreAuthorize("hasRole('API_CLIENT')")
    @Operation(
        summary = "Decodificar y desencriptar código QR",
        description = "Decodifica (si es imagen Base64) y desencripta un código QR usando el certificado asociado. " +
            "Soporta dos tipos de entrada: DECODED_DATA (texto del QR) o BASE64_IMAGE (imagen en Base64)."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "QR decodificado y desencriptado exitosamente",
            content = @Content(schema = @Schema(implementation = DecryptQrResponse.class))
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Formato de QR inválido, imagen no válida, o parámetros incorrectos",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))
        ),
        @ApiResponse(
            responseCode = "404",
            description = "Certificado no encontrado",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))
        ),
        @ApiResponse(
            responseCode = "500",
            description = "Error interno al desencriptar",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))
        )
    })
    public ResponseEntity<DecryptQrResponse> decodeQr(
        @Valid @RequestBody DecodeQrRequest request
    ) {
        String requestId = UUID.randomUUID().toString();
        log.info("POST /api/qr/decode - inputType={}, requestId={}", request.getInputType(), requestId);

        try {
            // Paso 1: Obtener el contenido del QR (decodificar si es necesario)
            String qrContent;

            switch (request.getInputType()) {
                case DECODED_DATA:
                    // El contenido ya está decodificado
                    qrContent = request.getContent();
                    log.debug("Usando contenido del QR ya decodificado");
                    break;

                case BASE64_IMAGE:
                    // Decodificar la imagen Base64 usando ZXing
                    log.debug("Decodificando imagen Base64 con ZXing");
                    qrContent = qrImageDecoderService.decodeFromBase64(request.getContent());
                    log.info("QR decodificado desde imagen Base64: longitud={} caracteres", qrContent.length());
                    break;

                default:
                    throw new IllegalArgumentException("Tipo de entrada no soportado: " + request.getInputType());
            }

            // Paso 2: Desencriptar el contenido del QR
            DecryptQrRequest decryptRequest = DecryptQrRequest.builder()
                .qrString(qrContent)
                .entityIdRequest(request.getEntityIdRequest())
                .externalReference(request.getExternalReference())
                .metadata(request.getMetadata())
                .build();

            DecryptQrResponse response = qrDecryptionService.decrypt(decryptRequest);

            log.info("POST /api/qr/decode - Éxito: logId={}, requestId={}", response.getLogId(), requestId);

            // Headers de response
            HttpHeaders headers = new HttpHeaders();
            headers.add("X-Request-Id", requestId);
            headers.add("X-Log-Id", response.getLogId());

            return ResponseEntity
                .ok()
                .headers(headers)
                .body(response);

        } catch (Exception e) {
            log.error("POST /api/qr/decode - Error: requestId={}, error={}", requestId, e.getMessage());
            throw e;
        }
    }

    /**
     * POST /api/qr/decode/file : Decodifica y desencripta un código QR desde un archivo de imagen.
     * <p>
     * Acepta archivos de imagen (JPG, PNG, GIF) que contengan un código QR.
     * El QR se decodifica automáticamente usando ZXing y luego se desencripta.
     * </p>
     * <p>
     * Requiere rol: API_CLIENT (temporalmente deshabilitado para testing)
     * </p>
     *
     * @param file Archivo de imagen que contiene el código QR
     * @param entityIdRequest ID de la entidad (opcional)
     * @param externalReference Referencia externa (opcional)
     * @return Response con los datos desencriptados
     */
    @PostMapping(value = "/decode/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    // TODO: Habilitar para producción
    // @PreAuthorize("hasRole('API_CLIENT')")
    @Operation(
        summary = "Decodificar y desencriptar QR desde archivo de imagen",
        description = "Sube una imagen (JPG, PNG, GIF) que contenga un código QR. " +
            "La imagen se decodifica automáticamente con ZXing y luego se desencripta."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "QR decodificado y desencriptado exitosamente desde el archivo",
            content = @Content(schema = @Schema(implementation = DecryptQrResponse.class))
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Archivo no válido, no es una imagen, o no contiene un QR legible",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))
        ),
        @ApiResponse(
            responseCode = "404",
            description = "Certificado no encontrado",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))
        ),
        @ApiResponse(
            responseCode = "500",
            description = "Error interno al procesar el archivo o desencriptar",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))
        )
    })
    public ResponseEntity<DecryptQrResponse> decodeQrFromFile(
        @Parameter(description = "Archivo de imagen que contiene el código QR", required = true)
        @RequestParam("file") MultipartFile file,

        @Parameter(description = "ID de la entidad que solicita la desencriptación")
        @RequestParam(required = false) String entityIdRequest,

        @Parameter(description = "Referencia externa para trazabilidad")
        @RequestParam(required = false) String externalReference
    ) {
        String requestId = UUID.randomUUID().toString();
        log.info("POST /api/qr/decode/file - file={}, size={}, requestId={}",
            file.getOriginalFilename(), file.getSize(), requestId);

        try {
            // Paso 1: Decodificar el QR de la imagen usando ZXing
            log.debug("Decodificando QR desde archivo: {}", file.getOriginalFilename());
            String qrContent = qrImageDecoderService.decodeFromFile(file);
            log.info("QR decodificado desde archivo: longitud={} caracteres", qrContent.length());

            // Paso 2: Desencriptar el contenido del QR
            DecryptQrRequest decryptRequest = DecryptQrRequest.builder()
                .qrString(qrContent)
                .entityIdRequest(entityIdRequest)
                .externalReference(externalReference)
                .build();

            DecryptQrResponse response = qrDecryptionService.decrypt(decryptRequest);

            log.info("POST /api/qr/decode/file - Éxito: logId={}, requestId={}", response.getLogId(), requestId);

            // Headers de response
            HttpHeaders headers = new HttpHeaders();
            headers.add("X-Request-Id", requestId);
            headers.add("X-Log-Id", response.getLogId());
            headers.add("X-Source-File", file.getOriginalFilename());

            return ResponseEntity
                .ok()
                .headers(headers)
                .body(response);

        } catch (Exception e) {
            log.error("POST /api/qr/decode/file - Error: requestId={}, error={}", requestId, e.getMessage());
            throw e;
        }
    }

    /**
     * GET /api/qr/audits : Consulta auditorías de desencriptaciones con filtros.
     * <p>
     * Requiere rol: ADMIN o AUDITOR (temporalmente deshabilitado para testing)
     * </p>
     *
     * @param keycloakClientId Filtro por client ID (opcional)
     * @param certificateCode Filtro por código de certificado (opcional)
     * @param entityId Filtro por ID de entidad (opcional)
     * @param status Filtro por estado: SUCCESS o ERROR (opcional)
     * @param fromDate Filtro por fecha desde (opcional)
     * @param toDate Filtro por fecha hasta (opcional)
     * @param page Número de página (default: 0)
     * @param size Tamaño de página (default: 20, max: 100)
     * @param sort Campo de ordenamiento (default: createdDate)
     * @param order Orden: asc o desc (default: desc)
     * @return Página de logs de auditoría
     */
    @GetMapping("/audits")
    // TODO: Habilitar para producción
    // @PreAuthorize("hasAnyRole('ADMIN', 'AUDITOR')")
    @Operation(
        summary = "Consultar auditorías de desencriptaciones",
        description = "Consulta logs de auditoría de desencriptaciones con filtros avanzados."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Auditorías consultadas exitosamente",
            content = @Content(schema = @Schema(implementation = Page.class))
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Parámetros de filtro inválidos",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))
        )
    })
    public ResponseEntity<Page<DecryptionLog>> getAudits(
        @Parameter(description = "Filtrar por Client ID de Keycloak")
        @RequestParam(required = false) String keycloakClientId,

        @Parameter(description = "Filtrar por código de certificado")
        @RequestParam(required = false) String certificateCode,

        @Parameter(description = "Filtrar por ID de entidad")
        @RequestParam(required = false) String entityId,

        @Parameter(description = "Filtrar por estado: SUCCESS o ERROR")
        @RequestParam(required = false) String status,

        @Parameter(description = "Filtrar desde fecha (ISO-8601)")
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant fromDate,

        @Parameter(description = "Filtrar hasta fecha (ISO-8601)")
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant toDate,

        @Parameter(description = "Número de página (0-indexed)")
        @RequestParam(defaultValue = "0") Integer page,

        @Parameter(description = "Tamaño de página (max: 100)")
        @RequestParam(defaultValue = "20") Integer size,

        @Parameter(description = "Campo de ordenamiento")
        @RequestParam(defaultValue = "createdDate") String sort,

        @Parameter(description = "Orden: asc o desc")
        @RequestParam(defaultValue = "desc") String order
    ) {
        log.info("GET /api/qr/audits - page={}, size={}, status={}", page, size, status);

        // Validar tamaño de página
        if (size > 100) {
            log.warn("Tamaño de página solicitado ({}) excede el máximo (100), ajustando", size);
            size = 100;
        }

        // Construir filtro
        AuditLogFilter filter = AuditLogFilter.builder()
            .keycloakClientId(keycloakClientId)
            .certificateCode(certificateCode)
            .entityId(entityId)
            .status(status)
            .fromDate(fromDate)
            .toDate(toDate)
            .page(page)
            .size(size)
            .sort(sort)
            .order(order)
            .build();

        // Consultar auditorías
        Page<DecryptionLog> audits = auditService.queryDecryptionLogs(filter);

        log.info("GET /api/qr/audits - {} resultados, página {}/{}",
            audits.getTotalElements(), audits.getNumber() + 1, audits.getTotalPages());

        // Headers
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Total-Count", String.valueOf(audits.getTotalElements()));

        return ResponseEntity
            .ok()
            .headers(headers)
            .body(audits);
    }
}
