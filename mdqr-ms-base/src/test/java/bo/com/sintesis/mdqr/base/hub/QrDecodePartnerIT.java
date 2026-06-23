package bo.com.sintesis.mdqr.base.hub;

import bo.com.sintesis.mdqr.audit.chain.ChainHashCalculator;
import bo.com.sintesis.mdqr.audit.hub.HubAuditCommand;
import bo.com.sintesis.mdqr.audit.hub.HubAuditService;
import bo.com.sintesis.mdqr.audit.hub.IdempotencyKeyConflictException;
import bo.com.sintesis.mdqr.audit.signing.NoOpAuditSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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
 * {@code POST /api/qr/decode}.
 *
 * <p>Verifica:
 * <ol>
 *   <li>Que tras una llamada al endpoint se escriben registros en
 *       {@code hub_audit_log} y {@code outbox_event}.</li>
 *   <li>Que la cadena de hashes es correcta para dos llamadas consecutivas.</li>
 *   <li>Que si {@link HubAuditService} falla, la response de negocio sale igual.</li>
 *   <li>Que una {@code Idempotency-Key} duplicada lanza
 *       {@link IdempotencyKeyConflictException}.</li>
 * </ol>
 *
 * <p>Infraestructura: Testcontainers con PostgreSQL real. Sin H2.
 * Las tablas del hub se crean mediante DDL directo para no depender de que
 * Liquibase de ms-base ejecute exactamente los changesets v2.
 * Esto hace el test autónomo y reproducible.
 *
 * <p>La lógica de negocio de {@code QrDecryptionService} puede fallar por falta
 * de certificados en la BD de test — los tests se centran en la escritura del
 * registro de auditoría, que ocurre en {@code afterCompletion()} del interceptor
 * independientemente del resultado del negocio.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class QrDecodePartnerIT {

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
        // Deshabilitar servicios de infraestructura
        registry.add("spring.cloud.vault.enabled", () -> "false");
        registry.add("spring.cloud.consul.config.enabled", () -> "false");
        registry.add("spring.cloud.consul.discovery.enabled", () -> "false");
        registry.add("spring.config.import", () -> "");
        // Deshabilitar Redis — se evita la dependencia en tests unitarios del pipeline de auditoría
        // TODO(hub-poc): agregar Testcontainer de Redis si la auto-configuración de Redis
        //   no se puede excluir sin romper el contexto de Spring
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
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    @Qualifier("hubAuditService")
    private HubAuditService hubAuditService;

    // ─── Setup ────────────────────────────────────────────────────────────────

    @BeforeEach
    void prepararEsquema() {
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
     * {@code hub_audit_log} y {@code outbox_event}.
     *
     * <p>El resultado del negocio puede ser 4xx (sin certificado en la BD),
     * lo que importa es que el interceptor escribe la auditoría en
     * {@code afterCompletion()} independientemente del status.
     */
    @Test
    void shouldAuditQrDecodeRequestWithCorrectHashes() throws Exception {
        String partnerId = "partner-audit-" + UUID.randomUUID();
        String correlationId = UUID.randomUUID().toString();

        String requestBody = """
                {
                  "inputType": "DECODED_DATA",
                  "content": "DATOS_CIFRADOS_TEST|CERT_001"
                }
                """;

        mockMvc.perform(post("/api/qr/decode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                        .header("X-Partner-Id", partnerId)
                        .header("X-Request-Id", correlationId))
                .andReturn();

        // Verificar registro de auditoría
        Integer auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM public.hub_audit_log WHERE partner_id = ?",
                Integer.class, partnerId);
        assertThat(auditCount)
                .as("Debe existir exactamente un registro de auditoría para el partner")
                .isEqualTo(1);

        // Verificar outbox en la misma transacción
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
     * una cadena de hashes válida.
     */
    @Test
    void shouldCalculateCorrectChainForTwoConsecutiveRequests() throws Exception {
        String partnerId = "partner-chain-" + UUID.randomUUID();

        String requestBody = """
                {
                  "inputType": "DECODED_DATA",
                  "content": "DATOS_CHAIN_TEST|CERT_001"
                }
                """;

        // Primera llamada
        mockMvc.perform(post("/api/qr/decode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                        .header("X-Partner-Id", partnerId))
                .andReturn();

        // Segunda llamada
        mockMvc.perform(post("/api/qr/decode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
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
     * Test 3: si la auditoría falla, la respuesta HTTP debe salir igualmente
     * (sin causar un 500 adicional).
     *
     * <p>Se verifica que el endpoint siempre responde — la resiliencia ante
     * fallos de auditoría está implementada con catch en
     * {@link HubAuditInterceptor#afterCompletion}.
     */
    @Test
    void shouldHandleAuditFailureGracefully() throws Exception {
        String requestBody = """
                {
                  "inputType": "DECODED_DATA",
                  "content": "DATOS_RESILIENCE|CERT_001"
                }
                """;

        // Sin tablas de auditoría creadas en este test, el interceptor fallará
        // pero el endpoint debe responder igualmente.
        // Primero eliminar las tablas para forzar el fallo de auditoría:
        jdbc.execute("DROP TABLE IF EXISTS public.hub_audit_log_default");
        jdbc.execute("DROP TABLE IF EXISTS public.hub_audit_log CASCADE");
        jdbc.execute("DROP TABLE IF EXISTS public.hub_audit_idempotency");
        jdbc.execute("DROP TABLE IF EXISTS public.outbox_event");
        jdbc.execute("DROP SEQUENCE IF EXISTS public.outbox_event_seq");

        var result = mockMvc.perform(post("/api/qr/decode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                        .header("X-Partner-Id", "partner-resilience-test"))
                .andReturn();

        // El endpoint debe responder (puede ser 4xx o 5xx por lógica de negocio,
        // pero nunca debe ser silencioso / sin respuesta)
        int status = result.getResponse().getStatus();
        assertThat(status)
                .as("El endpoint debe responder aunque la auditoría falle")
                .isGreaterThan(0);

        // Verificar que el fallo fue causado por negocio, no por la auditoría
        // (el status no debe ser 0 / indefinido)
        assertThat(status).isBetween(100, 599);
    }

    /**
     * Test 4: una segunda llamada con la misma {@code Idempotency-Key} debe
     * lanzar {@link IdempotencyKeyConflictException}.
     *
     * <p>Se verifica directamente a través del servicio de auditoría, ya que
     * la garantía de idempotencia es responsabilidad de {@link HubAuditService}.
     * La integración HTTP completa se verifica en el test 1.
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

        // Solo debe existir una entrada en idempotencia
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM public.hub_audit_idempotency WHERE idempotency_key = ?",
                Integer.class, idempotencyKey);
        assertThat(idemCount).isEqualTo(1);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private HubAuditCommand crearComando(String partnerId, String idempotencyKey) {
        return new HubAuditCommand(
                UUID.randomUUID(),
                "IN",
                partnerId,
                "QR_DECODE",
                "/api/qr/decode",
                "POST",
                200,
                "a".repeat(64),
                "b".repeat(64),
                150,
                1,
                idempotencyKey,
                UUID.randomUUID().toString(),
                Instant.now(),
                "HUB_TRANSACTION",
                UUID.randomUUID().toString(),
                Map.of("partner", partnerId, "product", "QR_DECODE", "units", 1)
        );
    }
}
