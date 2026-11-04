package dowob.xyz.stockwebv2.trading.service;

import dowob.xyz.stockwebv2.common.api.PageResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.error.FieldValidationException;
import dowob.xyz.stockwebv2.common.model.AssetType;
import dowob.xyz.stockwebv2.common.model.CurrencyCode;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetFacade;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetProfile;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetSummary;
import dowob.xyz.stockwebv2.trading.api.PortfolioActivityDto;
import dowob.xyz.stockwebv2.trading.domain.ActivityKind;
import dowob.xyz.stockwebv2.trading.domain.ActivitySortKey;
import dowob.xyz.stockwebv2.trading.domain.ActivityTypeFilter;
import dowob.xyz.stockwebv2.trading.domain.SortDirection;
import dowob.xyz.stockwebv2.trading.repository.ActivityRepository;
import dowob.xyz.stockwebv2.trading.repository.ActivityRepository.ActivityQuery;
import dowob.xyz.stockwebv2.trading.repository.ActivityRepository.ActivityRow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ActivityQueryService} 的單元測試（PORT-13 tracer）。
 *
 * <p>鎖三件事：symbol / currency 經<strong>一次</strong> {@link AssetFacade#findProfiles} 以 distinct assetId 取得
 * （trading 不 JOIN 資產主檔，D-21）；空頁不呼叫 Facade；repository 輸出的各 kind 欄位原樣進 DTO
 * （金額由 SQL 計算、不適用欄位為 null、沖銷欄位轉成字串 UUID）。count 與 list 必須在同一個
 * 可重複讀的唯讀交易內（D-12）。</p>
 *
 * <p>04.2-10 Task 2 補上參數解析：type / sort / direction 白名單外一律 400 且附 fields、零 I/O 驗證先於 symbol 解析、
 * 日期與 symbol 沿用 {@code GET /trades} 的 helper 與錯誤語意、page / size 夾限。</p>
 *
 * @author Yuan
 * @version 1.1
 */
@DisplayName("統一活動列表查詢服務")
class ActivityQueryServiceTest {

    private static final long USER_ID = 42L;
    private static final long AAPL = 1L;
    private static final long TSMC = 2L;
    private static final long TSLA = 3L;

    private ActivityRepository repository;
    private AssetFacade assetFacade;
    private ActivityQueryService service;

    @BeforeEach
    void setUp() {
        repository = mock(ActivityRepository.class);
        assetFacade = mock(AssetFacade.class);
        service = new ActivityQueryService(repository, assetFacade);
        when(assetFacade.findProfiles(anyCollection())).thenReturn(Map.of(
            AAPL, new AssetProfile(AAPL, "AAPL", AssetType.STOCK, "US", CurrencyCode.USD, "Tech"),
            TSMC, new AssetProfile(TSMC, "2330", AssetType.STOCK, "TW", CurrencyCode.TWD, "Semis"),
            TSLA, new AssetProfile(TSLA, "TSLA", AssetType.STOCK, "US", CurrencyCode.USD, "Auto")
        ));
    }

    @Test
    @DisplayName("三種事件各一列（不同標的）→ findProfiles 恰呼叫一次、參數為三個 distinct id；symbol / currency 取自 profile、kind 原樣")
    void resolvesProfilesOnceForDistinctAssets() {
        when(repository.count(eq(USER_ID), any())).thenReturn(3L);
        when(repository.list(eq(USER_ID), any())).thenReturn(List.of(splitRow(TSLA), buyRow(AAPL), dividendRow(TSMC)));

        PageResponse<PortfolioActivityDto> page = service.list(USER_ID, null, null, null, null, null, null, 0, 20);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(assetFacade, times(1)).findProfiles(ids.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrder(AAPL, TSMC, TSLA);

        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.items()).extracting(PortfolioActivityDto::kind)
            .containsExactly("SPLIT", "BUY", "CASH_DIVIDEND");
        assertThat(page.items()).extracting(PortfolioActivityDto::symbol)
            .containsExactly("TSLA", "AAPL", "2330");
        assertThat(page.items()).extracting(PortfolioActivityDto::currency)
            .containsExactly("USD", "USD", "TWD");
    }

    @Test
    @DisplayName("repository 回 0 列 → 不呼叫 findProfiles、回空頁（total 0）")
    void emptyPageSkipsFacade() {
        when(repository.count(eq(USER_ID), any())).thenReturn(0L);
        when(repository.list(eq(USER_ID), any())).thenReturn(List.of());

        PageResponse<PortfolioActivityDto> page = service.list(USER_ID, null, null, null, null, null, null, 0, 20);

        verify(assetFacade, never()).findProfiles(anyCollection());
        assertThat(page.items()).isEmpty();
        assertThat(page.totalElements()).isZero();
    }

    @Test
    @DisplayName("各 kind 欄位原樣進 DTO：BUY 的 amount 為 SQL 算好的 quantity × price、股利 quantity null、調整 amount null")
    void fieldApplicabilityIsPassedThrough() {
        when(repository.count(eq(USER_ID), any())).thenReturn(3L);
        when(repository.list(eq(USER_ID), any())).thenReturn(List.of(buyRow(AAPL), dividendRow(TSMC), splitRow(TSLA)));

        List<PortfolioActivityDto> items = service.list(USER_ID, null, null, null, null, null, null, 0, 20).items();

        PortfolioActivityDto buy = items.get(0);
        assertThat(buy.amount()).isEqualByComparingTo("1500");
        assertThat(buy.quantity()).isEqualByComparingTo("10");
        assertThat(buy.price()).isEqualByComparingTo("150");
        assertThat(buy.fee()).isEqualByComparingTo("1");
        assertThat(buy.withholdingTax()).isNull();
        assertThat(buy.effectiveDate()).isNull();
        assertThat(buy.reversed()).isFalse();
        assertThat(buy.reversible()).isFalse();

        PortfolioActivityDto dividend = items.get(1);
        assertThat(dividend.quantity()).isNull();
        assertThat(dividend.price()).isNull();
        assertThat(dividend.amount()).isEqualByComparingTo("120");
        assertThat(dividend.withholdingTax()).isEqualByComparingTo("30");
        assertThat(dividend.reversible()).isTrue();

        PortfolioActivityDto split = items.get(2);
        assertThat(split.amount()).isNull();
        assertThat(split.quantity()).isEqualByComparingTo("300");
        assertThat(split.ratioNew()).isEqualTo(4);
        assertThat(split.ratioOld()).isEqualTo(1);
        assertThat(split.effectiveDate()).isEqualTo(LocalDate.parse("2026-10-30"));
    }

    @Test
    @DisplayName("沖銷列：reversalOfId 轉成 UUID 字串、reversalOfOccurredAt / reversalOfEffectiveDate 原樣")
    void reversalColumnsAreMapped() {
        UUID originalId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        ActivityRow reversal = new ActivityRow(
            UUID.fromString("33333333-3333-3333-3333-333333333333"), ActivityKind.SPLIT, TSLA,
            OffsetDateTime.parse("2026-10-30T04:00:00Z"), LocalDate.parse("2026-10-30"),
            OffsetDateTime.parse("2026-11-01T00:00:00Z"), new BigDecimal("300"), null, null, null, null, 4, 1, null,
            originalId, OffsetDateTime.parse("2026-10-30T04:00:00Z"), LocalDate.parse("2026-10-30"), false, false);
        when(repository.count(eq(USER_ID), any())).thenReturn(1L);
        when(repository.list(eq(USER_ID), any())).thenReturn(List.of(reversal));

        PortfolioActivityDto dto = service.list(USER_ID, null, null, null, null, null, null, 0, 20).items().get(0);

        assertThat(dto.id()).isEqualTo("33333333-3333-3333-3333-333333333333");
        assertThat(dto.reversalOfId()).isEqualTo(originalId.toString());
        assertThat(dto.reversalOfOccurredAt()).isEqualTo(OffsetDateTime.parse("2026-10-30T04:00:00Z"));
        assertThat(dto.reversalOfEffectiveDate()).isEqualTo(LocalDate.parse("2026-10-30"));
    }

    @Test
    @DisplayName("資產屬性缺失 → symbol / currency 為 null，不拋例外")
    void missingProfileYieldsNullSymbolAndCurrency() {
        when(repository.count(eq(USER_ID), any())).thenReturn(1L);
        when(repository.list(eq(USER_ID), any())).thenReturn(List.of(buyRow(99L)));

        PortfolioActivityDto dto = service.list(USER_ID, null, null, null, null, null, null, 0, 20).items().get(0);

        assertThat(dto.symbol()).isNull();
        assertThat(dto.currency()).isNull();
        assertThat(dto.kind()).isEqualTo("BUY");
    }

    @Test
    @DisplayName("不帶任何參數 → 查詢為全部種類、occurredAt desc、第 0 頁 20 筆；count 與 list 用同一個查詢")
    void defaultsAreAppliedWhenNoParameters() {
        when(repository.count(eq(USER_ID), any())).thenReturn(0L);
        when(repository.list(eq(USER_ID), any())).thenReturn(List.of());

        service.list(USER_ID, null, null, null, null, null, null, 0, 20);

        ArgumentCaptor<ActivityQuery> listQuery = ArgumentCaptor.forClass(ActivityQuery.class);
        ArgumentCaptor<ActivityQuery> countQuery = ArgumentCaptor.forClass(ActivityQuery.class);
        verify(repository).list(eq(USER_ID), listQuery.capture());
        verify(repository).count(eq(USER_ID), countQuery.capture());
        ActivityQuery query = listQuery.getValue();
        assertThat(query.type()).isEmpty();
        assertThat(query.assetIdOrNull()).isNull();
        assertThat(query.from()).isNull();
        assertThat(query.to()).isNull();
        assertThat(query.sort()).isEqualTo(ActivitySortKey.EXECUTED_AT);
        assertThat(query.direction()).isEqualTo(SortDirection.DESC);
        assertThat(query.page()).isZero();
        assertThat(query.size()).isEqualTo(20);
        assertThat(countQuery.getValue()).isEqualTo(query);
    }

    @Test
    @DisplayName("list 標記為可重複讀的唯讀交易（count 與 list 共用快照，D-12）")
    void listRunsInRepeatableReadReadOnlyTransaction() throws NoSuchMethodException {
        Method list = ActivityQueryService.class.getMethod("list", Long.class, String.class, String.class,
            String.class, String.class, String.class, String.class, int.class, int.class);

        Transactional transactional = list.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
        assertThat(transactional.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }

    @Test
    @DisplayName("篩選與排序參數解析後交給 repository：type / symbol（正規化大寫）/ 日期半開區間 / sort / direction；count 與 list 同一查詢")
    void parsedFiltersArePassedToRepository() {
        when(assetFacade.findBySymbol("AAPL")).thenReturn(Optional.of(
            new AssetSummary(AAPL, "AAPL", "Apple Inc.", AssetType.STOCK, "US", true, true)));
        when(repository.count(eq(USER_ID), any())).thenReturn(0L);
        when(repository.list(eq(USER_ID), any())).thenReturn(List.of());

        service.list(USER_ID, "dividend", " aapl ", "2026-10-01", "2026-10-31", "total", "asc", 2, 5);

        ArgumentCaptor<ActivityQuery> listQuery = ArgumentCaptor.forClass(ActivityQuery.class);
        ArgumentCaptor<ActivityQuery> countQuery = ArgumentCaptor.forClass(ActivityQuery.class);
        verify(repository).list(eq(USER_ID), listQuery.capture());
        verify(repository).count(eq(USER_ID), countQuery.capture());
        ActivityQuery query = listQuery.getValue();
        assertThat(query.type()).contains(ActivityTypeFilter.DIVIDEND);
        assertThat(query.assetIdOrNull()).isEqualTo(AAPL);
        assertThat(query.from()).isEqualTo(OffsetDateTime.parse("2026-10-01T00:00:00Z"));
        /* 純日期的上界涵蓋當日整天：解析為隔日 00:00（與 GET /trades 同一個 helper）。 */
        assertThat(query.to()).isEqualTo(OffsetDateTime.parse("2026-11-01T00:00:00Z"));
        assertThat(query.sort()).isEqualTo(ActivitySortKey.TOTAL);
        assertThat(query.direction()).isEqualTo(SortDirection.ASC);
        assertThat(query.page()).isEqualTo(2);
        assertThat(query.size()).isEqualTo(5);
        assertThat(countQuery.getValue()).isEqualTo(query);
    }

    @Test
    @DisplayName("page / size 超出範圍 → 夾限至 0..10000 / 1..100（同 GET /trades）")
    void pageAndSizeAreClamped() {
        when(repository.count(eq(USER_ID), any())).thenReturn(0L);
        when(repository.list(eq(USER_ID), any())).thenReturn(List.of());

        PageResponse<PortfolioActivityDto> page = service.list(USER_ID, null, null, null, null, null, null, -1, 500);

        ArgumentCaptor<ActivityQuery> query = ArgumentCaptor.forClass(ActivityQuery.class);
        verify(repository).list(eq(USER_ID), query.capture());
        assertThat(query.getValue().page()).isZero();
        assertThat(query.getValue().size()).isEqualTo(100);
        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(100);
    }

    @Test
    @DisplayName("type 白名單外 → 400 fields.type，不碰 repository 與 Facade")
    void unknownTypeIsRejectedBeforeAnyIo() {
        assertThatThrownBy(() -> service.list(USER_ID, "FOO", "AAPL", null, null, null, null, 0, 20))
            .isInstanceOf(FieldValidationException.class)
            .satisfies(error -> assertThat(((BusinessException) error).fields()).containsOnlyKeys("type"));

        verifyNoInteractions(repository, assetFacade);
    }

    @Test
    @DisplayName("sort 白名單外 → 400 fields.sort；direction 白名單外 → 400 fields.direction")
    void unknownSortOrDirectionIsRejected() {
        assertThatThrownBy(() -> service.list(USER_ID, null, null, null, null, "price", null, 0, 20))
            .isInstanceOf(FieldValidationException.class)
            .satisfies(error -> assertThat(((BusinessException) error).fields()).containsOnlyKeys("sort"));
        assertThatThrownBy(() -> service.list(USER_ID, null, null, null, null, null, "up", 0, 20))
            .isInstanceOf(FieldValidationException.class)
            .satisfies(error -> assertThat(((BusinessException) error).fields()).containsOnlyKeys("direction"));

        verifyNoInteractions(repository, assetFacade);
    }

    @Test
    @DisplayName("日期格式非法或 dateFrom 晚於 dateTo → VALIDATION_FAILED（同 GET /trades），不碰 repository")
    void invalidDateRangeIsRejected() {
        assertThatThrownBy(() -> service.list(USER_ID, null, null, "yesterday", null, null, null, 0, 20))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.list(USER_ID, null, null, "2026-10-31", "2026-10-01", null, null, 0, 20))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("symbol 查無 → 404 ASSET_NOT_FOUND fields.symbol（同 GET /trades 現況），不碰 repository")
    void unknownSymbolIsRejectedLikeTrades() {
        when(assetFacade.findBySymbol("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.list(USER_ID, null, "nope", null, null, null, null, 0, 20))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> {
                BusinessException business = (BusinessException) error;
                assertThat(business.errorCode()).isEqualTo(ErrorCode.ASSET_NOT_FOUND);
                assertThat(business.fields()).containsOnlyKeys("symbol");
            });

        verifyNoInteractions(repository);
    }

    private static ActivityRow buyRow(long assetId) {
        return new ActivityRow(
            UUID.fromString("11111111-1111-1111-1111-111111111111"), ActivityKind.BUY, assetId,
            OffsetDateTime.parse("2026-10-28T15:00:00Z"), null, OffsetDateTime.parse("2026-10-28T15:00:01Z"),
            new BigDecimal("10"), new BigDecimal("150"), new BigDecimal("1"), new BigDecimal("1500.0000000000000000"),
            null, null, null, "buy", null, null, null, false, false);
    }

    private static ActivityRow dividendRow(long assetId) {
        return new ActivityRow(
            UUID.fromString("44444444-4444-4444-4444-444444444444"), ActivityKind.CASH_DIVIDEND, assetId,
            OffsetDateTime.parse("2026-10-29T01:00:00Z"), null, OffsetDateTime.parse("2026-10-29T01:00:01Z"),
            null, null, null, new BigDecimal("120"), new BigDecimal("30"), null, null, null,
            null, null, null, false, true);
    }

    private static ActivityRow splitRow(long assetId) {
        return new ActivityRow(
            UUID.fromString("55555555-5555-5555-5555-555555555555"), ActivityKind.SPLIT, assetId,
            OffsetDateTime.parse("2026-10-30T04:00:00Z"), LocalDate.parse("2026-10-30"),
            OffsetDateTime.parse("2026-10-31T00:00:00Z"), new BigDecimal("300"), null, null, null, null, 4, 1, null,
            null, null, null, false, true);
    }
}
