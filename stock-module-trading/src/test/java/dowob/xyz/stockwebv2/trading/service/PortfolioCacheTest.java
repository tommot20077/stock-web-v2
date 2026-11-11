package dowob.xyz.stockwebv2.trading.service;

import dowob.xyz.stockwebv2.common.model.CurrencyCode;
import dowob.xyz.stockwebv2.trading.api.HoldingDto;
import dowob.xyz.stockwebv2.trading.api.PortfolioSummaryDto;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import tools.jackson.databind.ObjectMapper;

/**
 * {@link PortfolioCache} 的失效時機。
 *
 * <p>本檔存在的理由是一個實際的競態：{@code TradingService.createTrade} 帶 {@code @Transactional}，
 * 而快取失效原本在方法體內、也就是<strong>在 COMMIT 之前</strong>執行。於是：
 *
 * <ol>
 *   <li>下單交易刪掉快取，但尚未 commit；</li>
 *   <li>另一個請求讀快取 miss → 查 DB，讀到的是<strong>交易前</strong>的持倉；</li>
 *   <li>它把這份舊資料寫回快取，TTL 60 秒；</li>
 *   <li>下單交易 commit。</li>
 * </ol>
 *
 * <p>結果是成交後的 refetch 最長一分鐘看不到自己剛記錄的交易 —— 正好打中 Phase 4 的
 * post-trade refetch。失效必須排到 commit 之後。
 *
 * <p>04.2-05 起 summary 鍵升 {@code v3}（summary 新增 {@code dividendIncome} / {@code dividendTaxWithheld}、
 * {@code totalPnl} 含股利），持倉鍵維持 {@code v2}；原 v2 summary 斷言改為 v3，另鎖舊 v2 鍵不被讀、
 * 兩條失效路徑只刪 v3 summary 鍵。
 *
 * <p>2026-11-07（quick-261107-1j4 E1）起持倉鍵升 {@code v3}：{@link HoldingDto} 新增隨持倉快取的 {@code market}，
 * 舊 v2 JSON 缺此欄會被讀成 {@code market = null}；原 v2 持倉鍵斷言改為 v3，另鎖舊 v2 持倉鍵不被讀、
 * 快取 JSON 帶 {@code market} 往返不遺失。
 *
 * @author Yuan
 * @version 1.2.0
 */
@DisplayName("portfolio 快取失效時機")
class PortfolioCacheTest {

    private StringRedisTemplate redisTemplate;
    private PortfolioCache cache;

    @BeforeEach
    void setup() {
        redisTemplate = mock(StringRedisTemplate.class);
        cache = new PortfolioCache(redisTemplate, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("交易進行中不立刻刪快取，等 COMMIT 之後才刪")
    void defersInvalidationUntilAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();

        cache.invalidateAfterTrade(7L, 42L);

        verify(redisTemplate, never()).delete("portfolio:valuation:v4:7:42");
        for (CurrencyCode base : CurrencyCode.values()) {
            verify(redisTemplate, never()).delete("portfolio:summary:v4:7:" + base.name());
        }

        TransactionSynchronizationUtils.triggerAfterCommit();

        verify(redisTemplate).delete("portfolio:valuation:v4:7:42");
        verify(redisTemplate).delete("portfolio:summary:v4:7:USD");
        verify(redisTemplate).delete("portfolio:summary:v4:7:TWD");
        verify(redisTemplate).delete("portfolio:summary:v4:7:EUR");
        verify(redisTemplate).delete("portfolio:summary:v4:7:JPY");
    }

    @Test
    @DisplayName("沒有進行中的交易時立刻刪，不會靜默漏掉失效")
    void invalidatesImmediatelyWithoutTransaction() {
        cache.invalidateAfterTrade(7L, 42L);

        verify(redisTemplate).delete("portfolio:valuation:v4:7:42");
        for (CurrencyCode base : CurrencyCode.values()) {
            verify(redisTemplate).delete("portfolio:summary:v4:7:" + base.name());
        }
    }

    @Test
    @DisplayName("升 v3 後舊 v2 summary 鍵即使存在也不讀（缺 dividendIncome、totalPnl 語意舊，T-04.2-13）")
    @SuppressWarnings("unchecked")
    void legacyV2SummaryKeyIsNeverRead() {
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("portfolio:summary:v2:7:TWD")).thenReturn(
            "{\"totalMarketValue\":1,\"totalCostBasis\":1,\"realizedPnl\":0,\"unrealizedPnl\":0,\"totalPnl\":0,"
                + "\"roi\":0,\"holdingCount\":0,\"baseCurrency\":\"TWD\",\"fxExcludedCount\":0,\"dayPnlExcludedCount\":0}");
        when(valueOps.get("portfolio:summary:v4:7:TWD")).thenReturn(null);

        assertThat(cache.readSummary(7L, CurrencyCode.TWD)).isEmpty();
        verify(valueOps).get("portfolio:summary:v4:7:TWD");
        verify(valueOps, never()).get("portfolio:summary:v2:7:TWD");
    }

