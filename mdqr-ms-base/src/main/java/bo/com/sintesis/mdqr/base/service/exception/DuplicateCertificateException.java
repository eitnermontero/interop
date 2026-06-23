package bo.com.sintesis.mdqr.base.service.exception;

/**
 * Excepción lanzada cuando se intenta crear un certificado duplicado.
 */
public class DuplicateCertificateException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DuplicateCertificateException(String message) {
        super(message);
    }

    public DuplicateCertificateException(String message, Throwable cause) {
        super(message, cause);
    }
}
