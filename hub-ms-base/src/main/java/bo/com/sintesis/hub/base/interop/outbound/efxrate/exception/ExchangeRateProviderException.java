package bo.com.sintesis.hub.base.interop.outbound.efxrate.exception;

/**
 * Error de infraestructura del proveedor EfxRate: timeout, 5xx, red caída.
 *
 * Esta excepción SI dispara el mecanismo de retry y actúa como señal
 * de apertura para el circuit breaker. Representa fallos transitorios
 * donde la reintentada tiene posibilidad de éxito.
 */
public class ExchangeRateProviderException extends RuntimeException {

    public ExchangeRateProviderException(String message) {
        super(message);
    }

    public ExchangeRateProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
