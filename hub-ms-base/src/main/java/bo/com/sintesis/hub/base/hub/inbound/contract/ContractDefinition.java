package bo.com.sintesis.hub.base.hub.inbound.contract;

import java.util.List;

/**
 * Definición completa de un contrato canónico inbound.
 *
 * <p>Una definición identifica unívocamente un producto y su versión,
 * junto con el conjunto de reglas de validación que deben cumplirse antes
 * de delegar al {@link bo.com.sintesis.hub.base.hub.inbound.port.InboundPort}.
 *
 * @param product         código del producto (ej. {@code "CASO_PENAL"})
 * @param version         versión del contrato (ej. {@code "v1"})
 * @param fields          lista ordenada de reglas de validación de campos
 * @param resourceIdField nombre del campo donde el dispatcher inyecta el ID del path en
 *                        operaciones PATCH (ej. {@code "id_pol_caso"}); {@code null} para
 *                        contratos POST/GET que no tienen ID de recurso en el path
 * @param httpMethod      método HTTP con el que el partner debe invocar el producto
 *                        ({@code POST}, {@code PATCH} o {@code GET} — ver {@link #isReadOnly()}).
 *                        Determina qué mapping de {@link bo.com.sintesis.hub.base.hub.inbound.DispatcherController}
 *                        puede servir este contrato; el dispatcher rechaza (403) una
 *                        petición cuyo verbo no coincida, para evitar que un {@code GET}
 *                        dispare accidentalmente un contrato de escritura sin payload
 *                        validado, o viceversa.
 */
public record ContractDefinition(
        String product,
        String version,
        List<FieldRule> fields,
        String resourceIdField,
        String httpMethod
) {
    /** Constructor abreviado para contratos POST sin ID de recurso en el path. */
    public ContractDefinition(String product, String version, List<FieldRule> fields) {
        this(product, version, fields, null, "POST");
    }

    /** Constructor abreviado que preserva la compatibilidad de contratos POST/PATCH existentes. */
    public ContractDefinition(String product, String version, List<FieldRule> fields, String resourceIdField) {
        this(product, version, fields, resourceIdField, "POST");
    }

    /**
     * {@code true} si el contrato es de solo lectura (declarado con {@code method: GET}
     * en {@code hub.apis}) — catálogos sin payload de entrada, ver ADR-0006/0007.
     */
    public boolean isReadOnly() {
        return "GET".equalsIgnoreCase(httpMethod);
    }
}
