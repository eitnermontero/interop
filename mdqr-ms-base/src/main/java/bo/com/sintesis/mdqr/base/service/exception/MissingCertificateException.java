package bo.com.sintesis.mdqr.base.service.exception;

/**
 * Excepción lanzada cuando no se encuentra un certificado solicitado.
 * <p>
 * Escenarios:
 * - El código de certificado no existe en el JKS de Tuxedo
 * - El certificado fue eliminado o revocado
 * - El alias del certificado no se encuentra
 * </p>
 */
public class MissingCertificateException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Crea una nueva excepción con el mensaje especificado.
     *
     * @param message Mensaje de error
     */
    public MissingCertificateException(String message) {
        super(message);
    }

    /**
     * Crea una nueva excepción con el mensaje y causa especificados.
     *
     * @param message Mensaje de error
     * @param cause Causa original
     */
    public MissingCertificateException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Crea una excepción para un código de certificado específico.
     *
     * @param certificateCode Código del certificado no encontrado
     * @return Nueva excepción
     */
    public static MissingCertificateException forCode(String certificateCode) {
        return new MissingCertificateException(
            String.format("Certificado no encontrado con código: %s", certificateCode)
        );
    }

    /**
     * Crea una excepción para un alias específico.
     *
     * @param alias Alias del certificado no encontrado
     * @return Nueva excepción
     */
    public static MissingCertificateException forAlias(String alias) {
        return new MissingCertificateException(
            String.format("Certificado no encontrado con alias: %s", alias)
        );
    }
}
