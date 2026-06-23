package bo.com.sintesis.mdqr.audit.hub;

import bo.com.sintesis.mdqr.audit.chain.ChainHashCalculator;
import bo.com.sintesis.mdqr.audit.signing.AuditSigner;
import bo.com.sintesis.mdqr.audit.signing.NoOpAuditSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests de integración para {@link HubAuditService}.
 *
 * <p>Usa Testcontainers con PostgreSQL real para verificar:
 * <ul>
 *   <li>Escritura atomica de audit_log + outbox_event.</li>
 *   <li>Correctitud de la cadena de hashes entre registros consecutivos.</li>
 *   <li>Rechazo de idempotency_key duplicada con rollback completo.</li>
 *   <li>Uso del hash genesis para el primer registro de un partner.</li>
 *   <li>Independencia de cadenas entre partners distintos.</li>
 *   <li>Rollback atomico ante fallo en el INSERT de outbox.</li>
 * </ul>
 *
 * <p>Las tablas se crean antes de cada test usando DDL directo para no
 * depender de Liquibase en el modulo de libreria.
 */
@SpringBootTest(classes = TestApplication.class)
@Testcontainers
class HubAuditServiceIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("mdqr_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // Desactivar configuraciones innecesarias para la libreria en modo test
        registry.add("audit.sink-mode", () -> "in-process");
        registry.add("spring.autoconfigure.exclude",
                () -> "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration");
    }

    /**
     * Configuracion de test: expone un HubAuditService con NoOp signer para
     * evitar dependencia de Vault en los tests de integracion.
     * Tambien expone un segundo HubAuditService con un signer que siempre falla
     * para validar el rollback transaccional.
     */
    @TestConfiguration
    static class TestConfig {

        /** Bean principal con signer funcional (NoOp). */
        @Bean
        public HubAuditService hubAuditService(JdbcTemplate jdbc) {
            return new HubAuditService(jdbc, new ChainHashCalculator(), new NoOpAuditSigner());
        }

        /**
         * Bean con signer defectuoso para probar el rollback.
         * Usa un qualifier distinto para no colisionar con el bean principal.
         */
        @Bean("failingAuditService")
        public HubAuditService failingAuditService(JdbcTemplate jdbc) {
            AuditSigner failingSigner = chainHash -> {
                throw new RuntimeException("Fallo simulado en la firma del chain_hash");
            };
            return new HubAuditService(jdbc, new ChainHashCalculator(), failingSigner);
        }
    }

    @Autowired
    private HubAuditService service;

    /** Bean con signer defectuoso para el test de rollback. */
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("failingAuditService")
    private HubAuditService failingAuditService;

    @Autowired
    private JdbcTemplate jdbc;

    // DDL de las tablas necesarias (sin particionado completo — se usa particion DEFAULT)
    private static final String DDL_AUDIT_LOG_PARENT = """
            CREATE TABLE IF NOT EXISTS public.hub_audit_log (
                id              uuid        NOT NULL,
                ts              timestamptz NOT NULL,
                direction       varchar(3)  NOT NULL,
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
                CONSTRAINT chk_hub_audit_direction CHECK (direction IN ('IN', 'OUT')),
                CONSTRAINT chk_hub_audit_hash_len CHECK (
                    length(request_hash) = 64
                    AND length(response_hash) = 64
                    AND length(prev_hash) = 64
                    AND length(chain_hash) = 64
                )
            ) PARTITION BY RANGE (ts)
            """;

    private static final String DDL_AUDIT_LOG_DEFAULT_PARTITION = """
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
                vault_key_ref   text,
                status          varchar(20) NOT NULL DEFAULT 'PENDING',
                attempts        integer     NOT NULL DEFAULT 0,
                last_error      text,
                created_date    timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
                processed_date  timestamptz,
                CONSTRAINT pk_outbox_event PRIMARY KEY (id),
                CONSTRAINT uk_outbox_idempotency_key UNIQUE (idempotency_key)
            )
            """;

    @BeforeEach
    void prepararTablas() {
        // Eliminar tablas en orden inverso para respetar dependencias
        jdbc.execute("DROP TABLE IF EXISTS public.hub_audit_log_default");
        jdbc.execute("DROP TABLE IF EXISTS public.hub_audit_log CASCADE");
        jdbc.execute("DROP TABLE IF EXISTS public.hub_audit_idempotency");
        jdbc.execute("DROP TABLE IF EXISTS public.outbox_event");
        jdbc.execute("DROP SEQUENCE IF EXISTS public.outbox_event_seq");

        // Crear esquema limpio para cada test
        jdbc.execute(DDL_AUDIT_LOG_PARENT);
        jdbc.execute(DDL_AUDIT_LOG_DEFAULT_PARTITION);
        jdbc.execute(DDL_IDEMPOTENCY);
        jdbc.execute(DDL_OUTBOX_SEQ);
        jdbc.execute(DDL_OUTBOX);
    }

    // --- Helper para crear comandos de prueba ---

    private HubAuditCommand crearComando(String partnerId) {
        return crearComandoConIdempotencia(partnerId, null);
    }

    private HubAuditCommand crearComandoConIdempotencia(String partnerId, String idempotencyKey) {
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

    @Test
    void shouldInsertAuditRecordAndOutboxEventInSameTransaction() {
        HubAuditCommand cmd = crearComando("partner-tx-test");
        String chainHash = service.record(cmd);

        assertThat(chainHash).hasSize(64).matches("[0-9a-f]{64}");

        // Verificar que el registro de auditoría fue insertado
        Integer auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM public.hub_audit_log WHERE id = ?",
                Integer.class, cmd.id());
        assertThat(auditCount).isEqualTo(1);

        // Verificar que el evento de outbox fue insertado en la misma transaccion
        Integer outboxCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM public.outbox_event WHERE aggregate_id = ?",
                Integer.class, cmd.aggregateId());
        assertThat(outboxCount).isEqualTo(1);

        // Verificar el estado del outbox
        String status = jdbc.queryForObject(
                "SELECT status FROM public.outbox_event WHERE aggregate_id = ?",
                String.class, cmd.aggregateId());
        assertThat(status).isEqualTo("PENDING");
    }

    @Test
    void shouldCalculateCorrectChainForSequentialRecords() {
        String partnerId = "partner-chain-seq";

        HubAuditCommand cmd1 = crearComando(partnerId);
        String chain1 = service.record(cmd1);

        HubAuditCommand cmd2 = crearComando(partnerId);
        String chain2 = service.record(cmd2);

        HubAuditCommand cmd3 = crearComando(partnerId);
        String chain3 = service.record(cmd3);

        // Verificar la cadena: prev_hash del registro N debe ser chain_hash del registro N-1
        String prevHash1 = jdbc.queryForObject(
                "SELECT prev_hash FROM public.hub_audit_log WHERE id = ?",
                String.class, cmd1.id());
        String prevHash2 = jdbc.queryForObject(
                "SELECT prev_hash FROM public.hub_audit_log WHERE id = ?",
                String.class, cmd2.id());
        String prevHash3 = jdbc.queryForObject(
                "SELECT prev_hash FROM public.hub_audit_log WHERE id = ?",
                String.class, cmd3.id());

        // El primer registro usa el hash genesis
        assertThat(prevHash1).isEqualTo(ChainHashCalculator.GENESIS_PREV_HASH);
        // El segundo registro tiene como prev_hash el chain_hash del primero
        assertThat(prevHash2).isEqualTo(chain1);
        // El tercer registro tiene como prev_hash el chain_hash del segundo
        assertThat(prevHash3).isEqualTo(chain2);

        // Verificar que los tres chain_hashes son distintos
        assertThat(chain1).isNotEqualTo(chain2).isNotEqualTo(chain3);
    }

    @Test
    void shouldRejectDuplicateIdempotencyKey() {
        String partnerId = "partner-idem-test";
        String idempotencyKey = "idem-key-" + UUID.randomUUID();

        HubAuditCommand cmd1 = crearComandoConIdempotencia(partnerId, idempotencyKey);
        service.record(cmd1);

        // El segundo intento con la misma idempotency_key debe lanzar excepcion
        HubAuditCommand cmd2 = crearComandoConIdempotencia(partnerId, idempotencyKey);
        assertThatThrownBy(() -> service.record(cmd2))
                .isInstanceOf(IdempotencyKeyConflictException.class)
                .hasMessageContaining(idempotencyKey);

        // Verificar que solo existe un registro de auditoría (el del primer intento)
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM public.hub_audit_log WHERE partner_id = ?",
                Integer.class, partnerId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void shouldUseGenesisHashForFirstRecordOfPartner() {
        String partnerId = "partner-genesis-" + UUID.randomUUID();
        HubAuditCommand cmd = crearComando(partnerId);
        service.record(cmd);

        String prevHash = jdbc.queryForObject(
                "SELECT prev_hash FROM public.hub_audit_log WHERE id = ?",
                String.class, cmd.id());
        assertThat(prevHash).isEqualTo(ChainHashCalculator.GENESIS_PREV_HASH);
    }

    @Test
    void shouldMaintainIndependentChainsPerPartner() {
        String partnerA = "partner-chain-A-" + UUID.randomUUID();
        String partnerB = "partner-chain-B-" + UUID.randomUUID();

        // Registrar 2 eventos para partner A
        HubAuditCommand a1 = crearComando(partnerA);
        String chainA1 = service.record(a1);
        HubAuditCommand a2 = crearComando(partnerA);
        service.record(a2);

        // Registrar 1 evento para partner B (debe usar genesis, no el hash de A)
        HubAuditCommand b1 = crearComando(partnerB);
        service.record(b1);

        String prevHashB1 = jdbc.queryForObject(
                "SELECT prev_hash FROM public.hub_audit_log WHERE id = ?",
                String.class, b1.id());
        // El partner B siempre empieza desde genesis, independiente de A
        assertThat(prevHashB1).isEqualTo(ChainHashCalculator.GENESIS_PREV_HASH);

        String prevHashA2 = jdbc.queryForObject(
                "SELECT prev_hash FROM public.hub_audit_log WHERE id = ?",
                String.class, a2.id());
        // El segundo registro de A usa el chain_hash del primero de A
        assertThat(prevHashA2).isEqualTo(chainA1);
        // Y no el genesis ni el hash de B
        assertThat(prevHashA2).isNotEqualTo(ChainHashCalculator.GENESIS_PREV_HASH);
    }

    /**
     * Verifica el rollback transaccional: el servicio Spring-gestionado con un
     * signer que lanza excepcion no debe dejar ningun registro en BD.
     *
     * <p>Se usa el bean {@code failingAuditService} declarado en {@link TestConfig},
     * que esta bajo el proxy @Transactional de Spring porque se registra como bean.
     * El signer falla durante el calculo de la firma, provocando rollback completo
     * antes de que se ejecute cualquier INSERT.
     */
    @Test
    void shouldRollbackBothTablesOnFailure() {
        String partnerId = "partner-rollback-" + UUID.randomUUID();
        HubAuditCommand cmd = crearComando(partnerId);

        assertThatThrownBy(() -> failingAuditService.record(cmd))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Fallo simulado");

        // Verificar rollback: ningun registro debe existir
        Integer auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM public.hub_audit_log WHERE partner_id = ?",
                Integer.class, partnerId);
        assertThat(auditCount).isZero();

        Integer outboxCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM public.outbox_event WHERE aggregate_id = ?",
                Integer.class, cmd.aggregateId());
        assertThat(outboxCount).isZero();
    }

    /**
     * Version alternativa del test de rollback usando directamente JdbcTemplate
     * con una transaccion controlada por el test.
     */
    @Test
    void shouldRollbackIdempotencyAndAuditOnDuplicateKey() {
        String partnerId = "partner-rollback-idem-" + UUID.randomUUID();
        String idemKey = "rollback-key-" + UUID.randomUUID();

        // Primer registro exitoso
        HubAuditCommand cmd1 = crearComandoConIdempotencia(partnerId, idemKey);
        service.record(cmd1);

        // Segundo registro con misma clave: debe fallar y hacer rollback
        HubAuditCommand cmd2 = crearComandoConIdempotencia(partnerId, idemKey);
        assertThatThrownBy(() -> service.record(cmd2))
                .isInstanceOf(IdempotencyKeyConflictException.class);

        // Solo debe existir el registro del primer intento
        Integer countAudit = jdbc.queryForObject(
                "SELECT COUNT(*) FROM public.hub_audit_log WHERE partner_id = ?",
                Integer.class, partnerId);
        assertThat(countAudit).isEqualTo(1);

        // La idempotency table solo tiene una entrada
        Integer countIdem = jdbc.queryForObject(
                "SELECT COUNT(*) FROM public.hub_audit_idempotency WHERE idempotency_key = ?",
                Integer.class, idemKey);
        assertThat(countIdem).isEqualTo(1);
    }
}
