package bo.com.sintesis.hub.base.hub.inbound.config;

import bo.com.sintesis.hub.base.hub.inbound.contract.ContractDefinition;
import bo.com.sintesis.hub.base.hub.inbound.contract.ContractRegistry;
import bo.com.sintesis.hub.base.hub.inbound.contract.FieldRule;
import bo.com.sintesis.hub.base.hub.inbound.contract.FieldType;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitarios del {@link InboundContractOpenApiCustomizer}.
 *
 * <p>Sin Spring context: instancia directamente el customizer con un
 * {@link ContractRegistry} poblado y verifica el {@link OpenAPI} resultante.
 *
 * <p>Contratos usados:
 * <ul>
 *   <li>{@code CASO_PENAL/v1} — producto POST normal (3 campos: 2 requeridos, 1 opcional)</li>
 *   <li>{@code CASO_PENAL_EDITAR/v1} — producto PATCH con {@code resourceIdField}</li>
 * </ul>
 */
@DisplayName("InboundContractOpenApiCustomizer — generación de spec desde ContractRegistry")
class InboundContractOpenApiCustomizerTest {

    private InboundContractOpenApiCustomizer customizer;
    private OpenAPI openApi;

    @BeforeEach
    void setUp() {
        ContractRegistry registry = new ContractRegistry();
        registry.register(contratoCasoPenalPost());
        registry.register(contratoCasoPenalEditar());
        registry.register(contratoCatalogoGet());

        customizer = new InboundContractOpenApiCustomizer(registry);
        openApi = new OpenAPI();
        customizer.customise(openApi);
    }

    // ─── Paths generados ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Debe generar path POST para CASO_PENAL/v1")
    void debeGenerarPathPostParaCasoPenal() {
        assertThat(openApi.getPaths())
                .as("Paths no deben ser null")
                .isNotNull()
                .containsKey("/api/inbound/CASO_PENAL/v1");

        PathItem item = openApi.getPaths().get("/api/inbound/CASO_PENAL/v1");
        assertThat(item.getPost())
                .as("La operacion POST debe existir en el path")
                .isNotNull();
        assertThat(item.getPatch())
                .as("No debe existir operacion PATCH para un producto POST")
                .isNull();
    }

    @Test
    @DisplayName("Debe generar path PATCH para CASO_PENAL_EDITAR/v1 con segmento {id}")
    void debeGenerarPathPatchParaCasoPenalEditar() {
        assertThat(openApi.getPaths())
                .containsKey("/api/inbound/CASO_PENAL/v1/{id}");

        PathItem item = openApi.getPaths().get("/api/inbound/CASO_PENAL/v1/{id}");
        assertThat(item.getPatch())
                .as("La operacion PATCH debe existir en el path de edicion")
                .isNotNull();
        assertThat(item.getPost())
                .as("No debe existir operacion POST en el path de edicion")
                .isNull();
    }

    // ─── Schemas de respuesta ($ref en components) ─────────────────────────────

    @Test
    @DisplayName("Debe registrar los schemas ApiResponse, ApiError y ApiViolation en components")
    void debeRegistrarSchemasComponente() {
        assertThat(openApi.getComponents()).isNotNull();
        assertThat(openApi.getComponents().getSchemas())
                .containsKey(InboundContractOpenApiCustomizer.SCHEMA_API_RESPONSE)
                .containsKey(InboundContractOpenApiCustomizer.SCHEMA_API_ERROR)
                .containsKey(InboundContractOpenApiCustomizer.SCHEMA_API_VIOLATION);
    }

    @Test
    @DisplayName("Responses de POST deben referenciar ApiResponse con $ref")
    void responsesDePostDebenUsarRef() {
        var postOp = openApi.getPaths()
                .get("/api/inbound/CASO_PENAL/v1")
                .getPost();

        assertThat(postOp.getResponses()).isNotNull();
        assertThat(postOp.getResponses()).containsKey("201");
        assertThat(postOp.getResponses()).containsKey("400");
        assertThat(postOp.getResponses()).containsKey("403");
        assertThat(postOp.getResponses()).containsKey("409");
        assertThat(postOp.getResponses()).containsKey("500");

        // El media type del 201 debe tener un schema $ref a ApiResponse
        var schema201 = postOp.getResponses().get("201")
                .getContent().get("application/json").getSchema();
        assertThat(schema201.get$ref())
                .as("La respuesta 201 debe referenciar el schema ApiResponse con $ref")
                .endsWith(InboundContractOpenApiCustomizer.SCHEMA_API_RESPONSE);
    }

    @Test
    @DisplayName("Responses de PATCH deben incluir 200, no 201")
    void responsesDePathDebenIncluir200() {
        var patchOp = openApi.getPaths()
                .get("/api/inbound/CASO_PENAL/v1/{id}")
                .getPatch();

        assertThat(patchOp.getResponses())
                .containsKey("200")
                .doesNotContainKey("201");
    }

