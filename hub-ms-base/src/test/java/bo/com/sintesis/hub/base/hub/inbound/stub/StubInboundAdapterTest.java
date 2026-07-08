package bo.com.sintesis.hub.base.hub.inbound.stub;

import bo.com.sintesis.hub.base.hub.inbound.port.ForwardResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitarios sin Spring para {@link StubInboundAdapter}.
 */
@DisplayName("StubInboundAdapter — modo desarrollo")
class StubInboundAdapterTest {

    private StubInboundAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new StubInboundAdapter();
    }

    @Test
    @DisplayName("forward() devuelve ForwardResult con ok=true y httpStatus=201")
    void forward_devuelveResultadoExitoso() {
        Map<String, Object> payload = Map.of(
                "cud", "CUD-STUB-001",
                "id_externo_caso", 1001,
                "id_tipo_denuncia", 1,
                "id_oficina", 5,
                "id_estado", 1,
                "id_etapa", 2
        );

        ForwardResult result = adapter.forward(
                "CASO_PENAL", "v1", payload, UUID.randomUUID().toString());

        assertThat(result).isNotNull();
        assertThat(result.ok()).isTrue();
        assertThat(result.httpStatus()).isEqualTo(201);
        assertThat(result.message()).isNotBlank();
    }

    @Test
    @DisplayName("forward() devuelve id_pol_caso como Integer positivo")
    void forward_devuelveIdPolCasoIntegerPositivo() {
        Map<String, Object> payload = Map.of(
                "cud", "CUD-STUB-002",
                "id_externo_caso", 2002
        );

        ForwardResult result = adapter.forward(
                "CASO_PENAL", "v1", payload, UUID.randomUUID().toString());

        // ForwardResult.data() es Object (soporta tanto objetos JSON como arrays de
        // catálogos GET) — el stub siempre devuelve un Map, así que se castea aquí.
        Map<String, ?> data = (Map<String, ?>) result.data();
        assertThat(data).containsKey("id_pol_caso");
        Object idPolCaso = data.get("id_pol_caso");
        assertThat(idPolCaso).isInstanceOf(Integer.class);
        assertThat((Integer) idPolCaso).isPositive();
    }

    @Test
    @DisplayName("forward() invocaciones repetidas generan id_pol_caso distintos (probabilistico)")
    void forward_invocacionesRepetidas_generanIdsDiferentes() {
        // Con rango 1..Integer.MAX_VALUE la probabilidad de colision es ~0
        ForwardResult r1 = adapter.forward("CASO_PENAL", "v1", Map.of(), "corr-1");
        ForwardResult r2 = adapter.forward("CASO_PENAL", "v1", Map.of(), "corr-2");
        ForwardResult r3 = adapter.forward("CASO_PENAL", "v1", Map.of(), "corr-3");

        // Al menos dos de tres deben ser distintos (prácticamente siempre todos distintos)
        int id1 = (Integer) ((Map<String, ?>) r1.data()).get("id_pol_caso");
        int id2 = (Integer) ((Map<String, ?>) r2.data()).get("id_pol_caso");
        int id3 = (Integer) ((Map<String, ?>) r3.data()).get("id_pol_caso");

        assertThat(List.of(id1, id2, id3).stream().distinct().count())
                .as("Con ThreadLocalRandom y rango amplio los IDs deben ser distintos")
                .isGreaterThan(1);
    }
}
