package bo.com.sintesis.mdqr.base.service.exception;

/**
 * Excepción lanzada cuando se intenta usar un certificado revocado.
 */
public class CertificateRevokedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CertificateRevokedException(String message) {
        super(message);
    }

    public CertificateRevokedException(String message, Throwable cause) {
        super(message, cause);
    }
}