    @Test
    @DisplayName("股利寫入（含沖銷）只失效 summary：刪該使用者全部基準幣的 v3 summary 鍵，不刪任何 portfolio:valuation:* 持倉鍵")
    void summaryOnlyInvalidationDeletesAllV3SummaryKeysAndNoValuationKey() {
        cache.invalidateSummaryAfterCommit(7L);

        for (CurrencyCode base : CurrencyCode.values()) {
            verify(redisTemplate).delete("portfolio:summary:v4:7:" + base.name());
        }
        verify(redisTemplate, times(CurrencyCode.values().length)).delete(anyString());
        verify(redisTemplate, never()).delete(startsWith("portfolio:valuation:"));
        verify(redisTemplate, never()).delete(startsWith("portfolio:summary:v2:"));
    }

    @Test
    @DisplayName("交易失效：刪 portfolio:valuation:v4:{userId}:{assetId} 與全部 v3 summary 鍵，不刪舊 v2 鍵")
    void tradeInvalidationDeletesV3ValuationKeyAndAllV3SummaryKeys() {
        cache.invalidateAfterTrade(7L, 42L);

        verify(redisTemplate).delete("portfolio:valuation:v4:7:42");
        for (CurrencyCode base : CurrencyCode.values()) {
            verify(redisTemplate).delete("portfolio:summary:v4:7:" + base.name());
        }
        verify(redisTemplate, times(CurrencyCode.values().length + 1)).delete(anyString());
        verify(redisTemplate, never()).delete(startsWith("portfolio:summary:v2:"));
        verify(redisTemplate, never()).delete(startsWith("portfolio:valuation:v2:"));
    }

    @Test
    @DisplayName("summary 快取鍵含版本與基準幣：portfolio:summary:v4:{userId}:{baseCurrency}")
    @SuppressWarnings("unchecked")
    void summaryKeyIncludesVersionAndBaseCurrency() {
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("portfolio:summary:v4:7:TWD")).thenReturn(null);

        assertThat(cache.readSummary(7L, CurrencyCode.TWD)).isEmpty();
        verify(valueOps).get("portfolio:summary:v4:7:TWD");

        PortfolioSummaryDto dto = new PortfolioSummaryDto(
            BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            1, "TWD", 0, List.of(), BigDecimal.ZERO, null, 0, BigDecimal.ZERO, BigDecimal.ONE, List.of(), List.of(), List.of(),
            BigDecimal.ZERO, BigDecimal.ZERO);
        cache.writeSummary(7L, CurrencyCode.TWD, dto);

