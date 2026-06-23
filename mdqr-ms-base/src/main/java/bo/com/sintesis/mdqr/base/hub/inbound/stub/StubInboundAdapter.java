package bo.com.sintesis.mdqr.base.hub.inbound.stub;

import bo.com.sintesis.mdqr.base.hub.inbound.port.ForwardResult;
import bo.com.sintesis.mdqr.base.hub.inbound.port.InboundPort;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Adaptador stub del puerto inbound — SOLO para modo desarrollo/pruebas.
 *
 * <p>No es un {@code @Component}: se registra condicionalmente como {@code @Bean} en
 * {@link bo.com.sintesis.mdqr.base.hub.inbound.config.InboundAutoConfiguration}
 * cuando {@code hub.inbound.stub-mode=true}.
 *
 * <p>Simula la aceptación de cualquier payload y devuelve un {@code id_pol_caso}
 * aleatorio para que el pipeline de auditoría y el test de integración puedan
 * verificar el flujo completo sin depender del sistema policial real.
 */
@Slf4j
public class StubInboundAdapter implements InboundPort {

    @Override
    public ForwardResult forward(String product, String version,
                                 Map<String, Object> payload, String correlationId) {
        int idPolCaso = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);

        log.warn("STUB MODE activo — producto={}/{} correlationId={} id_pol_caso={} " +
                 "— no usar en produccion",
                product, version, correlationId, idPolCaso);

        return new ForwardResult(
                true,
                201,
                Map.of("id_pol_caso", idPolCaso),
                "Caso aceptado por stub (modo desarrollo)"
        );
    }
}