    // ─── Schema del requestBody ────────────────────────────────────────────────

    @Test
    @DisplayName("RequestBody de POST debe contener los campos del contrato con tipos correctos")
    void requestBodyDebeContenerCamposConTiposCorrectos() {
        var requestBody = openApi.getPaths()
                .get("/api/inbound/CASO_PENAL/v1")
                .getPost()
                .getRequestBody();

        assertThat(requestBody).isNotNull();
        assertThat(requestBody.getRequired()).isTrue();

        @SuppressWarnings("unchecked")
        Map<String, Schema> props = requestBody.getContent()
                .get("application/json")
                .getSchema()
                .getProperties();

        assertThat(props).isNotNull();

        // cud: STRING → type=string con maxLength
        assertThat(props).containsKey("cud");
        Schema<?> cudSchema = props.get("cud");
        assertThat(cudSchema.getType()).isEqualTo("string");
        assertThat(cudSchema.getMaxLength()).isEqualTo(50);

        // id_externo_caso: INTEGER → type=integer, format=int64
        assertThat(props).containsKey("id_externo_caso");
        Schema<?> idSchema = props.get("id_externo_caso");
        assertThat(idSchema.getType()).isEqualTo("integer");
        assertThat(idSchema.getFormat()).isEqualTo("int64");

        // tags: ARRAY → type=array
        assertThat(props).containsKey("tags");
        Schema<?> tagsSchema = props.get("tags");
        assertThat(tagsSchema.getType()).isEqualTo("array");
    }

    @Test
    @DisplayName("Lista 'required' del schema debe contener exactamente los campos obligatorios")
    void listaRequiredDebeContenerSoloCamposObligatorios() {
        @SuppressWarnings("unchecked")
        List<String> required = openApi.getPaths()
                .get("/api/inbound/CASO_PENAL/v1")
                .getPost()
                .getRequestBody()
                .getContent()
                .get("application/json")
                .getSchema()
                .getRequired();

        assertThat(required)
                .as("Solo 'cud' e 'id_externo_caso' son requeridos en el contrato de prueba")
                .containsExactlyInAnyOrder("cud", "id_externo_caso");
    }

    // ─── Parámetros ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Operacion POST debe tener X-Idempotency-Key (required) y X-Correlation-ID (opcional)")
    void postDebeDocumentarCabeceras() {
        List<Parameter> params = openApi.getPaths()
                .get("/api/inbound/CASO_PENAL/v1")
                .getPost()
                .getParameters();

        assertThat(params).isNotNull();

        Parameter idempotencyKey = params.stream()
                .filter(p -> "X-Idempotency-Key".equals(p.getName()))
                .findFirst()
                .orElse(null);
        assertThat(idempotencyKey)
                .as("X-Idempotency-Key debe estar documentada como parametro header")
                .isNotNull();
        assertThat(idempotencyKey.getIn()).isEqualTo("header");
        assertThat(idempotencyKey.getRequired()).isTrue();

        Parameter correlationId = params.stream()
                .filter(p -> "X-Correlation-ID".equals(p.getName()))
                .findFirst()
                .orElse(null);
        assertThat(correlationId)
                .as("X-Correlation-ID debe estar documentada como parametro header")
                .isNotNull();
        assertThat(correlationId.getIn()).isEqualTo("header");
        assertThat(correlationId.getRequired()).as("X-Correlation-ID es opcional").isFalse();
    }

    @Test
    @DisplayName("Operacion PATCH debe tener ademas parametro {id} en path")
    void patchDebeDocumentarParametroIdEnPath() {
        List<Parameter> params = openApi.getPaths()
                .get("/api/inbound/CASO_PENAL/v1/{id}")
                .getPatch()
                .getParameters();

        assertThat(params).isNotNull();

        Parameter idParam = params.stream()
                .filter(p -> "id".equals(p.getName()))
                .findFirst()
                .orElse(null);
        assertThat(idParam)
                .as("El parametro {id} de path debe estar documentado")
                .isNotNull();
        assertThat(idParam.getIn()).isEqualTo("path");
        assertThat(idParam.getRequired()).isTrue();
        assertThat(idParam.getSchema().getType()).isEqualTo("integer");
        assertThat(idParam.getSchema().getFormat()).isEqualTo("int64");
    }

    // ─── Tags ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("La operacion POST debe tener el tag del producto base")
    void postDebeTenerTagDelProducto() {
        List<String> tags = openApi.getPaths()
                .get("/api/inbound/CASO_PENAL/v1")
                .getPost()
                .getTags();

        assertThat(tags).containsExactly("CASO_PENAL");
    }

