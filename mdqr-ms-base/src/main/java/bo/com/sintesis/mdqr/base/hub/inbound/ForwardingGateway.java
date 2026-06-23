package bo.com.sintesis.mdqr.base.hub.inbound;

import bo.com.sintesis.mdqr.base.hub.inbound.port.ForwardResult;
import bo.com.sintesis.mdqr.base.hub.inbound.port.InboundPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Gateway de reenvío inbound del hub de interoperabilidad.
 *
 * <p>Resuelve el {@link InboundPort} correcto para cada combinación
 * {@code product:version} y delega la llamada. El mapeo de producto a bean
 * se registra en
 * {@link bo.com.sintesis.mdqr.base.hub.inbound.config.InboundAutoConfiguration}.
 *
 * <p>El mapa de puertos se inyecta por Spring en el constructor; la clave del
 * mapa es el nombre del bean Spring (qualifier). El mapeo lógico de
 * {@code product:version} → nombre de bean se mantiene en {@link #productBeanMap}.
 *
 * <p>Políticas de resiliencia:
 * <ul>
 *   <li>Si no hay un puerto registrado para el producto → 503 con mensaje legible.</li>
 *   <li>Si el puerto lanza una excepción de infraestructura → 502 (Bad Gateway).</li>
 * </ul>
 */
@Slf4j
@Component
public class ForwardingGateway {

    /** Mapa de todos los beans {@link InboundPort} disponibles: beanName → port. */
    private final Map<String, InboundPort> ports;

    /**
     * Mapeo estático de {@code "PRODUCT:version"} → nombre de bean Spring.
     * Se extiende al agregar nuevos adaptadores sin modificar este gateway.
     */
    private static final Map<String, String> PRODUCT_BEAN_MAP = Map.of(
            "CASO_PENAL:v1", "stubInboundAdapter"
    );

    public ForwardingGateway(Map<String, InboundPort> ports) {
        this.ports = ports;
        log.info("ForwardingGateway inicializado con {} puerto(s) inbound: {}",
                ports.size(), ports.keySet());
    }

    /**
     * Reenvía el payload al adaptador correspondiente.
     *
     * @param product       código del producto
     * @param version       versión del contrato
     * @param payload       mapa ya validado por {@code ContractValidator}
     * @param correlationId ID de correlación
     * @return resultado del reenvío; nunca {@code null}
     */
    public ForwardResult forward(String product, String version,
                                 Map<String, Object> payload, String correlationId) {
        String productKey = product + ":" + version;
        String beanName = PRODUCT_BEAN_MAP.get(productKey);

        if (beanName == null) {
            log.warn("No hay mapeo de bean registrado para producto: {}", productKey);
            return new ForwardResult(false, 503, Map.of(),
                    "Adaptador no disponible para " + product);
        }

        InboundPort port = ports.get(beanName);
        if (port == null) {
            log.warn("Bean '{}' no encontrado en el contexto para producto: {}", beanName, productKey);
            return new ForwardResult(false, 503, Map.of(),
                    "Adaptador no disponible para " + product);
        }

        try {
            log.debug("Delegando al adaptador '{}' para producto={} version={} correlationId={}",
                    beanName, product, version, correlationId);
            return port.forward(product, version, payload, correlationId);
        } catch (Exception ex) {
            log.error("Error en adaptador '{}' para producto={}: {}", beanName, productKey, ex.getMessage(), ex);
            return new ForwardResult(false, 502, Map.of(),
                    "Error en el adaptador de destino: " + ex.getMessage());
        }
    }
}
