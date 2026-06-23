package bo.com.sintesis.mdqr.base.service.exception;

/**
 * Excepción lanzada cuando hay errores al comunicarse con la API de Tuxedo (Go API).
 * <p>
 * Escenarios:
 * - Timeout en la llamada HTTP
 * - Error de red (conexión rechazada, host unreachable)
 * - Error del servidor (5xx)
 * - Error de autenticación (401, 403)
 * - Error de validación (400)
 * </p>
 */
public class TuxedoApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Integer statusCode;

    /**
     * Crea una nueva excepción con el mensaje especificado.
     *
     * @param message Mensaje de error
     */
    public TuxedoApiException(String message) {
        super(message);
        this.statusCode = null;
    }

    /**
     * Crea una nueva excepción con el mensaje y causa especificados.
     *
     * @param message Mensaje de error
     * @param cause Causa original
     */
    public TuxedoApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = null;
    }

    /**
     * Crea una nueva excepción con el mensaje, código de estado y causa.
     *
     * @param message Mensaje de error
     * @param statusCode Código de estado HTTP
     * @param cause Causa original
     */
    public TuxedoApiException(String message, Integer statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /**
     * Obtiene el código de estado HTTP si está disponible.
     *
     * @return Código de estado o null
     */
    public Integer getStatusCode() {
        return statusCode;
    }

    /**
     * Crea una excepción para timeout.
     *
     * @param cause Causa original
     * @return Nueva excepción
     */
    public static TuxedoApiException timeout(Throwable cause) {
        return new TuxedoApiException(
            "Timeout al comunicarse con Tuxedo API: " + cause.getMessage(),
            cause
        );
    }

    /**
     * Crea una excepción para error de red.
     *
     * @param cause Causa original
     * @return Nueva excepción
     */
    public static TuxedoApiException networkError(Throwable cause) {
        return new TuxedoApiException(
            "Error de red al comunicarse con Tuxedo API: " + cause.getMessage(),
            cause
        );
    }

    /**
     * Crea una excepción para error del servidor con código de estado.
     *
     * @param statusCode Código de estado HTTP
     * @param responseBody Cuerpo de la respuesta
     * @return Nueva excepción
     */
    public static TuxedoApiException serverError(int statusCode, String responseBody) {
        return new TuxedoApiException(
            String.format("Tuxedo API retornó error %d: %s", statusCode, responseBody),
            statusCode,
            null
        );
    }
}
