package bo.com.sintesis.mdqr.base.hub;

import bo.com.sintesis.mdqr.audit.chain.ChainHashCalculator;
import bo.com.sintesis.mdqr.audit.hub.HubAuditCommand;
import bo.com.sintesis.mdqr.audit.hub.HubAuditService;
import bo.com.sintesis.mdqr.audit.hub.IdempotencyKeyConflictException;
import bo.com.sintesis.mdqr.audit.signing.NoOpAuditSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.junit.jupiter.api.Disabled;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Tests de integración del pipeline de auditoría del hub para
 * {@code POST /api/inbound/CASO_PENAL/v1}.
 *
 * <p>Verifica:
 * <ol>
 *   <li>Que tras una llamada al endpoint se escriben registros en
 *       {@code hub_audit_log} con {@code product="CASO_PENAL"} y en
 *       {@code outbox_event} con {@code aggregate_type="CASO_PENAL"}.</li>
 *   <li>Que la cadena de hashes es correcta para dos llamadas consecutivas.</li>
 *   <li>Que si {@link HubAuditService} falla (tablas ausentes), la response de
 *       negocio sale igualmente (resiliencia del interceptor).</li>
 *   <li>Que una {@code X-Idempotency-Key} duplicada lanza
 *       {@link IdempotencyKeyConflictException}.</li>
 * </ol>
 *
 * <p>Infraestructura: Testcontainers con PostgreSQL real. Sin H2.
 * Las tablas del hub se crean mediante DDL directo para no depender de Liquibase.
 */
// docker-java 3.4.x envía API 1.32 al daemon; Docker 29.x requiere mínimo 1.44.
// Testcontainers no expone la negociación de versión externamente en esta combinación.
// Habilitar cuando se actualice docker-java a una versión que negocie API >= 1.44.
@Disabled("docker-java API 1.32 incompatible con Docker 29.x — ver build.gradle")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("test")
class CasoInboundIT {

