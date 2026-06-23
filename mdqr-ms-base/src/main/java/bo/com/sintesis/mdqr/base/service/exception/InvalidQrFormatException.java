package bo.com.sintesis.mdqr.base.service.exception;

/**
 * Excepción lanzada cuando el formato del QR es inválido.
 * <p>
 * Escenarios:
 * - El QR no cumple con el patrón esperado: {encrypted_data}|{certificate_code}
 * - El QR está vacío o es null
 * - El QR no contiene el separador "|"
 * - El código de certificado está vacío
 * - Los datos encriptados están vacíos
 * </p>
 */
public class InvalidQrFormatException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private static final String EXPECTED_FORMAT = "{encrypted_base64}|{certificate_code}";

    /**
     * Crea una nueva excepción con el mensaje especificado.
     *
     * @param message Mensaje de error
     */
    public InvalidQrFormatException(String message) {
        super(message);
    }

    /**
     * Crea una nueva excepción con el mensaje y causa especificados.
     *
     * @param message Mensaje de error
     * @param cause Causa original
     */
    public InvalidQrFormatException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Crea una excepción para formato inválido con el formato esperado.
     *
     * @param qrString String del QR inválido
     * @return Nueva excepción
     */
    public static InvalidQrFormatException invalidFormat(String qrString) {
        return new InvalidQrFormatException(
            String.format(
                "Formato de QR inválido. Esperado: %s, Recibido: %s",
                EXPECTED_FORMAT,
                qrString != null && qrString.length() > 100
                    ? qrString.substring(0, 100) + "..."
                    : qrString
            )
        );
    }

    /**
     * Crea una excepción para QR vacío o null.
     *
     * @return Nueva excepción
     */
    public static InvalidQrFormatException emptyQr() {
        return new InvalidQrFormatException(
            "El QR no puede estar vacío o ser null"
        );
    }

    /**
     * Crea una excepción para QR sin separador.
     *
     * @return Nueva excepción
     */
    public static InvalidQrFormatException missingSeparator() {
        return new InvalidQrFormatException(
            String.format(
                "El QR debe contener el separador '|'. Formato esperado: %s",
                EXPECTED_FORMAT
            )
        );
    }

    /**
     * Crea una excepción para componente vacío del QR.
     *
     * @param componentName Nombre del componente (ej: "encrypted_data", "certificate_code")
     * @return Nueva excepción
     */
    public static InvalidQrFormatException emptyComponent(String componentName) {
        return new InvalidQrFormatException(
            String.format("El componente '%s' del QR no puede estar vacío", componentName)
        );
    }
}
