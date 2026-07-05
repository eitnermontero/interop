package bo.com.sintesis.hub.audit.hub;

import bo.com.sintesis.hub.audit.chain.ChainHashCalculator;
import bo.com.sintesis.hub.audit.chain.ChainHashEntry;
import bo.com.sintesis.hub.audit.signing.AuditSigner;
import bo.com.sintesis.hub.audit.signing.SignResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Servicio central de auditoría del hub. Escribe de forma atómica:
 * <ol>
 *   <li>Registro en {@code public.hub_audit_idempotency} (si hay {@code idempotency_key}).</li>
 *   <li>Registro en {@code public.hub_audit_log} con el {@code chain_hash} calculado.</li>
 *   <li>Evento de outbox en {@code public.outbox_event} para facturación.</li>
 * </ol>
 *
 * <p>Garantías de consistencia:
 * <ul>
 *   <li>Los tres INSERTs ocurren en la misma transacción JPA/JDBC.</li>
 *   <li>Se adquiere un advisory lock por partner ({@code pg_advisory_xact_lock})
 *       para serializar la lectura del {@code prev_hash} y la escritura del
 *       nuevo {@code chain_hash}, evitando condiciones de carrera en la cadena.</li>
 *   <li>Si la {@code idempotency_key} ya existe, se lanza
 *       {@link IdempotencyKeyConflictException} y la transacción hace rollback.</li>
 * </ul>
 *
 * <p>Esta clase solo tiene lógica de coordinación. El cálculo del hash y la
 * firma se delegan en {@link ChainHashCalculator} y {@link AuditSigner}.
 */
public class HubAuditService {

    private static final Logger log = LoggerFactory.getLogger(HubAuditService.class);

    private static final String SQL_ADVISORY_LOCK =
            "SELECT pg_advisory_xact_lock(?)";

    private static final String SQL_PREV_HASH =
            "SELECT chain_hash FROM public.hub_audit_log " +
            "WHERE partner_id = ? ORDER BY ts DESC LIMIT 1";

    private static final String SQL_INSERT_IDEMPOTENCY =
            "INSERT INTO public.hub_audit_idempotency (idempotency_key, audit_id, ts, created_date) " +
            "VALUES (?, ?, ?, NOW())";

    private static final String SQL_INSERT_AUDIT =
            "INSERT INTO public.hub_audit_log " +
            "  (id, ts, direction, partner_id, product, endpoint, http_method, http_status, " +
            "   request_hash, response_hash, latency_ms, billable_units, idempotency_key, " +
            "   correlation_id, prev_hash, chain_hash, signature, key_version) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SQL_INSERT_OUTBOX =
            "INSERT INTO public.outbox_event " +
            "  (aggregate_type, aggregate_id, event_type, idempotency_key, payload, status, " +
            "   attempts, created_date) " +
            "VALUES (?, ?, 'transaction.billable', ?, ?::jsonb, 'PENDING', 0, NOW())";

    private final JdbcTemplate jdbc;
    private final ChainHashCalculator chainCalculator;
    private final AuditSigner signer;
    private final ObjectMapper objectMapper;

