package bo.com.sintesis.hub.base.hub.inbound.contract;

/**
 * Excepción lanzada cuando el {@link ContractRegistry} no encuentra un contrato
 * para el producto y versión solicitados.
 *
 * <p>El {@link bo.com.sintesis.hub.base.web.rest.errors.GlobalExceptionHandler}
 * la captura y devuelve un 403 con {@code error.code=PRODUCT_NOT_AUTHORIZED}.
 */
public class ProductNotFoundException extends RuntimeException {

    private final String product;
    private final String version;

    public ProductNotFoundException(String product, String version) {
        super("Producto no autorizado o no registrado: " + product + "/" + version);
        this.product = product;
        this.version = version;
    }

    public String getProduct() {
        return product;
    }

    public String getVersion() {
        return version;
    }
}
