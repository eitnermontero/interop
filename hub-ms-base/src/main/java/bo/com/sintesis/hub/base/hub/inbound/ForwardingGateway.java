package bo.com.sintesis.hub.base.hub.inbound;

import bo.com.sintesis.hub.base.hub.inbound.config.HubInteropProperties;
import bo.com.sintesis.hub.base.hub.inbound.port.ForwardResult;
import bo.com.sintesis.hub.base.hub.inbound.port.InboundPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Gateway de reenvío inbound del hub de interoperabilidad.
 *
 * <p>Resuelve el destino de cada {@code product:version} desde el plano de
 * control declarativo ({@code hub.apis}, ADR-0007):
 * <ul>
 *   <li>{@code adapter-bean: <nombre>} → bean {@link InboundPort} custom
 *       (stub en local, integraciones no HTTP/JSON).</li>
 *   <li>{@code connector: <nombre>} → {@link HttpForwardingAdapter} genérico
 *       contra el destino declarado en {@code hub.connectors}.</li>
 * </ul>
 *
 * <p>Políticas de resiliencia:
 * <ul>
 *   <li>Producto sin destino resoluble → 503 con mensaje legible.</li>
 *   <li>Excepción de infraestructura del adaptador → 502 (Bad Gateway).</li>
 * </ul>
 */
@Slf4j
@Component
public class ForwardingGateway {

    /** Beans {@link InboundPort} disponibles: beanName → port. */
    private final Map<String, InboundPort> ports;
    private final HubInteropProperties properties;
    private final HttpForwardingAdapter httpForwardingAdapter;

    /** Índice product:version → nombre lógico del bloque de API en hub.apis. */
    private final Map<String, String> apiIndex = new LinkedHashMap<>();

    public ForwardingGateway(Map<String, InboundPort> ports,
                             HubInteropProperties properties,
                             HttpForwardingAdapter httpForwardingAdapter) {
        this.ports = ports;
        this.properties = properties;
        this.httpForwardingAdapter = httpForwardingAdapter;
        properties.getApis().forEach((nombre, api) ->
                apiIndex.put(api.getProduct() + ":" + api.getVersion(), nombre));
        log.info("ForwardingGateway inicializado — {} API(s) declaradas, {} puerto(s) custom: {}",
                apiIndex.size(), ports.size(), ports.keySet());
    }

    /**
     * Reenvía el payload al destino declarado para el producto.
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
        String nombre = apiIndex.get(productKey);
        if (nombre == null) {
            log.warn("No hay API declarada en hub.apis para: {}", productKey);
            return noDisponible(product);
        }
        HubInteropProperties.ApiProps api = properties.getApis().get(nombre);

        try {
            // ── Destino custom: bean InboundPort ─────────────────────────────
            if (StringUtils.hasText(api.getAdapterBean())) {
                InboundPort port = ports.get(api.getAdapterBean());
                if (port == null) {
                    log.warn("Bean '{}' no encontrado en el contexto para producto: {}",
                            api.getAdapterBean(), productKey);
                    return noDisponible(product);
                }
                log.debug("Delegando al bean '{}' para {} correlationId={}",
                        api.getAdapterBean(), productKey, correlationId);
                return port.forward(product, version, payload, correlationId);
            }

            // ── Destino HTTP genérico: conector declarado ────────────────────
            HubInteropProperties.ConnectorProps connector =
                    properties.getConnectors().get(api.getConnector());
            log.debug("Delegando al conector '{}' para {} correlationId={}",
                    api.getConnector(), productKey, correlationId);
            return httpForwardingAdapter.forward(
                    api.getConnector(), connector, api, payload, correlationId);

        } catch (Exception ex) {
            log.error("Error en el destino de {}: {}", productKey, ex.getMessage(), ex);
            return new ForwardResult(false, 502, Map.of(),
                    "Error en el adaptador de destino: " + ex.getMessage());
        }
    }

    private static ForwardResult noDisponible(String product) {
        return new ForwardResult(false, 503, Map.of(),
                "Adaptador no disponible para " + product);
    }
}
