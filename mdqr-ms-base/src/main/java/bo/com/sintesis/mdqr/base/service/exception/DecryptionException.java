package bo.com.sintesis.mdqr.base.service.exception;

/**
 * Excepción lanzada cuando falla el proceso de desencriptación de un QR.
 * <p>
 * Escenarios:
 * - Error en la desencriptación RSA (datos corruptos, certificado incorrecto)
 * - Formato de QR inválido
 * - Error en el parsing del PEM del certificado
 * - Error criptográfico (algoritmo no soportado, key inválida)
 * </p>
 */
public class DecryptionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Crea una nueva excepción con el mensaje especificado.
     *
     * @param message Mensaje de error
     */
    public DecryptionException(String message) {
        super(message);
    }

    /**
     * Crea una nueva excepción con el mensaje y causa especificados.
     *
     * @param message Mensaje de error
     * @param cause Causa original
     */
    public DecryptionException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Crea una excepción para error de desencriptación RSA.
     *
     * @param cause Causa original
     * @return Nueva excepción
     */
    public static DecryptionException rsaDecryptionFailed(Throwable cause) {
        return new DecryptionException(
            "Error al desencriptar datos con RSA: " + cause.getMessage(),
            cause
        );
    }

    /**
     * Crea una excepción para error de parsing de certificado.
     *
     * @param cause Causa original
     * @return Nueva excepción
     */
    public static DecryptionException certificateParsingFailed(Throwable cause) {
        return new DecryptionException(
            "Error al parsear certificado PEM: " + cause.getMessage(),
            cause
        );
    }
}
