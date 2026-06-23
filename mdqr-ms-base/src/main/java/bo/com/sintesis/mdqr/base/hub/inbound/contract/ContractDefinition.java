package bo.com.sintesis.mdqr.base.hub.inbound.contract;

import java.util.List;

/**
 * Definición completa de un contrato canónico inbound.
 *
 * <p>Una definición identifica unívocamente un producto y su versión,
 * junto con el conjunto de reglas de validación que deben cumplirse antes
 * de delegar al {@link bo.com.sintesis.mdqr.base.hub.inbound.port.InboundPort}.
 *
 * @param product código del producto (ej. {@code "CASO_PENAL"})
 * @param version versión del contrato (ej. {@code "v1"})
 * @param fields  lista ordenada de reglas de validación de campos
 */
public record ContractDefinition(
        String product,
        String version,
        List<FieldRule> fields
) {}