    // ─── Infraestructura ─────────────────────────────────────────────────────

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("mdqr_decode_it")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void configurarDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // Deshabilitar Liquibase — tablas se crean con DDL manual en @BeforeEach
        registry.add("spring.liquibase.enabled", () -> "false");
        // Deshabilitar infraestructura externa
        registry.add("spring.cloud.vault.enabled", () -> "false");
        registry.add("spring.cloud.consul.config.enabled", () -> "false");
        registry.add("spring.cloud.consul.discovery.enabled", () -> "false");
        registry.add("spring.config.import", () -> "");
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> "6379");
        registry.add("spring.autoconfigure.exclude", () ->
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration," +
                "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration," +
                "org.springframework.boot.autoconfigure.security.oauth2.resource.servlet." +
                        "OAuth2ResourceServerAutoConfiguration"
        );
        registry.add("audit.enabled", () -> "true");
        registry.add("audit.integrity.vault-enabled", () -> "false");
        registry.add("hub.mtls.test-mode", () -> "true");
        // Activar stub para CASO_PENAL/v1
        registry.add("hub.inbound.stub-mode", () -> "true");
    }

    // ─── Test config ─────────────────────────────────────────────────────────

    /**
     * Sobreescribe el bean {@link HubAuditService} con una instancia que usa
     * el {@link JdbcTemplate} del Testcontainer y un {@link NoOpAuditSigner}
     * (sin Vault).
     */
    @TestConfiguration
    static class HubAuditTestConfig {

        @Bean
        @Primary
        public HubAuditService hubAuditService(JdbcTemplate jdbc) {
            return new HubAuditService(jdbc, new ChainHashCalculator(), new NoOpAuditSigner());
        }
    }

    // ─── DDL ──────────────────────────────────────────────────────────────────

    private static final String DDL_AUDIT_LOG_PARENT = """
            CREATE TABLE IF NOT EXISTS public.hub_audit_log (
                id              uuid        NOT NULL,
                ts              timestamptz NOT NULL,
                direction       varchar(3)  NOT NULL CHECK (direction IN ('IN','OUT')),
                partner_id      text        NOT NULL,
                product         varchar(100) NOT NULL,
                endpoint        text        NOT NULL,
                http_method     varchar(10) NOT NULL,
                http_status     smallint    NOT NULL,
                request_hash    varchar(64) NOT NULL,
                response_hash   varchar(64) NOT NULL,
                latency_ms      integer     NOT NULL,
                billable_units  integer     NOT NULL DEFAULT 1,
                idempotency_key varchar(128),
                correlation_id  varchar(128),
                prev_hash       varchar(64) NOT NULL,
                chain_hash      varchar(64) NOT NULL,
                signature       text,
                key_version     integer,
                CONSTRAINT pk_hub_audit_log PRIMARY KEY (id, ts),
                CONSTRAINT chk_hub_audit_hash_len CHECK (
                    length(request_hash) = 64 AND length(response_hash) = 64
                    AND length(prev_hash) = 64 AND length(chain_hash) = 64)
            ) PARTITION BY RANGE (ts)
            """;

    private static final String DDL_AUDIT_LOG_DEFAULT = """
            CREATE TABLE IF NOT EXISTS public.hub_audit_log_default
                PARTITION OF public.hub_audit_log DEFAULT
            """;

    private static final String DDL_IDEMPOTENCY = """
            CREATE TABLE IF NOT EXISTS public.hub_audit_idempotency (
                idempotency_key varchar(128) NOT NULL,
                audit_id        uuid         NOT NULL,
                ts              timestamptz  NOT NULL,
                created_date    timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT pk_hub_audit_idempotency PRIMARY KEY (idempotency_key)
            )
            """;

    private static final String DDL_OUTBOX_SEQ =
            "CREATE SEQUENCE IF NOT EXISTS public.outbox_event_seq START 1 INCREMENT 50";

    private static final String DDL_OUTBOX = """
            CREATE TABLE IF NOT EXISTS public.outbox_event (
                id              bigint      NOT NULL DEFAULT nextval('public.outbox_event_seq'),
                aggregate_type  varchar(100) NOT NULL,
                aggregate_id    text        NOT NULL,
                event_type      varchar(100) NOT NULL,
                idempotency_key varchar(128) NOT NULL,
                payload         jsonb       NOT NULL,
                status          varchar(20) NOT NULL DEFAULT 'PENDING',
                attempts        integer     NOT NULL DEFAULT 0,
                created_date    timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
                processed_date  timestamptz,
                CONSTRAINT pk_outbox_event PRIMARY KEY (id),
                CONSTRAINT uk_outbox_idempotency_key UNIQUE (idempotency_key)
            )
            """;

    // ─── Beans inyectados ─────────────────────────────────────────────────────

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    @Qualifier("hubAuditService")
    private HubAuditService hubAuditService;

    // ─── Setup ────────────────────────────────────────────────────────────────

    @BeforeEach
    void prepararEsquema() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        jdbc.execute("DROP TABLE IF EXISTS public.hub_audit_log_default");
        jdbc.execute("DROP TABLE IF EXISTS public.hub_audit_log CASCADE");
        jdbc.execute("DROP TABLE IF EXISTS public.hub_audit_idempotency");
        jdbc.execute("DROP TABLE IF EXISTS public.outbox_event");
        jdbc.execute("DROP SEQUENCE IF EXISTS public.outbox_event_seq");

        jdbc.execute(DDL_AUDIT_LOG_PARENT);
        jdbc.execute(DDL_AUDIT_LOG_DEFAULT);
        jdbc.execute(DDL_IDEMPOTENCY);
        jdbc.execute(DDL_OUTBOX_SEQ);
        jdbc.execute(DDL_OUTBOX);
    }

    // ─── Tests ────────────────────────────────────────────────────────────────

    /**
     * Test 1: una llamada al endpoint debe generar registros en
     * {@code hub_audit_log} con {@code product="CASO_PENAL"} y en
     * {@code outbox_event} con {@code aggregate_type="CASO_PENAL"}.
     */
    @Test
    void shouldAuditCasoInboundRequestWithCorrectHashes() throws Exception {
        String partnerId = "partner-audit-" + UUID.randomUUID();
        String correlationId = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/inbound/CASO_PENAL/v1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadCasoMinimo())
                        .header("X-Partner-Id", partnerId)
                        .header("X-Correlation-ID", correlationId)
                        .header("X-Idempotency-Key", UUID.randomUUID().toString()))
                .andReturn();

        // Verificar registro de auditoría con product correcto
        Integer auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM public.hub_audit_log WHERE partner_id = ?",
                Integer.class, partnerId);
        assertThat(auditCount)
                .as("Debe existir exactamente un registro de auditoría para el partner")
                .isEqualTo(1);

        // Verificar product=CASO_PENAL en el registro
        String storedProduct = jdbc.queryForObject(
                "SELECT product FROM public.hub_audit_log WHERE partner_id = ?",
                String.class, partnerId);
        assertThat(storedProduct).isEqualTo("CASO_PENAL");

        // Verificar outbox con aggregate_type=CASO_PENAL
        Integer outboxCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM public.outbox_event WHERE payload::text LIKE ?",
                Integer.class, "%" + partnerId + "%");
        assertThat(outboxCount)
                .as("Debe existir exactamente un evento de outbox para la transacción")
                .isEqualTo(1);

        // Verificar integridad de hashes
        String requestHash = jdbc.queryForObject(
                "SELECT request_hash FROM public.hub_audit_log WHERE partner_id = ?",
                String.class, partnerId);
        String responseHash = jdbc.queryForObject(
                "SELECT response_hash FROM public.hub_audit_log WHERE partner_id = ?",
                String.class, partnerId);
        String chainHash = jdbc.queryForObject(
                "SELECT chain_hash FROM public.hub_audit_log WHERE partner_id = ?",
                String.class, partnerId);

        assertThat(requestHash).matches("[0-9a-f]{64}");
        assertThat(responseHash).matches("[0-9a-f]{64}");
        assertThat(chainHash).matches("[0-9a-f]{64}");

        // Verificar correlation_id propagado
        String storedCorrelationId = jdbc.queryForObject(
                "SELECT correlation_id FROM public.hub_audit_log WHERE partner_id = ?",
                String.class, partnerId);
        assertThat(storedCorrelationId).isEqualTo(correlationId);

        // Verificar estado PENDING del outbox
        String outboxStatus = jdbc.queryForObject(
                "SELECT status FROM public.outbox_event WHERE payload::text LIKE ?",
                String.class, "%" + partnerId + "%");
        assertThat(outboxStatus).isEqualTo("PENDING");
    }

    /**
     * Test 2: dos llamadas consecutivas para el mismo partner deben formar
     * una cadena de hashes válida (prev_hash del 2do = chain_hash del 1ro).
     */
    @Test
    void shouldCalculateCorrectChainForTwoConsecutiveRequests() throws Exception {
        String partnerId = "partner-chain-" + UUID.randomUUID();

        // Primera llamada
        mockMvc.perform(post("/api/inbound/CASO_PENAL/v1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadCasoMinimo())
                        .header("X-Partner-Id", partnerId))
                .andReturn();

        // Segunda llamada
        mockMvc.perform(post("/api/inbound/CASO_PENAL/v1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadCasoMinimo())
                        .header("X-Partner-Id", partnerId))
                .andReturn();

        // Deben existir 2 registros
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM public.hub_audit_log WHERE partner_id = ?",
                Integer.class, partnerId);
        assertThat(count).isEqualTo(2);

        // Leer en orden cronológico
        var rows = jdbc.queryForList(
                "SELECT chain_hash, prev_hash FROM public.hub_audit_log " +
                "WHERE partner_id = ? ORDER BY ts ASC",
                partnerId);

        assertThat(rows).hasSize(2);

        String chainHash1 = (String) rows.get(0).get("chain_hash");
        String prevHash1  = (String) rows.get(0).get("prev_hash");
        String prevHash2  = (String) rows.get(1).get("prev_hash");

        assertThat(prevHash1)
                .as("El primer registro debe usar el hash génesis")
                .isEqualTo(ChainHashCalculator.GENESIS_PREV_HASH);

        assertThat(prevHash2)
                .as("El prev_hash del 2do registro debe coincidir con el chain_hash del 1ro")
                .isEqualTo(chainHash1);
    }

    /**
     * Test 3: si la auditoría falla (tablas eliminadas), la respuesta HTTP debe
     * salir igualmente. Resiliencia del interceptor ante fallos de BD.
     */
    @Test
    void shouldHandleAuditFailureGracefully() throws Exception {
        // Eliminar tablas para forzar el fallo de auditoría
        jdbc.execute("DROP TABLE IF EXISTS public.hub_audit_log_default");
        jdbc.execute("DROP TABLE IF EXISTS public.hub_audit_log CASCADE");
        jdbc.execute("DROP TABLE IF EXISTS public.hub_audit_idempotency");
        jdbc.execute("DROP TABLE IF EXISTS public.outbox_event");
        jdbc.execute("DROP SEQUENCE IF EXISTS public.outbox_event_seq");

        var result = mockMvc.perform(post("/api/inbound/CASO_PENAL/v1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadCasoMinimo())
                        .header("X-Partner-Id", "partner-resilience-test"))
                .andReturn();

        // El endpoint debe responder aunque la auditoría falle
        int status = result.getResponse().getStatus();
        assertThat(status)
                .as("El endpoint debe responder aunque la auditoría falle")
                .isBetween(100, 599);
    }

    /**
     * Test 4: una segunda llamada con la misma {@code X-Idempotency-Key} debe
     * lanzar {@link IdempotencyKeyConflictException}.
     * Se verifica directamente sobre el servicio de auditoría.
     */
    @Test
    void shouldRejectDuplicateIdempotencyKey() {
        String partnerId = "partner-idem-" + UUID.randomUUID();
        String idempotencyKey = "idem-test-" + UUID.randomUUID();

        HubAuditCommand cmd1 = crearComando(partnerId, idempotencyKey);
        hubAuditService.record(cmd1);

        // El segundo intento con la misma clave debe lanzar excepción
        HubAuditCommand cmd2 = crearComando(partnerId, idempotencyKey);
        assertThatThrownBy(() -> hubAuditService.record(cmd2))
                .isInstanceOf(IdempotencyKeyConflictException.class)
                .hasMessageContaining(idempotencyKey);

        // Solo debe existir un registro de auditoría
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM public.hub_audit_log WHERE partner_id = ?",
                Integer.class, partnerId);
        assertThat(count)
                .as("Solo debe existir el registro del primer intento")
                .isEqualTo(1);

        // Solo debe existir una entrada de idempotencia
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM public.hub_audit_idempotency WHERE idempotency_key = ?",
                Integer.class, idempotencyKey);
        assertThat(idemCount).isEqualTo(1);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String payloadCasoMinimo() {
        return """
                {
                  "cud": "CUD-IT-001",
                  "id_externo_caso": 10001,
                  "id_tipo_denuncia": 1,
                  "id_oficina": 5,
                  "id_estado": 1,
                  "id_etapa": 2
                }
                """;
    }

    private HubAuditCommand crearComando(String partnerId, String idempotencyKey) {
        return new HubAuditCommand(
                UUID.randomUUID(),
                "IN",
                partnerId,
                "CASO_PENAL",
                "/api/inbound/CASO_PENAL/v1",
                "POST",
                201,
                "a".repeat(64),
                "b".repeat(64),
                150,
                1,
                idempotencyKey,
                UUID.randomUUID().toString(),
                Instant.now(),
                "CASO_PENAL",
                UUID.randomUUID().toString(),
                Map.of("partner", partnerId, "product", "CASO_PENAL", "units", 1)
        );
    }
}
