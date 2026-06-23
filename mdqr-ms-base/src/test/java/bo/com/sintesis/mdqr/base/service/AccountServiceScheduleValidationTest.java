package bo.com.sintesis.mdqr.base.service;

import bo.com.sintesis.mdqr.base.integration.genesis.GenesisService;
import bo.com.sintesis.mdqr.base.integration.genesis.dto.ProviderScheduleResponse;
import bo.com.sintesis.mdqr.base.security.PartnerContext;
import bo.com.sintesis.mdqr.base.service.dto.AccountSearchResponse;
import bo.com.sintesis.mdqr.base.web.rest.errors.OutsideOperatingHoursException;
import bo.com.sintesis.mdqr.base.web.rest.errors.OutsideOperatingHoursException.OperatingWindow;
import bo.com.sintesis.mdqr.base.web.rest.errors.ProviderDisabledException;
import bo.com.sintesis.mdqr.base.web.rest.request.AccountSearchRequest;
import bo.com.sintesis.sdk.intraplatinum.model.request.IpcLoginRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Schedule pre-check on AccountService.searchAccount.
 * Schedule comes from Genesis in Bolivia local time (UTC-4); evaluation happens in that zone
 * but the exception payload exposes UTC ISO 8601 windows.
 *
 * 2026-04-27 is a Monday — used as the reference date in these tests.
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceScheduleValidationTest {

    private static final Integer PROVIDER_ID = 42;
    private static final String PARTNER_ID = "partner-001";

    @Mock private GenesisService genesisService;
    @Mock private VaultPartnerService vaultPartnerService;
    @Mock private ExchangeRateService exchangeRateService;
    @Mock private PartnerContext partnerContext;

    @InjectMocks
    private AccountService accountService;

    private MockedStatic<Instant> instantMock;

    private AccountSearchRequest request;

    @BeforeEach
    void setUp() {
        request = new AccountSearchRequest(PROVIDER_ID, 1, List.of());
    }

    @AfterEach
    void tearDown() {
        if (instantMock != null) instantMock.close();
    }

    private void freezeNow(String isoUtc) {
        Instant fixed = Instant.parse(isoUtc);
        instantMock = mockStatic(Instant.class, Mockito.CALLS_REAL_METHODS);
        instantMock.when(Instant::now).thenReturn(fixed);
    }

    private void stubGenesisSearchOk() {
        when(partnerContext.getCurrentPartnerId()).thenReturn(PARTNER_ID);
        when(vaultPartnerService.getGenesisCredentials(PARTNER_ID)).thenReturn(new IpcLoginRequest());
        when(genesisService.searchAccount(any(IpcLoginRequest.class), any(AccountSearchRequest.class)))
            .thenReturn(new AccountSearchResponse(0, "20260427", List.of()));
    }

    private ProviderScheduleResponse fullWeek247() {
        return new ProviderScheduleResponse(true, List.of(
            "Lunes    : 00:00:00 - 24:00:00",
            "Martes   : 00:00:00 - 24:00:00",
            "Miercoles: 00:00:00 - 24:00:00",
            "Jueves   : 00:00:00 - 24:00:00",
            "Viernes  : 00:00:00 - 24:00:00",
            "Sabado   : 00:00:00 - 24:00:00",
            "Domingo  : 00:00:00 - 24:00:00"
        ));
    }

    @Test
    @DisplayName("habilitado=N throws ProviderDisabledException without calling Genesis searchAccount")
    void providerDisabled_throws() {
        freezeNow("2026-04-27T14:00:00Z");
        when(genesisService.getProviderSchedule(PROVIDER_ID))
            .thenReturn(new ProviderScheduleResponse(false, List.of()));

        assertThatThrownBy(() -> accountService.searchAccount(request))
            .isInstanceOf(ProviderDisabledException.class)
            .extracting("providerId").isEqualTo(PROVIDER_ID);

        verify(genesisService, never()).searchAccount(any(IpcLoginRequest.class), any(AccountSearchRequest.class));
    }

    @Test
    @DisplayName("24/7 schedule and any current time → searchAccount is invoked")
    void schedule24x7_passesValidation() {
        freezeNow("2026-04-27T14:00:00Z"); // Monday 10:00 Bolivia
        when(genesisService.getProviderSchedule(PROVIDER_ID)).thenReturn(fullWeek247());
        stubGenesisSearchOk();

        AccountSearchResponse actual = accountService.searchAccount(request);

        assertThat(actual.operationDate()).isEqualTo("20260427");
    }

    @Test
    @DisplayName("Current time inside today's window (Mon 10:00 against Mon 08-18) → passes")
    void insideWindow_passes() {
        freezeNow("2026-04-27T14:00:00Z"); // Monday 10:00 Bolivia
        when(genesisService.getProviderSchedule(PROVIDER_ID))
            .thenReturn(new ProviderScheduleResponse(true, List.of("Lunes: 08:00:00 - 18:00:00")));
        stubGenesisSearchOk();

        accountService.searchAccount(request);

        verify(genesisService).searchAccount(any(IpcLoginRequest.class), any(AccountSearchRequest.class));
    }

    @Test
    @DisplayName("Current time after today's close (Mon 19:00 against Mon 08-18) → throws with next-week window")
    void outsideWindow_afterClose_throws() {
        freezeNow("2026-04-27T23:00:00Z"); // Monday 19:00 Bolivia
        when(genesisService.getProviderSchedule(PROVIDER_ID))
            .thenReturn(new ProviderScheduleResponse(true, List.of("Lunes: 08:00:00 - 18:00:00")));

        OutsideOperatingHoursException ex = catchThrowableOfType(
            () -> accountService.searchAccount(request),
            OutsideOperatingHoursException.class
        );

        assertThat(ex).isNotNull();
        assertThat(ex.getProviderId()).isEqualTo(PROVIDER_ID);
        assertThat(ex.getOperatingHoursUtc()).hasSize(1);
        OperatingWindow w = ex.getOperatingHoursUtc().get(0);
        assertThat(w.open()).isEqualTo(Instant.parse("2026-05-04T12:00:00Z"));
        assertThat(w.close()).isEqualTo(Instant.parse("2026-05-04T22:00:00Z"));

        verify(genesisService, never()).searchAccount(any(IpcLoginRequest.class), any(AccountSearchRequest.class));
    }

    @Test
    @DisplayName("End boundary is exclusive — exactly 18:00:00 is outside")
    void boundaryEndExclusive_throws() {
        freezeNow("2026-04-27T22:00:00Z"); // Monday 18:00 Bolivia exactly
        when(genesisService.getProviderSchedule(PROVIDER_ID))
            .thenReturn(new ProviderScheduleResponse(true, List.of("Lunes: 08:00:00 - 18:00:00")));

        assertThatThrownBy(() -> accountService.searchAccount(request))
            .isInstanceOf(OutsideOperatingHoursException.class);
    }

    @Test
    @DisplayName("Start boundary is inclusive — exactly 08:00:00 is inside")
    void boundaryStartInclusive_passes() {
        freezeNow("2026-04-27T12:00:00Z"); // Monday 08:00 Bolivia exactly
        when(genesisService.getProviderSchedule(PROVIDER_ID))
            .thenReturn(new ProviderScheduleResponse(true, List.of("Lunes: 08:00:00 - 18:00:00")));
        stubGenesisSearchOk();

        accountService.searchAccount(request);

        verify(genesisService).searchAccount(any(IpcLoginRequest.class), any(AccountSearchRequest.class));
    }

    @Test
    @DisplayName("End at 24:00:00 covers up to midnight — Mon 23:59 Bolivia passes")
    void endOfDay24_treatedAsMidnight() {
        freezeNow("2026-04-28T03:59:00Z"); // Monday 23:59 Bolivia
        when(genesisService.getProviderSchedule(PROVIDER_ID))
            .thenReturn(new ProviderScheduleResponse(true, List.of("Lunes: 00:00:00 - 24:00:00")));
        stubGenesisSearchOk();

        accountService.searchAccount(request);

        verify(genesisService).searchAccount(any(IpcLoginRequest.class), any(AccountSearchRequest.class));
    }

    @Test
    @DisplayName("Current day not present in schedule → throws and lists upcoming windows in order")
    void dayNotInSchedule_throws() {
        freezeNow("2026-04-26T16:00:00Z"); // Sunday 12:00 Bolivia
        when(genesisService.getProviderSchedule(PROVIDER_ID))
            .thenReturn(new ProviderScheduleResponse(true, List.of(
                "Lunes  : 08:00:00 - 18:00:00",
                "Martes : 08:00:00 - 18:00:00"
            )));

        OutsideOperatingHoursException ex = catchThrowableOfType(
            () -> accountService.searchAccount(request),
            OutsideOperatingHoursException.class
        );

        assertThat(ex).isNotNull();
        assertThat(ex.getOperatingHoursUtc()).hasSize(2);
        assertThat(ex.getOperatingHoursUtc().get(0).open())
            .isEqualTo(Instant.parse("2026-04-27T12:00:00Z"));
        assertThat(ex.getOperatingHoursUtc().get(1).open())
            .isEqualTo(Instant.parse("2026-04-28T12:00:00Z"));
    }

    @Test
    @DisplayName("Accented day names (Miércoles/Sábado) are recognized")
    void accentedDayNames_recognized() {
        freezeNow("2026-04-29T14:00:00Z"); // Wednesday 10:00 Bolivia
        when(genesisService.getProviderSchedule(PROVIDER_ID))
            .thenReturn(new ProviderScheduleResponse(true, List.of(
                "Miércoles: 08:00:00 - 18:00:00",
                "Sábado   : 09:00:00 - 13:00:00"
            )));
        stubGenesisSearchOk();

        accountService.searchAccount(request);

        verify(genesisService).searchAccount(any(IpcLoginRequest.class), any(AccountSearchRequest.class));
    }

    @Test
    @DisplayName("Malformed entries are skipped; valid ones still drive the decision")
    void malformedEntries_skipped() {
        freezeNow("2026-04-27T14:00:00Z"); // Monday 10:00 Bolivia
        when(genesisService.getProviderSchedule(PROVIDER_ID))
            .thenReturn(new ProviderScheduleResponse(true, List.of(
                "garbage data",
                "Foo : 08:00:00 - 18:00:00",
                "Lunes: 08:00:00 - 18:00:00"
            )));
        stubGenesisSearchOk();

        accountService.searchAccount(request);

        verify(genesisService).searchAccount(any(IpcLoginRequest.class), any(AccountSearchRequest.class));
    }

    @Test
    @DisplayName("All entries malformed → fail closed (throws with empty windows)")
    void allEntriesMalformed_failsClosed() {
        freezeNow("2026-04-27T14:00:00Z");
        when(genesisService.getProviderSchedule(PROVIDER_ID))
            .thenReturn(new ProviderScheduleResponse(true, List.of("garbage", "more garbage")));

        OutsideOperatingHoursException ex = catchThrowableOfType(
            () -> accountService.searchAccount(request),
            OutsideOperatingHoursException.class
        );

        assertThat(ex).isNotNull();
        assertThat(ex.getOperatingHoursUtc()).isEmpty();

        verify(genesisService, never()).searchAccount(any(IpcLoginRequest.class), any(AccountSearchRequest.class));
    }

    @Test
    @DisplayName("Today's window not yet open → returned window points to today")
    void todayNotYetOpen_returnsTodayWindow() {
        freezeNow("2026-04-27T10:00:00Z"); // Monday 06:00 Bolivia
        when(genesisService.getProviderSchedule(PROVIDER_ID))
            .thenReturn(new ProviderScheduleResponse(true, List.of("Lunes: 08:00:00 - 18:00:00")));

        OutsideOperatingHoursException ex = catchThrowableOfType(
            () -> accountService.searchAccount(request),
            OutsideOperatingHoursException.class
        );

        assertThat(ex).isNotNull();
        assertThat(ex.getOperatingHoursUtc()).hasSize(1);
        OperatingWindow w = ex.getOperatingHoursUtc().get(0);
        assertThat(w.open()).isEqualTo(Instant.parse("2026-04-27T12:00:00Z"));
        assertThat(w.close()).isEqualTo(Instant.parse("2026-04-27T22:00:00Z"));
    }
}
