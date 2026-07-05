package bo.com.sintesis.hub.base.hub.inbound.port;

import java.util.Map;

/**
 * Puerto estable del motor inbound del hub de interoperabilidad.
 *
 * <p>Cada producto (ej. {@code CASO_PENAL}) tiene exactamente un adaptador que
 * implementa esta interfaz. El {@link bo.com.sintesis.hub.base.hub.inbound.ForwardingGateway}
 * resuelve el adaptador correcto por {@code product + version} y lo invoca.
 *
 * <p>Los implementadores no deben arrojar excepciones de negocio; deben
 * encapsular cualquier fallo en un {@link ForwardResult} con {@code ok=false}.
 * Las excepciones de infraestructura (timeout, conexión) se dejan propagar
 * para que el {@code ForwardingGateway} las envuelva en un 502.
 */
public interface InboundPort {

    /**
     * Reenvía el payload canónico al sistema destino.
     *
     * @param product       código del producto (ej. {@code "CASO_PENAL"})
     * @param version       versión del contrato (ej. {@code "v1"})
     * @param payload       mapa de campos ya validados por {@code ContractValidator}
     * @param correlationId ID de correlación para trazabilidad distribuida
     * @return resultado del reenvío; nunca {@code null}
     */
    ForwardResult forward(String product, String version,
                          Map<String, Object> payload, String correlationId);
}