        verify(valueOps).set(eq("portfolio:summary:v4:7:TWD"), anyString(), eq(Duration.ofSeconds(60)));
    }

    @Test
    @DisplayName("批次讀取持倉快取：一次 MGET，只回傳命中的項目")
    @SuppressWarnings("unchecked")
    void readHoldingsUsesSingleMultiGet() {
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.multiGet(List.of("portfolio:valuation:v4:7:41", "portfolio:valuation:v4:7:42"))).thenReturn(Arrays.asList(
            null,
            "{\"assetId\":\"u\",\"symbol\":\"MSFT\",\"assetName\":\"MSFT\",\"totalQuantity\":5,\"avgCost\":200,"
                + "\"costBasis\":1000,\"marketPrice\":210,\"marketValue\":1050,\"realizedPnl\":0,\"unrealizedPnl\":50,"
                + "\"roi\":0.05,\"priceTime\":null,\"lastUpdated\":null}"));

        Map<Long, HoldingDto> cached = cache.readHoldings(7L, List.of(41L, 42L));

        assertThat(cached).containsOnlyKeys(42L);
        assertThat(cached.get(42L).symbol()).isEqualTo("MSFT");
        verify(valueOps, times(1)).multiGet(List.of("portfolio:valuation:v4:7:41", "portfolio:valuation:v4:7:42"));
    }

    @Test
    @DisplayName("升 v3 後舊 v2 持倉鍵即使存在也不讀（舊 JSON 缺 market，會被讀成 market = null）")
    @SuppressWarnings("unchecked")
    void legacyV2ValuationKeyIsNeverRead() {
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.multiGet(List.of("portfolio:valuation:v4:7:43"))).thenReturn(Arrays.asList((String) null));
        when(valueOps.multiGet(List.of("portfolio:valuation:v2:7:43"))).thenReturn(List.of(
            "{\"assetId\":\"u\",\"symbol\":\"2330.TW\",\"currency\":\"TWD\",\"assetType\":\"STOCK\"}"));

        assertThat(cache.readHoldings(7L, List.of(43L))).isEmpty();
        verify(valueOps).multiGet(List.of("portfolio:valuation:v4:7:43"));
        verify(valueOps, never()).multiGet(List.of("portfolio:valuation:v2:7:43"));
    }

    @Test
    @DisplayName("持倉快取 JSON 帶 market：寫入 v3 鍵後讀回，market 不遺失")
    @SuppressWarnings("unchecked")
    void holdingMarketSurvivesCacheRoundTrip() {
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        HoldingDto dto = new HoldingDto("u", "2330.TW", "TSMC",
            new BigDecimal("2"), new BigDecimal("900"), new BigDecimal("1800"), new BigDecimal("1000"),
            new BigDecimal("2000"), BigDecimal.ZERO, new BigDecimal("200"), new BigDecimal("0.11111111"),
            null, null, "TWD", "STOCK", "Tech", "TW", null, null, null, null, null, null, null);

        cache.writeHolding(7L, 43L, dto);

        org.mockito.ArgumentCaptor<String> json = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq("portfolio:valuation:v4:7:43"), json.capture(), eq(Duration.ofSeconds(60)));
        assertThat(json.getValue()).contains("\"market\":\"TW\"");
        when(valueOps.multiGet(List.of("portfolio:valuation:v4:7:43"))).thenReturn(List.of(json.getValue()));
        assertThat(cache.readHoldings(7L, List.of(43L)).get(43L).market()).isEqualTo("TW");
    }

    @Test
    @DisplayName("批次讀取持倉快取：Redis 失敗時回空，讓呼叫端全部重算而不是整頁失敗")
    @SuppressWarnings("unchecked")
    void readHoldingsReturnsEmptyWhenRedisFails() {
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.multiGet(org.mockito.ArgumentMatchers.anyList())).thenThrow(new IllegalStateException("redis down"));

        assertThat(cache.readHoldings(7L, List.of(41L))).isEmpty();
    }

    @Test
    @DisplayName("入出金：交易進行中不立刻刪 summary，COMMIT 之後只刪四把 summary 鍵、不刪任何持倉鍵")
    void summaryInvalidationIsDeferredUntilAfterCommitAndSparesHoldings() {
        TransactionSynchronizationManager.initSynchronization();

        cache.invalidateSummaryAfterCommit(7L);

        for (CurrencyCode base : CurrencyCode.values()) {
            verify(redisTemplate, never()).delete("portfolio:summary:v4:7:" + base.name());
        }

        TransactionSynchronizationUtils.triggerAfterCommit();

        verify(redisTemplate).delete("portfolio:summary:v4:7:USD");
        verify(redisTemplate).delete("portfolio:summary:v4:7:TWD");
        verify(redisTemplate).delete("portfolio:summary:v4:7:EUR");
        verify(redisTemplate).delete("portfolio:summary:v4:7:JPY");
        // 分錄不影響持倉估值：持倉鍵一把都不能刪
        verify(redisTemplate, times(CurrencyCode.values().length)).delete(anyString());
        verify(redisTemplate, never()).delete(startsWith("portfolio:valuation:"));
    }

    @Test
    @DisplayName("入出金：沒有進行中的交易時立刻刪四把 summary 鍵，不會靜默漏掉失效")
    void summaryInvalidationRunsImmediatelyWithoutTransaction() {
        cache.invalidateSummaryAfterCommit(7L);

        for (CurrencyCode base : CurrencyCode.values()) {
            verify(redisTemplate).delete("portfolio:summary:v4:7:" + base.name());
        }
        verify(redisTemplate, never()).delete(startsWith("portfolio:valuation:"));
    }

    @Test
    @DisplayName("入出金：交易回滾時不刪快取（資料沒變，不該丟掉有效的快取）")
    void summaryInvalidationIsSkippedOnRollback() {
        TransactionSynchronizationManager.initSynchronization();

        cache.invalidateSummaryAfterCommit(7L);
        TransactionSynchronizationUtils.invokeAfterCompletion(
            TransactionSynchronizationManager.getSynchronizations(),
            org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(redisTemplate, never()).delete(anyString());
    }
}
