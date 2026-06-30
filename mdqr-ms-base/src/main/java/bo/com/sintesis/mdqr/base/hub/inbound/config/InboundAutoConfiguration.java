package bo.com.sintesis.mdqr.base.hub.inbound.config;

import bo.com.sintesis.mdqr.base.hub.inbound.contract.ContractDefinition;
import bo.com.sintesis.mdqr.base.hub.inbound.contract.ContractRegistry;
import bo.com.sintesis.mdqr.base.hub.inbound.contract.FieldRule;
import bo.com.sintesis.mdqr.base.hub.inbound.contract.FieldType;
import bo.com.sintesis.mdqr.base.hub.inbound.port.InboundPort;
import bo.com.sintesis.mdqr.base.hub.inbound.stub.StubInboundAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.util.List;

/**
 * Configuración explícita del motor inbound del hub de interoperabilidad.
 *
 * <p>Registra los contratos canónicos en el {@link ContractRegistry} durante el
 * arranque ({@link PostConstruct}) y declara los beans de adaptadores inbound
 * de forma condicional.
 *
 * <p>Los beans de adaptadores <em>nunca</em> usan {@code @Component} para respetar
 * el patrón de instanciación explícita establecido por {@code EfxRateAutoConfiguration}.
 *
 * <h2>Contrato CASO_PENAL/v1</h2>
 * Endpoint canónico: {@code POST /partner/v1/casos}. Mapeo de campos según
 * homologación definitiva (ficha §3.1):
 *
 * <pre>
 * Campo                      | Tipo      | Req | maxLen | format
 * cud                        | STRING    |  S  |  50    |
 * id_externo_caso            | INTEGER   |  S  |        |
 * id_externo_caso_referencia | INTEGER   |  N  |        |
 * id_tipo_denuncia           | INTEGER   |  S  |        |
 * es_reservado               | BOOLEAN   |  N  |        |
 * id_oficina                 | INTEGER   |  S  |        |
 * id_municipio               | INTEGER   |  N  |        |
 * zona                       | STRING    |  N  | 255    |
 * direccion                  | STRING    |  N  |        |
 * latitud                    | STRING    |  N  |  30    |
 * longitud                   | STRING    |  N  |  30    |
 * referencia                 | STRING    |  N  |        |
 * relato                     | STRING    |  N  |        |
 * fecha_caso                 | DATETIME  |  N  |        | iso8601
 * fecha_fin                  | DATETIME  |  N  |        | iso8601
 * fecha_aproximada           | STRING    |  N  | 255    |
 * id_estado                  | INTEGER   |  S  |        |
 * id_etapa                   | INTEGER   |  S  |        |
 * denominacion_caso          | STRING    |  N  | 500    |
 * tags                       | ARRAY     |  N  |        |
 * </pre>
 *
 * <h2>Contrato CASO_PENAL_EDITAR/v1</h2>
 * Endpoint canónico: {@code POST /partner/v1/inbound/CASO_PENAL_EDITAR/v1}.
 * Corresponde a {@code PATCH {urlPOL}/caso/{polCasoId}} (ficha §3.2).
 * El path variable {@code polCasoId} se incluye en el body como {@code id_pol_caso}.
 *
 * <pre>
 * Campo                      | Tipo      | Req | maxLen | format
 * id_pol_caso                | INTEGER   |  S  |        |   — polCasoId de POL
 * id_tipo_denuncia           | INTEGER   |  S  |        |
 * id_externo_caso_referencia | INTEGER   |  N  |        |   — mpCasoPadreId
 * id_municipio               | INTEGER   |  N  |        |
 * zona                       | STRING    |  N  | 255    |
 * direccion                  | STRING    |  N  |        |
 * latitud                    | STRING    |  N  |  30    |
 * longitud                   | STRING    |  N  |  30    |
 * referencia                 | STRING    |  N  |        |
 * relato                     | STRING    |  N  |        |
 * fecha_caso                 | DATETIME  |  N  |        | iso8601  — hechoFechaHora
 * fecha_fin                  | DATETIME  |  N  |        | iso8601
 * fecha_aproximada           | STRING    |  N  | 255    |
 * denominacion_caso          | STRING    |  N  | 500    |
 * tags                       | ARRAY     |  N  |        |   — tagsId (ids de etiquetas)
 * </pre>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class InboundAutoConfiguration {

    private static final String PRODUCT_CASO_PENAL        = "CASO_PENAL";
    private static final String PRODUCT_CASO_PENAL_EDITAR = "CASO_PENAL_EDITAR";
    private static final String VERSION_V1                = "v1";

    private final ContractRegistry contractRegistry;

    /**
     * Registra todos los contratos canónicos inbound durante el arranque.
     * Si en el futuro se agregan nuevos productos, se añaden aquí.
     */
    @PostConstruct
    public void registrarContratos() {
        log.info("Registrando contratos inbound del hub...");
        contractRegistry.register(contratoCasoPenalV1());
        contractRegistry.register(contratoCasoPenalEditarV1());
        log.info("Contratos inbound registrados.");
    }

    /**
     * Adaptador stub para {@code CASO_PENAL/v1}.
     *
     * <p>Solo se instancia cuando {@code hub.inbound.stub-mode=true} (perfil local/dev).
     * En producción, este bean no existe y debe reemplazarse por el adaptador real
     * que llama al sistema policial (FELCN/MP).
     */
    @Bean("stubInboundAdapter")
    @ConditionalOnProperty(name = "hub.inbound.stub-mode", havingValue = "true")
    public InboundPort stubInboundAdapter() {
        log.warn("STUB MODE habilitado para todos los productos inbound — no usar en produccion");
        return new StubInboundAdapter();
    }

    // ─── Definición del contrato CASO_PENAL/v1 ────────────────────────────────

    private ContractDefinition contratoCasoPenalV1() {
        List<FieldRule> campos = List.of(
                // Campos requeridos
                new FieldRule("cud",                        FieldType.STRING,  true,  50,   null),
                new FieldRule("id_externo_caso",            FieldType.INTEGER, true,  null, null),
                new FieldRule("id_tipo_denuncia",           FieldType.INTEGER, true,  null, null),
                new FieldRule("id_oficina",                 FieldType.INTEGER, true,  null, null),
                new FieldRule("id_estado",                  FieldType.INTEGER, true,  null, null),
                new FieldRule("id_etapa",                   FieldType.INTEGER, true,  null, null),

                // Campos opcionales
                new FieldRule("id_externo_caso_referencia", FieldType.INTEGER, false, null, null),
                new FieldRule("es_reservado",               FieldType.BOOLEAN, false, null, null),
                new FieldRule("id_municipio",               FieldType.INTEGER, false, null, null),
                new FieldRule("zona",                       FieldType.STRING,  false, 255,  null),
                new FieldRule("direccion",                  FieldType.STRING,  false, null, null),
                new FieldRule("latitud",                    FieldType.STRING,  false, 30,   null),
                new FieldRule("longitud",                   FieldType.STRING,  false, 30,   null),
                new FieldRule("referencia",                 FieldType.STRING,  false, null, null),
                new FieldRule("relato",                     FieldType.STRING,  false, null, null),
                new FieldRule("fecha_caso",                 FieldType.DATETIME, false, null, "iso8601"),
                new FieldRule("fecha_fin",                  FieldType.DATETIME, false, null, "iso8601"),
                new FieldRule("fecha_aproximada",           FieldType.STRING,  false, 255,  null),
                new FieldRule("denominacion_caso",          FieldType.STRING,  false, 500,  null),
                new FieldRule("tags",                       FieldType.ARRAY,   false, null, null)
        );

        return new ContractDefinition(PRODUCT_CASO_PENAL, VERSION_V1, campos);
    }

    private ContractDefinition contratoCasoPenalEditarV1() {
        List<FieldRule> campos = List.of(
                // Campo requerido (id_pol_caso viene del path — inyectado por el dispatcher)
                new FieldRule("id_tipo_denuncia",            FieldType.INTEGER, true,  null, null),

                // Campos opcionales
                new FieldRule("id_externo_caso_referencia",  FieldType.INTEGER, false, null, null),
                new FieldRule("id_municipio",                FieldType.INTEGER, false, null, null),
                new FieldRule("zona",                        FieldType.STRING,  false, 255,  null),
                new FieldRule("direccion",                   FieldType.STRING,  false, null, null),
                new FieldRule("latitud",                     FieldType.STRING,  false, 30,   null),
                new FieldRule("longitud",                    FieldType.STRING,  false, 30,   null),
                new FieldRule("referencia",                  FieldType.STRING,  false, null, null),
                new FieldRule("relato",                      FieldType.STRING,  false, null, null),
                new FieldRule("fecha_caso",                  FieldType.DATETIME, false, null, "iso8601"),
                new FieldRule("fecha_fin",                   FieldType.DATETIME, false, null, "iso8601"),
                new FieldRule("fecha_aproximada",            FieldType.STRING,  false, 255,  null),
                new FieldRule("denominacion_caso",           FieldType.STRING,  false, 500,  null),
                new FieldRule("tags",                        FieldType.ARRAY,   false, null, null)
        );

        // resourceIdField: el dispatcher inyecta el {polCasoId} del path como "id_pol_caso"
        return new ContractDefinition(PRODUCT_CASO_PENAL_EDITAR, VERSION_V1, campos, "id_pol_caso");
    }
}
