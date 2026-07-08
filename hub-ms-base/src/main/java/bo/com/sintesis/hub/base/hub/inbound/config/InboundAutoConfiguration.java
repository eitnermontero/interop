package bo.com.sintesis.hub.base.hub.inbound.config;

import bo.com.sintesis.hub.base.hub.inbound.HttpForwardingAdapter;
import bo.com.sintesis.hub.base.hub.inbound.contract.ContractDefinition;
import bo.com.sintesis.hub.base.hub.inbound.contract.ContractRegistry;
import bo.com.sintesis.hub.base.hub.inbound.contract.FieldRule;
import bo.com.sintesis.hub.base.hub.inbound.port.InboundPort;
import bo.com.sintesis.hub.base.hub.inbound.stub.StubInboundAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;

/**
 * Configuración del motor inbound del hub (ADR-0006 + ADR-0007).
 *
 * <p>Los contratos y el ruteo ya <b>no</b> se declaran en código: se cargan
 * desde el plano de control declarativo {@code hub.apis} / {@code hub.connectors}
 * ({@link HubInteropProperties}) al arrancar. Agregar una API nueva = un bloque
 * YAML — cero clases nuevas.
 *
 * <p><b>Fail-fast</b>: si la configuración declara una API inválida (sin destino,
 * conector inexistente, campos malformados), el contexto NO arranca. En un hub
 * es preferible no levantar a levantar con un catálogo inconsistente.
 *
 * <p>Los beans de adaptadores nunca usan {@code @Component} — instanciación
 * explícita, patrón {@code EfxRateAutoConfiguration}.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(HubInteropProperties.class)
public class InboundAutoConfiguration {

    private final ContractRegistry contractRegistry;
    private final HubInteropProperties properties;

    /** Valida el plano de control y registra todos los contratos declarados. */
    @PostConstruct
    public void registrarContratos() {
        Map<String, HubInteropProperties.ApiProps> apis = properties.getApis();
        if (apis.isEmpty()) {
            log.warn("hub.apis está vacío — el motor inbound no atenderá ningún producto");
            return;
        }
        log.info("Registrando {} contrato(s) inbound desde configuración...", apis.size());
        apis.forEach(this::registrar);
        log.info("Contratos inbound registrados: {}", apis.values().stream()
                .map(a -> a.getProduct() + "/" + a.getVersion()).toList());
    }

    private void registrar(String nombre, HubInteropProperties.ApiProps api) {
        // ── Validación fail-fast del bloque declarado ─────────────────────────
        if (!StringUtils.hasText(api.getProduct()) || !StringUtils.hasText(api.getVersion())) {
            throw new IllegalStateException(
                    "hub.apis." + nombre + ": 'product' y 'version' son obligatorios");
        }
        boolean tieneConnector = StringUtils.hasText(api.getConnector());
        boolean tieneBean      = StringUtils.hasText(api.getAdapterBean());
        if (tieneConnector == tieneBean) {
            throw new IllegalStateException(
                    "hub.apis." + nombre + ": declarar exactamente uno de 'connector' o 'adapter-bean'");
        }
        if (tieneConnector) {
            if (!properties.getConnectors().containsKey(api.getConnector())) {
                throw new IllegalStateException(
                        "hub.apis." + nombre + ": el conector '" + api.getConnector()
                        + "' no existe en hub.connectors");
            }
            if (!StringUtils.hasText(api.getTargetPath())) {
                throw new IllegalStateException(
                        "hub.apis." + nombre + ": 'target-path' es obligatorio cuando se usa 'connector'");
            }
        }
        api.getFields().forEach(f -> {
            if (!StringUtils.hasText(f.getName()) || f.getType() == null) {
                throw new IllegalStateException(
                        "hub.apis." + nombre + ": cada field requiere 'name' y 'type'");
            }
        });

        // ── Registro del contrato ─────────────────────────────────────────────
        List<FieldRule> reglas = api.getFields().stream()
                .map(f -> new FieldRule(f.getName(), f.getType(), f.isRequired(),
                        f.getMaxLength(), f.getFormat()))
                .toList();
        String httpMethod = StringUtils.hasText(api.getMethod()) ? api.getMethod().toUpperCase() : "POST";
        contractRegistry.register(new ContractDefinition(
                api.getProduct(), api.getVersion(), reglas, api.getResourceIdField(), httpMethod));
    }

    /** Adaptador HTTP genérico — el InboundPort de propósito general (ADR-0007). */
    @Bean
    public HttpForwardingAdapter httpForwardingAdapter() {
        return new HttpForwardingAdapter();
    }

    /**
     * Customizer de OpenAPI que genera los paths inbound dinámicamente desde el
     * {@link ContractRegistry} (ADR-0007 fase 6).
     *
     * <p>Cada contrato registrado produce un path concreto en el spec; los paths
     * genéricos de {@link bo.com.sintesis.hub.base.hub.inbound.DispatcherController}
     * ({@code /{product}/{version}}) quedan ocultos con {@code @Hidden} para evitar
     * duplicados.
     */
    @Bean
    public OpenApiCustomizer inboundContractOpenApiCustomizer() {
        return new InboundContractOpenApiCustomizer(contractRegistry);
    }

    /**
     * Adaptador stub para desarrollo local. Solo se instancia con
     * {@code hub.inbound.stub-mode=true}; las APIs lo referencian por nombre
     * con {@code adapter-bean: stubInboundAdapter}. En producción este bean no
     * existe y una API que lo referencie responde 503 de forma controlada.
     */
    @Bean("stubInboundAdapter")
    @ConditionalOnProperty(name = "hub.inbound.stub-mode", havingValue = "true")
    public InboundPort stubInboundAdapter() {
        log.warn("STUB MODE habilitado — no usar en produccion");
        return new StubInboundAdapter();
    }
}
