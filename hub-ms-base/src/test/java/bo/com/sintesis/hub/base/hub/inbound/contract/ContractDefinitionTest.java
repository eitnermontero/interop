package bo.com.sintesis.hub.base.hub.inbound.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitarios sin Spring para {@link ContractDefinition}.
 *
 * <p>Cubre cómo se señaliza que un contrato es de solo lectura ({@code httpMethod=GET})
 * y la compatibilidad hacia atrás de los constructores abreviados usados por contratos
 * POST/PATCH existentes (que no declaran {@code httpMethod} explícitamente).
 */
@DisplayName("ContractDefinition — httpMethod / isReadOnly()")
class ContractDefinitionTest {

    @Test
    @DisplayName("Constructor de 3 args (POST clásico) — httpMethod=POST, isReadOnly=false")
    void constructorTresArgs_esPostPorDefecto() {
        ContractDefinition contrato = new ContractDefinition("CASO_PENAL", "v1", List.of());

        assertThat(contrato.httpMethod()).isEqualTo("POST");
        assertThat(contrato.isReadOnly()).isFalse();
    }

    @Test
    @DisplayName("Constructor de 4 args (PATCH con resourceIdField) — httpMethod=POST por defecto")
    void constructorCuatroArgs_esPostPorDefecto() {
        ContractDefinition contrato = new ContractDefinition(
                "CASO_PENAL_EDITAR", "v1", List.of(), "id_pol_caso");

        assertThat(contrato.httpMethod()).isEqualTo("POST");
        assertThat(contrato.resourceIdField()).isEqualTo("id_pol_caso");
        assertThat(contrato.isReadOnly()).isFalse();
    }

    @Test
    @DisplayName("Constructor completo con httpMethod=GET — isReadOnly=true")
    void constructorCompleto_conHttpMethodGet_esSoloLectura() {
        ContractDefinition contrato = new ContractDefinition(
                "CATALOGO_UNIDADES", "v1", List.of(), null, "GET");

        assertThat(contrato.isReadOnly()).isTrue();
    }

    @Test
    @DisplayName("Constructor completo con httpMethod=PATCH — isReadOnly=false")
    void constructorCompleto_conHttpMethodPatch_noEsSoloLectura() {
        ContractDefinition contrato = new ContractDefinition(
                "CASO_PENAL_EDITAR", "v1", List.of(), "id_pol_caso", "PATCH");

        assertThat(contrato.isReadOnly()).isFalse();
    }

    @Test
    @DisplayName("isReadOnly() es case-insensitive respecto a httpMethod")
    void isReadOnly_esCaseInsensitive() {
        ContractDefinition contrato = new ContractDefinition(
                "CATALOGO_UNIDADES", "v1", List.of(), null, "get");

        assertThat(contrato.isReadOnly()).isTrue();
    }
}
