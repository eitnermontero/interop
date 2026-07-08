package bo.com.sintesis.hub.base.hub.inbound;

import bo.com.sintesis.hub.audit.hash.PayloadHasher;
import bo.com.sintesis.hub.audit.hub.HubAuditCommand;
import bo.com.sintesis.hub.audit.hub.HubAuditService;
import bo.com.sintesis.hub.base.hub.HubAuditInterceptor;
import bo.com.sintesis.hub.base.hub.HubWebMvcConfig;
import bo.com.sintesis.hub.base.hub.inbound.config.HubInteropProperties;
import bo.com.sintesis.hub.base.hub.inbound.config.InboundAutoConfiguration;
import bo.com.sintesis.hub.base.hub.inbound.contract.ContractRegistry;
import bo.com.sintesis.hub.base.hub.inbound.contract.ContractValidator;
import bo.com.sintesis.hub.base.web.rest.errors.GlobalExceptionHandler;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test de integración end-to-end del verbo {@code GET} del motor inbound (ADR-0006/0007):
 * {@link DispatcherController} → contrato de solo lectura → {@link ForwardingGateway} →
 * {@link bo.com.sintesis.hub.base.hub.inbound.HttpForwardingAdapter} (sin body) → backend
 * simulado con WireMock → {@code ApiResponse} + auditoría.
 *
 * <p>Slice de contexto explícito, sin base de datos: ninguna clase del motor inbound
 * depende de JPA/DataSource, así que este test evita el problema de Testcontainers/Docker
 * que deshabilita {@code CasoInboundIT} en este entorno (docker-java API 1.32 vs Docker 29.x
 * — ver ese test). {@link HubAuditService} y {@link PayloadHasher} se mockean (patrón
 * {@code EfxRateClientIT}), así que la auditoría se verifica por invocación, no por fila en BD.
 */
@SpringBootTest(
        classes = CatalogoInboundGetIT.TestSliceConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK
)
class CatalogoInboundGetIT {

    // ─── WireMock — inicialización estática para @DynamicPropertySource ───────

    static final WireMockServer WIRE_MOCK;

    static {
        WIRE_MOCK = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        WIRE_MOCK.start();
        com.github.tomakehurst.wiremock.client.WireMock.configureFor("localhost", WIRE_MOCK.port());
    }

    @AfterEach
    void limpiarStubs() {
        WIRE_MOCK.resetAll();
    }

    // ─── Propiedades dinámicas: catálogo GET de prueba ─────────────────────────

    private static final String CATALOGO_TARGET_PATH = "/v1/external/fiscalia/catalogos/unidades";

    @DynamicPropertySource
    static void configurarPropiedades(DynamicPropertyRegistry registry) {
        // Evita que Spring intente resolver Consul/Vault (no corren en este slice de test).
        registry.add("spring.config.import", () -> "");

        registry.add("hub.connectors.wiremock-catalogo.base-url",
                () -> "http://localhost:" + WIRE_MOCK.port());
        registry.add("hub.connectors.wiremock-catalogo.timeout-ms", () -> "3000");

        // Producto de catálogo de solo lectura — sin fields, sin resource-id-field,
        // target-path estático (estos catálogos no reciben parámetros).
        registry.add("hub.apis.catalogo-test-v1.product", () -> "CATALOGO_TEST");
        registry.add("hub.apis.catalogo-test-v1.version", () -> "v1");
        registry.add("hub.apis.catalogo-test-v1.method", () -> "GET");
        registry.add("hub.apis.catalogo-test-v1.connector", () -> "wiremock-catalogo");
        registry.add("hub.apis.catalogo-test-v1.target-path", () -> CATALOGO_TARGET_PATH);
    }

    // ─── Slice de contexto ────────────────────────────────────────────────────

    /**
     * Contexto mínimo del motor inbound: sin JPA/DataSource/Liquibase/Vault/Consul/Redis/
     * Security — solo lo necesario para ejercitar el pipeline GET completo vía MockMvc.
     */
    @Configuration
    @ImportAutoConfiguration({
            PropertyPlaceholderAutoConfiguration.class,
            JacksonAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class,
            ErrorMvcAutoConfiguration.class
    })
    @Import({
            ContractRegistry.class,
            ContractValidator.class,
            ForwardingGateway.class,
            DispatcherController.class,
            InboundAutoConfiguration.class,
            HubAuditInterceptor.class,
            HubWebMvcConfig.class,
            GlobalExceptionHandler.class
    })
    static class TestSliceConfig {