    public HubAuditService(JdbcTemplate jdbc,
                           ChainHashCalculator chainCalculator,
                           AuditSigner signer) {
        this.jdbc = jdbc;
        this.chainCalculator = chainCalculator;
        this.signer = signer;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());
    }

    /**
     * Registra una transacción de hub en la tabla de auditoría y el outbox
     * dentro de una única transacción de base de datos.
     *
     * @param cmd command con todos los datos de la transacción a registrar
     * @return el {@code chain_hash} calculado para este registro
     * @throws IdempotencyKeyConflictException si la {@code idempotency_key} ya fue procesada
     */
    @Transactional
    public String record(HubAuditCommand cmd) {
        log.debug("Registrando auditoría hub: partner={}, product={}, direction={}",
                cmd.partnerId(), cmd.product(), cmd.direction());

        // 1. Advisory lock por partner para serializar la cadena de hashes
        long lockId = Math.abs((long) cmd.partnerId().hashCode());
        jdbc.execute(SQL_ADVISORY_LOCK,
                (PreparedStatementCallback<Void>) ps -> {
                    ps.setLong(1, lockId);
                    ps.execute();
                    return null;
                });

        // 2. Obtener el prev_hash del ultimo registro del partner (o usar genesis)
        String prevHash = obtenerPrevHash(cmd.partnerId());

        // 3. Calcular el chain_hash
        ChainHashEntry entry = new ChainHashEntry(
                cmd.partnerId(),
                cmd.requestHash(),
                cmd.responseHash(),
                cmd.timestamp(),
                prevHash);
        String chainHash = chainCalculator.calculate(entry);

        // 4. Firmar el chain_hash
        SignResult signResult = signer.sign(chainHash);
        String signature = signResult.signature().isEmpty() ? null : signResult.signature();
        Integer keyVersion = signResult.keyVersion() == 0 ? null : signResult.keyVersion();

        // 5a. INSERT en hub_audit_idempotency (solo si hay idempotency_key)
        if (cmd.idempotencyKey() != null) {
            insertarIdempotencia(cmd);
        }

        // 5b. INSERT en hub_audit_log
        insertarAuditLog(cmd, prevHash, chainHash, signature, keyVersion);

        // 5c. INSERT en outbox_event
        insertarOutbox(cmd);

        log.debug("Auditoría registrada: id={}, chainHash={}", cmd.id(), chainHash);
        return chainHash;
    }

    private String obtenerPrevHash(String partnerId) {
        List<String> results = jdbc.query(
                SQL_PREV_HASH,
                (rs, rowNum) -> rs.getString("chain_hash"),
                partnerId);
        return results.isEmpty() ? ChainHashCalculator.GENESIS_PREV_HASH : results.get(0);
    }

    private void insertarIdempotencia(HubAuditCommand cmd) {
        try {
            jdbc.update(SQL_INSERT_IDEMPOTENCY,
                    cmd.idempotencyKey(),
                    cmd.id(),
                    Timestamp.from(cmd.timestamp()));
        } catch (DuplicateKeyException e) {
            log.warn("Idempotency key duplicada: {}", cmd.idempotencyKey());
            throw new IdempotencyKeyConflictException(cmd.idempotencyKey());
        }
    }

    private void insertarAuditLog(HubAuditCommand cmd,
                                   String prevHash,
                                   String chainHash,
                                   String signature,
                                   Integer keyVersion) {
        jdbc.update(SQL_INSERT_AUDIT,
                cmd.id(),
                Timestamp.from(cmd.timestamp()),
                cmd.direction(),
                cmd.partnerId(),
                cmd.product(),
                cmd.endpoint(),
                cmd.httpMethod(),
                (short) cmd.httpStatus(),
                cmd.requestHash(),
                cmd.responseHash(),
                cmd.latencyMs(),
                cmd.billableUnits(),
                cmd.idempotencyKey(),
                cmd.correlationId(),
                prevHash,
                chainHash,
                signature,
                keyVersion);
    }

    private void insertarOutbox(HubAuditCommand cmd) {
        String payloadJson = serializarPayload(cmd.outboxPayload());
        // La idempotency_key del outbox es la misma del audit, o el audit_id si no hay
        String outboxIdempotencyKey = cmd.idempotencyKey() != null
                ? cmd.idempotencyKey()
                : cmd.id().toString();

        jdbc.update(SQL_INSERT_OUTBOX,
                cmd.aggregateType(),
                cmd.aggregateId(),
                outboxIdempotencyKey,
                payloadJson);
    }

    private String serializarPayload(Object payload) {
        if (payload == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("No se pudo serializar el payload del outbox; usando {{}}: {}", e.getMessage());
            return "{}";
        }
    }

    /**
     * Consulta el {@code chain_hash} del ultimo registro de un partner para
     * verificacion externa de la cadena de integridad.
     *
     * @param partnerId identificador del partner
     * @return {@code chain_hash} del ultimo registro, o el hash genesis si no hay registros
     */
    @Transactional(readOnly = true)
    public String obtenerUltimoChainHash(String partnerId) {
        return obtenerPrevHash(partnerId);
    }
}
