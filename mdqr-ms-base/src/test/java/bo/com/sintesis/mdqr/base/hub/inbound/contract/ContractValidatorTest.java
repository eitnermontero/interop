package bo.com.sintesis.mdqr.base.hub.inbound.contract;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitarios sin Spring para {@link ContractValidator}.
 *
 * <p>Usa directamente el contrato CASO_PENAL/v1 definido en
 * {@link bo.com.sintesis.mdqr.base.hub.inbound.config.InboundAutoConfiguration}.
 */
@DisplayName("ContractValidator — validación del contrato CASO_PENAL/v1")
class ContractValidatorTest {

    private ContractValidator validator;
    private ContractDefinition contratoCasoPenal;

    @BeforeEach
    void setUp() {
        validator = new ContractValidator();
        contratoCasoPenal = buildContratoCasoPenalV1();
    }

    // ─── Payload válido ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Payload completo válido — sin violaciones")
    void payloadValidoCompleto_sinViolaciones() {
        Map<String, Object> payload = payloadRequeridosMinimos();
        payload.put("id_externo_caso_referencia", 9999);
        payload.put("es_reservado", false);
        payload.put("id_municipio", 1);
        payload.put("zona", "Norte");
        payload.put("direccion", "Av. Principal 123");
        payload.put("latitud", "-16.500000");
        payload.put("longitud", "-68.150000");
        payload.put("referencia", "Cerca del mercado");
        payload.put("relato", "Descripcion del caso");
        payload.put("fecha_caso", "2025-01-15T10:30:00-04:00");
        payload.put("fecha_fin", "2025-02-20T18:00:00-04:00");
        payload.put("fecha_aproximada", "Enero 2025");
        payload.put("denominacion_caso", "Caso ejemplo");
        payload.put("tags", List.of("etiqueta1", "etiqueta2"));

        List<ConstraintViolation> violations = validator.validate(payload, contratoCasoPenal);

        assertThat(violations).isEmpty();
    }

    // ─── Campos requeridos ────────────────────────────────────────────────────

    @Test
    @DisplayName("Campo requerido 'cud' ausente — genera violacion")
    void campoRequeridoCudAusente_generaViolacion() {
        Map<String, Object> payload = payloadRequeridosMinimos();
        payload.remove("cud");

        List<ConstraintViolation> violations = validator.validate(payload, contratoCasoPenal);

        assertThat(violations)
                .hasSize(1)
                .anySatisfy(v -> {
                    assertThat(v.field()).isEqualTo("cud");
                    assertThat(v.message()).isEqualTo("El campo es requerido");
                });
    }

    @Test
    @DisplayName("Campo requerido 'cud' con valor nulo — genera violacion")
    void campoRequeridoCudNulo_generaViolacion() {
        Map<String, Object> payload = new HashMap<>(payloadRequeridosMinimos());
        payload.put("cud", null);

        List<ConstraintViolation> violations = validator.validate(payload, contratoCasoPenal);

        assertThat(violations)
                .anySatisfy(v -> {
                    assertThat(v.field()).isEqualTo("cud");
                    assertThat(v.message()).isEqualTo("El campo es requerido");
                });
    }

    // ─── Tipo incorrecto ──────────────────────────────────────────────────────

    @Test
    @DisplayName("'id_externo_caso' como String en lugar de Integer — violacion de tipo")
    void idExternoCasoComoString_violacionDeTipo() {
        Map<String, Object> payload = payloadRequeridosMinimos();
        payload.put("id_externo_caso", "NO_SOY_ENTERO");

        List<ConstraintViolation> violations = validator.validate(payload, contratoCasoPenal);

        assertThat(violations)
                .anySatisfy(v -> {
                    assertThat(v.field()).isEqualTo("id_externo_caso");
                    assertThat(v.message()).contains("entero");
                });
    }

    // ─── Formato DATETIME ─────────────────────────────────────────────────────

    @Test
    @DisplayName("'fecha_caso' con valor no ISO 8601 — violacion de formato")
    void fechaCasoNoIso8601_violacionDeFormato() {
        Map<String, Object> payload = payloadRequeridosMinimos();
        payload.put("fecha_caso", "15/01/2025"); // formato incorrecto

        List<ConstraintViolation> violations = validator.validate(payload, contratoCasoPenal);

        assertThat(violations)
                .anySatisfy(v -> {
                    assertThat(v.field()).isEqualTo("fecha_caso");
                    assertThat(v.message()).containsIgnoringCase("iso");
                });
    }

