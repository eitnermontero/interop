package bo.com.sintesis.mdqr.base.service;

import bo.com.sintesis.mdqr.base.domain.PaymentTransaction;
import bo.com.sintesis.mdqr.base.repository.PaymentTransactionRepository;
import bo.com.sintesis.mdqr.base.web.rest.errors.IdempotencyConflictException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private PaymentTransactionRepository transactionRepository;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private IdempotencyService idempotencyService;

    private static final Long PARTNER_ID = 1L;
    private static final String IDEMPOTENCY_KEY = "unique-key-123";
    private static final String REQUEST_BODY = "{\"providerId\":100,\"accountNumber\":\"ACC-001\"}";

    private String expectedHash;

    @BeforeEach
    void setUp() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(REQUEST_BODY.getBytes(StandardCharsets.UTF_8));
        expectedHash = HexFormat.of().formatHex(hash);
    }

    @Test
    @DisplayName("checkExisting returns empty when no prior transaction exists")
    void checkExisting_noPriorTransaction_returnsEmpty() {
        when(transactionRepository.findByPartnerIdAndIdempotencyKey(PARTNER_ID, IDEMPOTENCY_KEY))
            .thenReturn(Optional.empty());

        Optional<PaymentTransaction> result = idempotencyService.checkExisting(PARTNER_ID, IDEMPOTENCY_KEY, REQUEST_BODY);

        assertThat(result).isEmpty();
        verify(transactionRepository).findByPartnerIdAndIdempotencyKey(PARTNER_ID, IDEMPOTENCY_KEY);
    }

    @Test
    @DisplayName("checkExisting returns cached transaction when same payload hash matches")
    void checkExisting_samePayload_returnsCachedTransaction() {
        PaymentTransaction existing = new PaymentTransaction();
        existing.setTxCode("txn-001");
        existing.setIdempotencyPayloadHash(expectedHash);
        existing.setIdempotencyResponse("{\"transactionId\":\"txn-001\"}");

        when(transactionRepository.findByPartnerIdAndIdempotencyKey(PARTNER_ID, IDEMPOTENCY_KEY))
            .thenReturn(Optional.of(existing));

        Optional<PaymentTransaction> result = idempotencyService.checkExisting(PARTNER_ID, IDEMPOTENCY_KEY, REQUEST_BODY);

        assertThat(result).isPresent();
        assertThat(result.get().getTxCode()).isEqualTo("txn-001");
    }

    @Test
    @DisplayName("checkExisting throws 409 IdempotencyConflictException when payload hash differs")
    void checkExisting_differentPayload_throws409() {
        PaymentTransaction existing = new PaymentTransaction();
        existing.setTxCode("txn-001");
        existing.setIdempotencyPayloadHash("totally-different-hash-value");

        when(transactionRepository.findByPartnerIdAndIdempotencyKey(PARTNER_ID, IDEMPOTENCY_KEY))
            .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> idempotencyService.checkExisting(PARTNER_ID, IDEMPOTENCY_KEY, REQUEST_BODY))
            .isInstanceOf(IdempotencyConflictException.class)
            .hasMessageContaining(IDEMPOTENCY_KEY);
    }

    @Test
    @DisplayName("computeHash returns consistent SHA-256 hex string")
    void computeHash_returnsConsistentHash() {
        String hash1 = idempotencyService.computeHash(REQUEST_BODY);
        String hash2 = idempotencyService.computeHash(REQUEST_BODY);

        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).isEqualTo(expectedHash);
        assertThat(hash1).hasSize(64); // SHA-256 = 32 bytes = 64 hex chars
    }

    @Test
    @DisplayName("cacheResponse serializes and saves to transaction")
    void cacheResponse_savesSerializedJson() throws Exception {
        PaymentTransaction txn = new PaymentTransaction();
        txn.setTxCode("txn-001");

        Object response = new Object();
        String serialized = "{\"transactionId\":\"txn-001\",\"status\":\"INITIATED\"}";
        when(objectMapper.writeValueAsString(response)).thenReturn(serialized);

        idempotencyService.cacheResponse(txn, response);

        assertThat(txn.getIdempotencyResponse()).isEqualTo(serialized);
        verify(transactionRepository).save(txn);
    }

    @Test
    @DisplayName("cacheResponse swallows exceptions without propagating")
    void cacheResponse_exceptionSwallowed() throws Exception {
        PaymentTransaction txn = new PaymentTransaction();
        txn.setTxCode("txn-001");

        when(objectMapper.writeValueAsString(any())).thenThrow(new RuntimeException("Serialization failed"));

        // Should not throw
        idempotencyService.cacheResponse(txn, new Object());

        verify(transactionRepository, never()).save(any());
    }
}
