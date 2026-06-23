package bo.com.sintesis.mdqr.base.web.rest.errors;

import bo.com.sintesis.mdqr.base.service.exception.MissingCertificateException;
import bo.com.sintesis.mdqr.base.service.exception.DecryptionException;
import bo.com.sintesis.mdqr.base.service.exception.InvalidQrFormatException;
import bo.com.sintesis.mdqr.base.service.exception.TuxedoApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Manejador global de excepciones para la aplicación.
 * <p>
 * Implementa RFC 7807 (Problem Details for HTTP APIs) usando ProblemDetail de Spring 6+.
 * Convierte excepciones en respuestas HTTP estructuradas con código de error, timestamp y detalles.
 * </p>
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    private static final String TYPE_PREFIX = "https://api.sintesis.com.bo/problems/";
    private static final String TIMESTAMP_PROPERTY = "timestamp";
    private static final String ERROR_CODE_PROPERTY = "errorCode";

    /**
     * Maneja errores de validación de argumentos (Bean Validation).
     * Retorna 400 BAD REQUEST con detalles de cada campo inválido.
     *
     * @param ex Excepción de validación
     * @param request Request actual
     * @return ProblemDetail con violations
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleMethodArgumentNotValid(
        MethodArgumentNotValidException ex,
        WebRequest request
    ) {
        log.warn("Validation error: {}", ex.getMessage());

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            "Error de validación en los datos enviados"
        );

        problemDetail.setType(URI.create(TYPE_PREFIX + "validation-error"));
        problemDetail.setTitle("Validation Error");
        problemDetail.setProperty(TIMESTAMP_PROPERTY, Instant.now());
        problemDetail.setProperty(ERROR_CODE_PROPERTY, "VALIDATION_ERROR");

        // Agregar violations (campo -> mensaje)
        Map<String, String> violations = ex.getBindingResult().getFieldErrors().stream()
            .collect(Collectors.toMap(
                FieldError::getField,
                error -> error.getDefaultMessage() != null ? error.getDefaultMessage() : "Invalid value",
                (existing, replacement) -> existing // Si hay duplicados, mantener el primero
            ));

        problemDetail.setProperty("violations", violations);

        return problemDetail;
    }

    /**
     * Maneja MissingCertificateException.
     * Retorna 404 NOT FOUND.
     *
     * @param ex Excepción
     * @param request Request actual
     * @return ProblemDetail
     */
    @ExceptionHandler(MissingCertificateException.class)
    public ProblemDetail handleCertificateNotFound(
        MissingCertificateException ex,
        WebRequest request
    ) {
        log.warn("Certificate not found: {}", ex.getMessage());

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.NOT_FOUND,
            ex.getMessage()
        );

        problemDetail.setType(URI.create(TYPE_PREFIX + "certificate-not-found"));
        problemDetail.setTitle("Certificate Not Found");
        problemDetail.setProperty(TIMESTAMP_PROPERTY, Instant.now());
        problemDetail.setProperty(ERROR_CODE_PROPERTY, "CERTIFICATE_NOT_FOUND");

        return problemDetail;
    }

    /**
     * Maneja InvalidQrFormatException.
     * Retorna 400 BAD REQUEST.
     *
     * @param ex Excepción
     * @param request Request actual
     * @return ProblemDetail
     */
    @ExceptionHandler(InvalidQrFormatException.class)
    public ProblemDetail handleInvalidQrFormat(
        InvalidQrFormatException ex,
        WebRequest request
    ) {
        log.warn("Invalid QR format: {}", ex.getMessage());

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            ex.getMessage()
        );

        problemDetail.setType(URI.create(TYPE_PREFIX + "invalid-qr-format"));
        problemDetail.setTitle("Invalid QR Format");
        problemDetail.setProperty(TIMESTAMP_PROPERTY, Instant.now());
        problemDetail.setProperty(ERROR_CODE_PROPERTY, "INVALID_QR_FORMAT");

        return problemDetail;
    }

    /**
     * Maneja DecryptionException.
     * Retorna 500 INTERNAL SERVER ERROR.
     *
     * @param ex Excepción
     * @param request Request actual
     * @return ProblemDetail
     */
    @ExceptionHandler(DecryptionException.class)
    public ProblemDetail handleDecryptionException(
        DecryptionException ex,
        WebRequest request
    ) {
        log.error("Decryption error: {}", ex.getMessage(), ex);

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Error al desencriptar el QR. Verifique el formato y el certificado."
        );

        problemDetail.setType(URI.create(TYPE_PREFIX + "decryption-error"));
        problemDetail.setTitle("Decryption Error");
        problemDetail.setProperty(TIMESTAMP_PROPERTY, Instant.now());
        problemDetail.setProperty(ERROR_CODE_PROPERTY, "DECRYPTION_ERROR");
        problemDetail.setProperty("technicalDetail", ex.getMessage());

        return problemDetail;
    }

    /**
     * Maneja TuxedoApiException.
     * Retorna 502 BAD GATEWAY.
     *
     * @param ex Excepción
     * @param request Request actual
     * @return ProblemDetail
     */
    @ExceptionHandler(TuxedoApiException.class)
    public ProblemDetail handleTuxedoApiException(
        TuxedoApiException ex,
        WebRequest request
    ) {
        log.error("Tuxedo API error: {}", ex.getMessage(), ex);

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_GATEWAY,
            "Error al comunicarse con el servicio de certificados. Intente nuevamente."
        );

        problemDetail.setType(URI.create(TYPE_PREFIX + "tuxedo-api-error"));
        problemDetail.setTitle("External Service Error");
        problemDetail.setProperty(TIMESTAMP_PROPERTY, Instant.now());
        problemDetail.setProperty(ERROR_CODE_PROPERTY, "TUXEDO_API_ERROR");
        problemDetail.setProperty("technicalDetail", ex.getMessage());

        if (ex.getStatusCode() != null) {
            problemDetail.setProperty("upstreamStatusCode", ex.getStatusCode());
        }

        return problemDetail;
    }

    /**
     * Maneja AccessDeniedException (403 FORBIDDEN).
     * Cuando el usuario no tiene permisos para acceder a un recurso.
     *
     * @param ex Excepción
     * @param request Request actual
     * @return ProblemDetail
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(
        AccessDeniedException ex,
        WebRequest request
    ) {
        log.warn("Access denied: {}", ex.getMessage());

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.FORBIDDEN,
            "No tiene permisos para acceder a este recurso"
        );

        problemDetail.setType(URI.create(TYPE_PREFIX + "access-denied"));
        problemDetail.setTitle("Access Denied");
        problemDetail.setProperty(TIMESTAMP_PROPERTY, Instant.now());
        problemDetail.setProperty(ERROR_CODE_PROPERTY, "ACCESS_DENIED");

        return problemDetail;
    }

    /**
     * Maneja AuthenticationException (401 UNAUTHORIZED).
     * Cuando las credenciales son inválidas o no se proporcionaron.
     *
     * @param ex Excepción
     * @param request Request actual
     * @return ProblemDetail
     */
    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthenticationException(
        AuthenticationException ex,
        WebRequest request
    ) {
        log.warn("Authentication error: {}", ex.getMessage());

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.UNAUTHORIZED,
            "Autenticación inválida o token expirado"
        );

        problemDetail.setType(URI.create(TYPE_PREFIX + "authentication-error"));
        problemDetail.setTitle("Authentication Error");
        problemDetail.setProperty(TIMESTAMP_PROPERTY, Instant.now());
        problemDetail.setProperty(ERROR_CODE_PROPERTY, "AUTHENTICATION_ERROR");

        return problemDetail;
    }

    /**
     * Maneja ErrorResponseException y subclases (ResponseStatusException, NoResourceFoundException, etc.).
     * NoResourceFoundException en Spring 6.1 extiende ErrorResponseException directamente —
     * no ResponseStatusException — por eso el catch-all Exception.class lo convertía en 500.
     */
    @ExceptionHandler(ErrorResponseException.class)
    public ProblemDetail handleErrorResponse(
        ErrorResponseException ex,
        WebRequest request
    ) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;

        String detail = ex instanceof ResponseStatusException rse && rse.getReason() != null
            ? rse.getReason()
            : status.getReasonPhrase();

        if (status.is5xxServerError()) {
            log.error("HTTP {}: {}", status.value(), ex.getMessage());
        } else {
            log.warn("HTTP {}: {}", status.value(), ex.getMessage());
        }

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setType(URI.create(TYPE_PREFIX + status.name().toLowerCase().replace('_', '-')));
        problemDetail.setTitle(status.getReasonPhrase());
        problemDetail.setProperty(TIMESTAMP_PROPERTY, Instant.now());
        problemDetail.setProperty(ERROR_CODE_PROPERTY, status.name());

        return problemDetail;
    }

    /**
     * Maneja excepciones genéricas no capturadas por otros handlers.
     * Retorna 500 INTERNAL SERVER ERROR.
     *
     * @param ex Excepción
     * @param request Request actual
     * @return ProblemDetail
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGenericException(
        Exception ex,
        WebRequest request
    ) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Error interno del servidor. Por favor contacte al administrador."
        );

        problemDetail.setType(URI.create(TYPE_PREFIX + "internal-error"));
        problemDetail.setTitle("Internal Server Error");
        problemDetail.setProperty(TIMESTAMP_PROPERTY, Instant.now());
        problemDetail.setProperty(ERROR_CODE_PROPERTY, "INTERNAL_ERROR");

        // En desarrollo, agregar detalles técnicos
        // TODO: Condicionar esto según el profile activo
        problemDetail.setProperty("technicalDetail", ex.getMessage());
        problemDetail.setProperty("exceptionType", ex.getClass().getSimpleName());

        return problemDetail;
    }
}
