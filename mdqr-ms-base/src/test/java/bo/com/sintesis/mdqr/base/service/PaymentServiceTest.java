package bo.com.sintesis.mdqr.base.service;

import bo.com.sintesis.mdqr.base.enumeration.CurrencyEnum;
import bo.com.sintesis.mdqr.base.enumeration.TransactionStatusEnum;
import bo.com.sintesis.sdk.intraplatinum.model.request.IpcLoginRequest;
import bo.com.sintesis.mdqr.base.domain.Partner;
import bo.com.sintesis.mdqr.base.domain.PaymentTransaction;
import bo.com.sintesis.mdqr.base.integration.currencyengine.dto.ExchangeRateDto;
import bo.com.sintesis.mdqr.base.integration.genesis.GenesisService;
import bo.com.sintesis.mdqr.base.repository.PartnerRepository;
import bo.com.sintesis.mdqr.base.repository.PaymentTransactionItemRepository;
import bo.com.sintesis.mdqr.base.repository.PaymentTransactionRepository;
import bo.com.sintesis.mdqr.base.security.SecurityUtils;
import bo.com.sintesis.mdqr.base.service.dto.PaymentInitResponse;
import bo.com.sintesis.mdqr.base.web.rest.request.InitCartRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock private GenesisService genesisService;
    @Mock private VaultPartnerService vaultPartnerService;
    @Mock private ExchangeRateService exchangeRateService;
    @Mock private IdempotencyService idempotencyService;
    @Mock private TransactionTimerService timerService;
    @Mock private PaymentTransactionRepository transactionRepository;
    @Mock private PaymentTransactionItemRepository itemRepository;
    @Mock private PartnerRepository partnerRepository;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks
    private PaymentService paymentService;

    private MockedStatic<SecurityUtils> securityUtilsMock;

    private static final String PARTNER_ID = "partner-001";
    private static final String CART_ID = "cart-abc";
    private static final BigDecimal BID_PRICE = new BigDecimal("6.960000000000");
    private static final BigDecimal LOCAL_AMOUNT = new BigDecimal("100.00");

    private Partner partner;
    private IpcLoginRequest creds;
    private ExchangeRateDto rate;
    private InitCartRequest initRequest;

    @BeforeEach
    void setUp() throws Exception {
        ReflectionTestUtils.setField(paymentService, "timeoutSeconds", 300L);

        securityUtilsMock = mockStatic(SecurityUtils.class);
        securityUtilsMock.when(SecurityUtils::getCurrentClientId).thenReturn(Optional.of(PARTNER_ID));

        partner = new Partner();
        partner.setId(1L);
        partner.setPartnerId(PARTNER_ID);
        partner.setName("Test Partner");
        partner.setIsActive(true);

        creds = new IpcLoginRequest();

        rate = new ExchangeRateDto();
        rate.setBidPrice(BID_PRICE);
        rate.setAskPrice(new BigDecimal("7.040000000000"));
        rate.setLastUpdated(Instant.now());

        initRequest = new InitCartRequest(
            "idem-key-001",
            100,
            "ACC-001",
            "2026-04-01",
            1,
            10,
            CurrencyEnum.USDT,
            List.of(new InitCartRequest.Item("ITEM-1", LOCAL_AMOUNT)),
            "John Doe",
            "123456",
            "CI",
            null,
            "john@example.com",
            null
        );
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    @Test
    @DisplayName("initCart locks rate, creates Genesis cart, starts timer, caches idempotency response")
    void initCart_happyPath() throws Exception {
        // Arrange
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"idempotencyKey\":\"idem-key-001\"}");
        when(partnerRepository.findByPartnerIdAndIsActiveTrue(PARTNER_ID)).thenReturn(Optional.of(partner));
        when(idempotencyService.checkExisting(eq(1L), eq("idem-key-001"), anyString())).thenReturn(Optional.empty());
        when(vaultPartnerService.getGenesisCredentials(PARTNER_ID)).thenReturn(creds);
        when(exchangeRateService.getCurrentRate()).thenReturn(rate);
        when(genesisService.createCart(creds)).thenReturn(CART_ID);
        when(genesisService.convertBobToUsdt(any(BigDecimal.class), eq(BID_PRICE)))
            .thenReturn(new BigDecimal("14.367816091954"));
        when(idempotencyService.computeHash(anyString())).thenReturn("abc123hash");

        when(transactionRepository.save(any(PaymentTransaction.class))).thenAnswer(invocation -> {
            PaymentTransaction txn = invocation.getArgument(0);
            txn.setId(42L);
            return txn;
        });
        when(itemRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        PaymentInitResponse response = paymentService.initCart(initRequest);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo("INITIATED");
        assertThat(response.userCurrency()).isEqualTo("USDT");
        assertThat(response.exchangeRate()).isEqualTo(BID_PRICE.toPlainString());

        // Verify interactions
        verify(exchangeRateService).getCurrentRate();
        verify(genesisService).createCart(creds);
        verify(genesisService).addItemsToCart(eq(creds), any(PaymentTransaction.class), anyList());
        verify(timerService).start(anyString(), eq(300L));
        verify(idempotencyService).cacheResponse(any(PaymentTransaction.class), any(PaymentInitResponse.class));
    }

    @Test
    @DisplayName("initCart returns cached response on idempotent replay (same key + same payload)")
    void initCart_idempotentReplay_returnsCached() throws Exception {
        PaymentTransaction existingTxn = buildExistingTransaction();

        when(objectMapper.writeValueAsString(any())).thenReturn("{\"idempotencyKey\":\"idem-key-001\"}");
        when(partnerRepository.findByPartnerIdAndIsActiveTrue(PARTNER_ID)).thenReturn(Optional.of(partner));
        when(idempotencyService.checkExisting(eq(1L), eq("idem-key-001"), anyString()))
            .thenReturn(Optional.of(existingTxn));

        PaymentInitResponse response = paymentService.initCart(initRequest);

        assertThat(response.txCode()).isEqualTo("txn-existing");
        assertThat(response.status()).isEqualTo("INITIATED");

        // Should NOT call Genesis or start timer
        verifyNoInteractions(genesisService);
        verifyNoInteractions(timerService);
    }

    @Test
    @DisplayName("initCart fetches exchange rate before creating Genesis cart")
    void initCart_rateLockedBeforeGenesis() throws Exception {
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        when(partnerRepository.findByPartnerIdAndIsActiveTrue(PARTNER_ID)).thenReturn(Optional.of(partner));
        when(idempotencyService.checkExisting(anyLong(), anyString(), anyString())).thenReturn(Optional.empty());
        when(vaultPartnerService.getGenesisCredentials(PARTNER_ID)).thenReturn(creds);
        when(exchangeRateService.getCurrentRate()).thenReturn(rate);
        when(genesisService.createCart(creds)).thenReturn(CART_ID);
        when(genesisService.convertBobToUsdt(any(BigDecimal.class), eq(BID_PRICE)))
            .thenReturn(new BigDecimal("14.37"));
        when(idempotencyService.computeHash(anyString())).thenReturn("hash");
        when(transactionRepository.save(any())).thenAnswer(inv -> {
            PaymentTransaction t = inv.getArgument(0);
            t.setId(1L);
            return t;
        });
        when(itemRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        paymentService.initCart(initRequest);

        // exchangeRate must be fetched before createCart
        var inOrder = inOrder(exchangeRateService, genesisService);
        inOrder.verify(exchangeRateService).getCurrentRate();
        inOrder.verify(genesisService).createCart(creds);
    }

    private PaymentTransaction buildExistingTransaction() {
        PaymentTransaction txn = new PaymentTransaction();
        txn.setId(99L);
        txn.setTxCode("txn-existing");
        txn.setCartId("cart-existing");
        txn.setStatus(TransactionStatusEnum.STARTED);
        txn.setUserCurrency(CurrencyEnum.USDT);
        txn.setExchangeRate(BID_PRICE);
        txn.setUserTotalAmount(new BigDecimal("14.37"));
        txn.setLocalTotalAmount(LOCAL_AMOUNT);
        txn.setExpiresAt(Instant.now().plusSeconds(300));
        txn.setPartner(partner);
        return txn;
    }
}