    @Test
    @DisplayName("La operacion PATCH debe tener el tag del producto base sin sufijo _EDITAR")
    void patchDebeTenerTagSinSufijoEditar() {
        List<String> tags = openApi.getPaths()
                .get("/api/inbound/CASO_PENAL/v1/{id}")
                .getPatch()
                .getTags();

        assertThat(tags)
                .as("El tag del PATCH debe ser 'CASO_PENAL', no 'CASO_PENAL_EDITAR'")
                .containsExactly("CASO_PENAL");
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Contrato POST mínimo de prueba: 2 requeridos + 1 opcional.
     */
    private ContractDefinition contratoCasoPenalPost() {
        List<FieldRule> campos = List.of(
                new FieldRule("cud",            FieldType.STRING,  true,  50,   null),
                new FieldRule("id_externo_caso", FieldType.INTEGER, true,  null, null),
                new FieldRule("tags",            FieldType.ARRAY,   false, null, null)
        );
        return new ContractDefinition("CASO_PENAL", "v1", campos);
    }

    /**
     * Contrato PATCH con {@code resourceIdField=id_pol_caso}.
     */
    private ContractDefinition contratoCasoPenalEditar() {
        List<FieldRule> campos = List.of(
                new FieldRule("id_tipo_denuncia", FieldType.INTEGER, true,  null, null),
                new FieldRule("zona",             FieldType.STRING,  false, 255,  null),
                new FieldRule("fecha_caso",       FieldType.DATETIME, false, null, "iso8601")
        );
        return new ContractDefinition("CASO_PENAL_EDITAR", "v1", campos, "id_pol_caso");
    }

    /**
     * Contrato de catálogo de solo lectura ({@code httpMethod=GET}), sin campos —
     * los catálogos declarados así no tienen payload de entrada.
     */
    private ContractDefinition contratoCatalogoGet() {
        return new ContractDefinition("CATALOGO_UNIDADES", "v1", List.of(), null, "GET");
    }

    // ─── Path GET (catálogo de solo lectura) ───────────────────────────────────

    @Test
    @DisplayName("Debe generar path GET para un contrato de solo lectura")
    void debeGenerarPathGetParaContratoDeSoloLectura() {
        assertThat(openApi.getPaths())
                .containsKey("/api/inbound/CATALOGO_UNIDADES/v1");

        PathItem item = openApi.getPaths().get("/api/inbound/CATALOGO_UNIDADES/v1");
        assertThat(item.getGet())
                .as("La operacion GET debe existir en el path del catálogo")
                .isNotNull();
        assertThat(item.getPost())
                .as("No debe existir operacion POST para un contrato de solo lectura")
                .isNull();
        assertThat(item.getPatch())
                .as("No debe existir operacion PATCH para un contrato de solo lectura")
                .isNull();
    }

    @Test
    @DisplayName("La operacion GET no debe tener requestBody")
    void operacionGetNoDebeTenerRequestBody() {
        var getOp = openApi.getPaths()
                .get("/api/inbound/CATALOGO_UNIDADES/v1")
                .getGet();

        assertThat(getOp.getRequestBody())
                .as("Un catálogo de solo lectura no recibe payload de entrada")
                .isNull();
    }

    @Test
    @DisplayName("La operacion GET no debe documentar X-Idempotency-Key, pero sí X-Correlation-ID")
    void operacionGetNoDebeDocumentarIdempotencyKey() {
        List<Parameter> params = openApi.getPaths()
                .get("/api/inbound/CATALOGO_UNIDADES/v1")
                .getGet()
                .getParameters();

        assertThat(params)
                .extracting(Parameter::getName)
                .as("GET es idempotente por naturaleza — no exige X-Idempotency-Key")
                .doesNotContain("X-Idempotency-Key");

        Parameter correlationId = params.stream()
                .filter(p -> "X-Correlation-ID".equals(p.getName()))
                .findFirst()
                .orElse(null);
        assertThat(correlationId).isNotNull();
        assertThat(correlationId.getRequired()).isFalse();
    }

    @Test
    @DisplayName("Responses de GET deben incluir 200, sin 400 ni 409")
    void responsesDeGetDebenIncluir200SinValidacionNiIdempotencia() {
        var getOp = openApi.getPaths()
                .get("/api/inbound/CATALOGO_UNIDADES/v1")
                .getGet();

        assertThat(getOp.getResponses())
                .containsKey("200")
                .containsKey("403")
                .containsKey("500")
                .doesNotContainKey("400")
                .doesNotContainKey("409")
                .doesNotContainKey("201");
    }

    @Test
    @DisplayName("La operacion GET debe tener el tag del producto")
    void getDebeTenerTagDelProducto() {
        List<String> tags = openApi.getPaths()
                .get("/api/inbound/CATALOGO_UNIDADES/v1")
                .getGet()
                .getTags();

        assertThat(tags).containsExactly("CATALOGO_UNIDADES");
    }
}
