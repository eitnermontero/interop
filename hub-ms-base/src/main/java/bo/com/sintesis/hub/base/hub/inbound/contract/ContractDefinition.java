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
 *                        contratos POST que no tienen ID de recurso en el path
 */
public record ContractDefinition(
        String product,
        String version,
        List<FieldRule> fields,
        String resourceIdField
) {
    /** Constructor abreviado para contratos POST sin ID de recurso en el path. */
    public ContractDefinition(String product, String version, List<FieldRule> fields) {
        this(product, version, fields, null);
    }
}
