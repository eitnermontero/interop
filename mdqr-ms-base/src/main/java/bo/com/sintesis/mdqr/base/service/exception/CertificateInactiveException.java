package bo.com.sintesis.mdqr.base.service.exception;

/**
 * Excepción lanzada cuando se intenta usar un certificado inactivo.
 */
public class CertificateInactiveException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CertificateInactiveException(String message) {
        super(message);
    }

    public CertificateInactiveException(String message, Throwable cause) {
        super(message, cause);
    }
}