        @Bean
        HubAuditService mockHubAuditService() {
            return mock(HubAuditService.class);
        }

        @Bean
        PayloadHasher mockPayloadHasher() {
            PayloadHasher hasher = mock(PayloadHasher.class);
            when(hasher.hash(anyString())).thenReturn("a".repeat(64));
            when(hasher.hash(any(byte[].class))).thenReturn("a".repeat(64));
            when(hasher.hashRaw(any(byte[].class))).thenReturn("b".repeat(64));
            return hasher;
        }
    }

    // ─── Beans inyectados ─────────────────────────────────────────────────────

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private HubAuditService hubAuditService;

    @Autowired
    private HubInteropProperties hubInteropProperties;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        reset(hubAuditService);
    }

    private static final String CATALOGO_RESPONSE = """
            {
              "unidades": [
                { "id": 1, "nombre": "FELCC La Paz" },
                { "id": 2, "nombre": "FELCC Santa Cruz" }
              ]
            }
            """;

    // ─── Tests ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("El contrato CATALOGO_TEST/v1 se registra como GET (solo lectura)")
    void elContratoSeRegistraDeclaradoComoGet() {
        assertThat(hubInteropProperties.getApis().get("catalogo-test-v1").getMethod())
                .isEqualTo("GET");
    }

    @Test
    @DisplayName("GET /api/inbound/CATALOGO_TEST/v1 reenvía sin body y envuelve la respuesta en ApiResponse")
    void get_reenviaSinBodyYEnvuelveApiResponse() throws Exception {
        WIRE_MOCK.stubFor(get(urlEqualTo(CATALOGO_TARGET_PATH))
                .withRequestBody(absent())
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(CATALOGO_RESPONSE)));

        String partnerId = "partner-catalogo-" + UUID.randomUUID();
        String correlationId = UUID.randomUUID().toString();

        mockMvc.perform(MockMvcRequestBuilders.get("/api/inbound/CATALOGO_TEST/v1")
                        .header("X-Partner-Id", partnerId)
                        .header("X-Correlation-ID", correlationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.correlationId").value(correlationId))
                .andExpect(jsonPath("$.data.unidades[0].nombre").value("FELCC La Paz"));

        // El backend recibió exactamente una llamada GET, sin body.
        WIRE_MOCK.verify(1, getRequestedFor(urlEqualTo(CATALOGO_TARGET_PATH)));

        // Auditoría: se registró la transacción con httpMethod=GET, product correcto
        // y sin idempotencyKey (GET no la exige).
        ArgumentCaptor<HubAuditCommand> captor = ArgumentCaptor.forClass(HubAuditCommand.class);
        verify(hubAuditService).record(captor.capture());
        HubAuditCommand cmd = captor.getValue();
        assertThat(cmd.httpMethod()).isEqualTo("GET");
        assertThat(cmd.product()).isEqualTo("CATALOGO_TEST");
        assertThat(cmd.partnerId()).isEqualTo(partnerId);
        assertThat(cmd.httpStatus()).isEqualTo(200);
        assertThat(cmd.idempotencyKey()).as("GET no debe propagar idempotencyKey").isNull();
        assertThat(cmd.correlationId()).isEqualTo(correlationId);
    }

    @Test
    @DisplayName("GET contra un backend que responde un array JSON (caso real Fiscalía) — "
            + "el ApiResponse.data() es ese array, no un 502")
    void get_backendResponeArrayJson_envuelveApiResponseSinError() throws Exception {
        // Los catálogos reales del backend de Fiscalía responden un array JSON en el
        // body (ej. [] o [{...}]), no un objeto — antes del fix, HttpForwardingAdapter
        // forzaba toEntity(Map.class) y esto producía 502 (UPSTREAM_ERROR) por fallo
        // de deserialización de Jackson.
        WIRE_MOCK.stubFor(get(urlEqualTo(CATALOGO_TARGET_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"id\":1,\"nombre\":\"FELCC La Paz\"},"
                                + "{\"id\":2,\"nombre\":\"FELCC Santa Cruz\"}]")));

        mockMvc.perform(MockMvcRequestBuilders.get("/api/inbound/CATALOGO_TEST/v1")
                        .header("X-Partner-Id", "partner-array-response"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].nombre").value("FELCC La Paz"))
                .andExpect(jsonPath("$.data[1].nombre").value("FELCC Santa Cruz"));
    }

    @Test
    @DisplayName("GET contra un backend que responde un array JSON vacío ([]) — 200 con data() vacío")
    void get_backendResponeArrayJsonVacio_respondeOkConDataVacio() throws Exception {
        WIRE_MOCK.stubFor(get(urlEqualTo(CATALOGO_TARGET_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[]")));

        mockMvc.perform(MockMvcRequestBuilders.get("/api/inbound/CATALOGO_TEST/v1")
                        .header("X-Partner-Id", "partner-empty-array-response"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("X-Idempotency-Key enviada en un GET se ignora — no se propaga a la auditoría ni genera 409")
    void get_ignoraIdempotencyKeyEnviada() throws Exception {
        WIRE_MOCK.stubFor(get(urlEqualTo(CATALOGO_TARGET_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(CATALOGO_RESPONSE)));

        // Misma clave en dos llamadas consecutivas: si el motor la exigiera para GET,
        // la segunda llamada fallaría (o al menos se propagaría a la auditoría).
        String claveReutilizada = "clave-reutilizada-" + UUID.randomUUID();

        mockMvc.perform(MockMvcRequestBuilders.get("/api/inbound/CATALOGO_TEST/v1")
                        .header("X-Partner-Id", "partner-idem-get")
                        .header("X-Idempotency-Key", claveReutilizada))
                .andExpect(status().isOk());

        mockMvc.perform(MockMvcRequestBuilders.get("/api/inbound/CATALOGO_TEST/v1")
                        .header("X-Partner-Id", "partner-idem-get")
                        .header("X-Idempotency-Key", claveReutilizada))
                .andExpect(status().isOk());

        ArgumentCaptor<HubAuditCommand> captor = ArgumentCaptor.forClass(HubAuditCommand.class);
        verify(hubAuditService, org.mockito.Mockito.times(2)).record(captor.capture());
        assertThat(captor.getAllValues())
                .as("Ninguna de las dos invocaciones debe llevar idempotencyKey")
                .allSatisfy(cmd -> assertThat(cmd.idempotencyKey()).isNull());
    }

    @Test
    @DisplayName("GET sobre un producto declarado como POST responde 403 sin invocar el backend")
    void get_productoNoDeclaradoComoGet_respondeForbidden() throws Exception {
        // CASO_PENAL/v1 está declarado en application.yml como POST — el verbo GET
        // no debe poder disparar ese contrato de escritura con un payload vacío.
        mockMvc.perform(MockMvcRequestBuilders.get("/api/inbound/CASO_PENAL/v1")
                        .header("X-Partner-Id", "partner-get-wrong-verb"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("PRODUCT_NOT_AUTHORIZED"));

        WIRE_MOCK.verify(0, getRequestedFor(urlEqualTo(CATALOGO_TARGET_PATH)));
    }

    @Test
    @DisplayName("GET sobre un producto inexistente responde 403")
    void get_productoInexistente_respondeForbidden() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/inbound/NO_EXISTE/v1")
                        .header("X-Partner-Id", "partner-get-unknown"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PRODUCT_NOT_AUTHORIZED"));
    }

    @Test
    @DisplayName("POST sobre un producto declarado como GET responde 403 sin invocar el backend "
            + "(simétrico al caso GET sobre POST — evita honrar X-Idempotency-Key en una lectura pura)")
    void post_productoDeclaradoComoGet_respondeForbidden() throws Exception {
        // CATALOGO_TEST/v1 está declarado como GET (catálogo de solo lectura). Sin la
        // verificación simétrica en post(), el body vacío pasaría la validación trivialmente
        // (el contrato no tiene fields) y el POST terminaría honrando X-Idempotency-Key y
        // reenviando como GET vía HttpForwardingAdapter — inconsistente con el diseño.
        mockMvc.perform(MockMvcRequestBuilders.post("/api/inbound/CATALOGO_TEST/v1")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header("X-Partner-Id", "partner-post-wrong-verb")
                        .header("X-Idempotency-Key", "clave-post-sobre-get"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("PRODUCT_NOT_AUTHORIZED"));

        WIRE_MOCK.verify(0, getRequestedFor(urlEqualTo(CATALOGO_TARGET_PATH)));

        // Tampoco debe haberse registrado auditoría con idempotencyKey para esta operación
        // rechazada — el rechazo ocurre antes de tocar el pipeline de forwarding/negocio.
        ArgumentCaptor<HubAuditCommand> captor = ArgumentCaptor.forClass(HubAuditCommand.class);
        verify(hubAuditService).record(captor.capture());
        assertThat(captor.getValue().httpStatus()).isEqualTo(403);
    }
}
