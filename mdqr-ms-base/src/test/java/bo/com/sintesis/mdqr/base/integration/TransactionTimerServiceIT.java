package bo.com.sintesis.mdqr.base.integration;

import bo.com.sintesis.mdqr.base.domain.Partner;
import bo.com.sintesis.mdqr.base.domain.PaymentTransaction;
import bo.com.sintesis.mdqr.base.enumeration.CurrencyEnum;
import bo.com.sintesis.mdqr.base.enumeration.TransactionStatusEnum;
import bo.com.sintesis.mdqr.base.repository.PartnerRepository;
import bo.com.sintesis.mdqr.base.repository.PaymentTransactionRepository;
import bo.com.sintesis.mdqr.base.service.TransactionTimerService;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class TransactionTimerServiceIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
        .withDatabaseName("middleware_core_test")
        .withUsername("test")
        .withPassword("test");

    @Container
    static RedisContainer redis = new RedisContainer("redis:8-alpine")
        .withCommand("redis-server", "--notify-keyspace-events", "Ex");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379).toString());
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "http://localhost:18080/realms/test");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "http://localhost:18080/realms/test/protocol/openid-connect/certs");
    }

    @Autowired
    private TransactionTimerService timerService;

    @Autowired
    private PaymentTransactionRepository transactionRepository;

    @Autowired
    private PartnerRepository partnerRepository;

    private Partner testPartner;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        partnerRepository.deleteAll();

        testPartner = new Partner();
        testPartner.setPartnerId("timer-test-partner");
        testPartner.setPartnerPublicId("pub-timer-test");
        testPartner.setName("Timer Test Partner");
        testPartner.setIsActive(true);
        testPartner.setCreatedBy("test");
        testPartner.setLastModifiedBy("test");
        testPartner = partnerRepository.save(testPartner);
    }

    @Test
    @DisplayName("Timer fires after TTL and marks INITIATED transaction as EXPIRED")
    void timerFires_marksTransactionExpired() {
        String txnId = UUID.randomUUID().toString();

        PaymentTransaction txn = new PaymentTransaction();
        txn.setTxCode(txnId);
        txn.setIdempotencyKey("timer-test-key");
        txn.setIdempotencyPayloadHash("abc123");
        txn.setPartner(testPartner);
        txn.setCartId("cart-timer-test");
        txn.setProviderId(100);
        txn.setAccountNumber("ACC-TIMER");
        txn.setOperationDate(LocalDate.now().toString());
        txn.setOperationNumber(1);
        txn.setServiceCode(10);
        txn.setUserCurrency(CurrencyEnum.USDT);
        txn.setExchangeRate(new BigDecimal("6.96"));
        txn.setUserTotalAmount(new BigDecimal("14.37"));
        txn.setLocalTotalAmount(new BigDecimal("100.00"));
        txn.setStatus(TransactionStatusEnum.STARTED);
        txn.setExpiresAt(Instant.now().plusSeconds(2));
        txn.setCreatedBy("test");
        txn.setLastModifiedBy("test");
        transactionRepository.save(txn);

        // Start timer with 2-second TTL
        timerService.start(txnId, 2);

        // Wait for Redis keyspace notification to fire and handler to update DB
        await().atMost(10, TimeUnit.SECONDS)
            .pollInterval(500, TimeUnit.MILLISECONDS)
            .untilAsserted(() -> {
                PaymentTransaction updated = transactionRepository.findByTxCode(txnId).orElseThrow();
                assertThat(updated.getStatus()).isEqualTo(TransactionStatusEnum.EXPIRED);
            });
    }

    @Test
    @DisplayName("Cancel removes the timer key so it does not fire")
    void cancel_preventsExpiry() throws Exception {
        String txnId = UUID.randomUUID().toString();

        PaymentTransaction txn = new PaymentTransaction();
        txn.setTxCode(txnId);
        txn.setIdempotencyKey("cancel-test-key");
        txn.setIdempotencyPayloadHash("def456");
        txn.setPartner(testPartner);
        txn.setCartId("cart-cancel-test");
        txn.setProviderId(100);
        txn.setAccountNumber("ACC-CANCEL");
        txn.setOperationDate(LocalDate.now().toString());
        txn.setOperationNumber(2);
        txn.setServiceCode(10);
        txn.setUserCurrency(CurrencyEnum.USDT);
        txn.setExchangeRate(new BigDecimal("6.96"));
        txn.setUserTotalAmount(new BigDecimal("14.37"));
        txn.setLocalTotalAmount(new BigDecimal("100.00"));
        txn.setStatus(TransactionStatusEnum.STARTED);
        txn.setExpiresAt(Instant.now().plusSeconds(3));
        txn.setCreatedBy("test");
        txn.setLastModifiedBy("test");
        transactionRepository.save(txn);

        timerService.start(txnId, 3);
        timerService.cancel(txnId);

        // Wait a bit longer than the TTL would have been
        Thread.sleep(5000);

        PaymentTransaction result = transactionRepository.findByTxCode(txnId).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(TransactionStatusEnum.STARTED);
    }
}