    // ─── maxLength ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("'zona' con longitud mayor a 255 — violacion de longitud")
    void zonaConLongitudSuperior_violacionDeLongitud() {
        Map<String, Object> payload = payloadRequeridosMinimos();
        payload.put("zona", "Z".repeat(256)); // maxLength=255

        List<ConstraintViolation> violations = validator.validate(payload, contratoCasoPenal);

        assertThat(violations)
                .anySatisfy(v -> {
                    assertThat(v.field()).isEqualTo("zona");
                    assertThat(v.message()).contains("255");
                });
    }

    // ─── ARRAY ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("'tags' como String en lugar de Array — violacion de tipo")
    void tagsComoString_violacionDeTipo() {
        Map<String, Object> payload = payloadRequeridosMinimos();
        payload.put("tags", "NO_SOY_ARRAY");

        List<ConstraintViolation> violations = validator.validate(payload, contratoCasoPenal);

        assertThat(violations)
                .anySatisfy(v -> {
                    assertThat(v.field()).isEqualTo("tags");
                    assertThat(v.message()).containsIgnoringCase("array");
                });
    }

    // ─── Campos opcionales ausentes ───────────────────────────────────────────

    @Test
    @DisplayName("Campos opcionales ausentes — sin violaciones")
    void camposOpcionalesAusentes_sinViolaciones() {
        // Solo campos requeridos, ningún opcional
        Map<String, Object> payload = payloadRequeridosMinimos();

        List<ConstraintViolation> violations = validator.validate(payload, contratoCasoPenal);

        assertThat(violations).isEmpty();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Payload mínimo con todos los campos requeridos de CASO_PENAL/v1.
     * Usa {@link HashMap} para permitir mutación en los tests.
     */
    private Map<String, Object> payloadRequeridosMinimos() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("cud", "CUD-TEST-001");
        payload.put("id_externo_caso", 12345);
        payload.put("id_tipo_denuncia", 1);
        payload.put("id_oficina", 10);
        payload.put("id_estado", 1);
        payload.put("id_etapa", 2);
        return payload;
    }

    /**
     * Reconstruye el contrato CASO_PENAL/v1 localmente para no depender del contexto Spring.
     */
    private ContractDefinition buildContratoCasoPenalV1() {
        List<FieldRule> campos = List.of(
                new FieldRule("cud",                        FieldType.STRING,   true,  50,   null),
                new FieldRule("id_externo_caso",            FieldType.INTEGER,  true,  null, null),
                new FieldRule("id_tipo_denuncia",           FieldType.INTEGER,  true,  null, null),
                new FieldRule("id_oficina",                 FieldType.INTEGER,  true,  null, null),
                new FieldRule("id_estado",                  FieldType.INTEGER,  true,  null, null),
                new FieldRule("id_etapa",                   FieldType.INTEGER,  true,  null, null),
                new FieldRule("id_externo_caso_referencia", FieldType.INTEGER,  false, null, null),
                new FieldRule("es_reservado",               FieldType.BOOLEAN,  false, null, null),
                new FieldRule("id_municipio",               FieldType.INTEGER,  false, null, null),
                new FieldRule("zona",                       FieldType.STRING,   false, 255,  null),
                new FieldRule("direccion",                  FieldType.STRING,   false, null, null),
                new FieldRule("latitud",                    FieldType.STRING,   false, 30,   null),
                new FieldRule("longitud",                   FieldType.STRING,   false, 30,   null),
                new FieldRule("referencia",                 FieldType.STRING,   false, null, null),
                new FieldRule("relato",                     FieldType.STRING,   false, null, null),
                new FieldRule("fecha_caso",                 FieldType.DATETIME, false, null, "iso8601"),
                new FieldRule("fecha_fin",                  FieldType.DATETIME, false, null, "iso8601"),
                new FieldRule("fecha_aproximada",           FieldType.STRING,   false, 255,  null),
                new FieldRule("denominacion_caso",          FieldType.STRING,   false, 500,  null),
                new FieldRule("tags",                       FieldType.ARRAY,    false, null, null)
        );
        return new ContractDefinition("CASO_PENAL", "v1", campos);
    }
}
