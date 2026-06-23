package bo.com.sintesis.mdqr.base.interop.outbound.efxrate.exception;

/**
 * El proveedor EfxRate no tiene datos de tipo de cambio para la combinación
 * de monedas y fecha solicitada.
 *
 * Esta excepción NO debe disparar el mecanismo de retry, ya que el proveedor
 * respondió de forma definitiva (4xx). Es el adaptador quien la lanza al
 * detectar esa categoría de respuesta.
 */
public class ExchangeRateNotFoundException extends RuntimeException {

    public ExchangeRateNotFoundException(String message) {
        super(message);
    }

    public ExchangeRateNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
