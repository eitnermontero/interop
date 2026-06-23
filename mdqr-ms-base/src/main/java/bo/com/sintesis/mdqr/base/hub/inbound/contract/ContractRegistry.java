package bo.com.sintesis.mdqr.base.hub.inbound.contract;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registro en memoria de contratos canónicos inbound.
 *
 * <p>Los contratos se registran en el arranque desde
 * {@link bo.com.sintesis.mdqr.base.hub.inbound.config.InboundAutoConfiguration}.
 * El registro es concurrente y seguro para lectura simultánea desde múltiples hilos
 * (hebras de Tomcat).
 *
 * <p>La clave del mapa interno es {@code product + ":" + version} (ej. {@code "CASO_PENAL:v1"}).
 */
@Slf4j
@Component
public class ContractRegistry {

    private final Map<String, ContractDefinition> registry = new ConcurrentHashMap<>();

    /**
     * Registra un contrato. Si ya existe una definición para el mismo producto/versión,
     * la sobreescribe y loggea una advertencia.
     *
     * @param definition contrato a registrar; no debe ser {@code null}
     */
    public void register(ContractDefinition definition) {
        String key = buildKey(definition.product(), definition.version());
        ContractDefinition prev = registry.put(key, definition);
        if (prev != null) {
            log.warn("Contrato sobreescrito en registry: {} — anterior tenía {} campos, nuevo tiene {}",
                    key, prev.fields().size(), definition.fields().size());
        } else {
            log.info("Contrato registrado: {} ({} campos)", key, definition.fields().size());
        }
    }

    /**
     * Busca un contrato por producto y versión.
     *
     * @param product código del producto
     * @param version versión del contrato
     * @return {@link Optional} vacío si no existe ningún contrato registrado
     */
    public Optional<ContractDefinition> lookup(String product, String version) {
        return Optional.ofNullable(registry.get(buildKey(product, version)));
    }

    private static String buildKey(String product, String version) {
        return product + ":" + version;
    }
}
