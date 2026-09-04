package dowob.xyz.stockwebv2.marketdata.api;

import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.model.AssetType;
import dowob.xyz.stockwebv2.common.model.KlineInterval;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetFacade;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetSummary;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link KlineQueryService} 單元測試。
 *
 * <p>使用純 Mockito 驗證服務層邏輯，不啟動 Spring 容器：
 * <ul>
 *   <li>未知 symbol → 拋 ASSET_NOT_FOUND</li>
 *   <li>from 為 null → 拋 VALIDATION_FAILED</li>
 *   <li>to 在 from 之前 → 拋 VALIDATION_FAILED</li>
 *   <li>limit 為 null → 預設 500</li>
 *   <li>limit 超過上限 → 截斷至 5000</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0.0
 */
class KlineQueryServiceTest {

    AssetFacade assetFacade = mock(AssetFacade.class);
    JdbcClient jdbcClient = mock(JdbcClient.class);

    KlineQueryService service;

    static final AssetSummary AAPL =
        new AssetSummary(1L, "AAPL", "Apple Inc.", AssetType.STOCK, "NASDAQ", true, true);

    static final Instant FROM = Instant.parse("2026-01-01T10:00:00Z");
    static final Instant TO   = Instant.parse("2026-01-01T11:00:00Z");

    @BeforeEach
    void setup() {
        service = new KlineQueryService(assetFacade, jdbcClient);
    }

    // ── findKlines — 基本參數驗證 ──────────────────────────────────────────────

    @Test
    @DisplayName("findKlines: 未知 symbol → 拋 ASSET_NOT_FOUND")
    void findKlines_unknownSymbol_throwsAssetNotFound() {
        when(assetFacade.findBySymbol("UNKNOWN")).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
            service.findKlines("UNKNOWN", KlineInterval.ONE_MINUTE, FROM, TO, null))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> assertThat(((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.ASSET_NOT_FOUND));
    }

    @Test
    @DisplayName("findKlines: from 為 null → 拋 VALIDATION_FAILED")
    void findKlines_nullFrom_throwsValidationFailed() {
        when(assetFacade.findBySymbol("AAPL")).thenReturn(Optional.of(AAPL));

        assertThatThrownBy(() ->
            service.findKlines("AAPL", KlineInterval.ONE_MINUTE, null, TO, null))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> assertThat(((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("findKlines: to 在 from 之前（相等）→ 拋 VALIDATION_FAILED")
    void findKlines_toBeforeFrom_throwsValidationFailed() {
        when(assetFacade.findBySymbol("AAPL")).thenReturn(Optional.of(AAPL));

        // to == from → 不滿足 isAfter → 拋出例外
        assertThatThrownBy(() ->
            service.findKlines("AAPL", KlineInterval.ONE_MINUTE, FROM, FROM, null))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> assertThat(((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    // ── findKlines — limit 邊界 ────────────────────────────────────────────────

    @Test
    @DisplayName("findKlines: limit 為 null → 使用預設 500 並成功呼叫 JdbcClient")
    void findKlines_limitDefault500_appliedWhenNull() {
        when(assetFacade.findBySymbol("AAPL")).thenReturn(Optional.of(AAPL));

        // 建立 fluent stub chain：sql → param(×4) → query → list
        JdbcClient.StatementSpec statementSpec = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<KlineDto> mappedSpec = mock(JdbcClient.MappedQuerySpec.class);

        when(jdbcClient.sql(anyString())).thenReturn(statementSpec);
        when(statementSpec.param(anyString(), any())).thenReturn(statementSpec);
        when(statementSpec.query(any(org.springframework.jdbc.core.RowMapper.class))).thenReturn(mappedSpec);
        when(mappedSpec.list()).thenReturn(List.of());

        service.findKlines("AAPL", KlineInterval.ONE_MINUTE, FROM, TO, null);

        // 驗證 :limit 被傳入（值 = 500）
        verify(statementSpec).param("limit", 500);
    }

    @Test
    @DisplayName("findKlines: limit 超過 5000 → 截斷至 5000")
    void findKlines_limitOverMax_cappedAt5000() {
        when(assetFacade.findBySymbol("AAPL")).thenReturn(Optional.of(AAPL));

        JdbcClient.StatementSpec statementSpec = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<KlineDto> mappedSpec = mock(JdbcClient.MappedQuerySpec.class);

        when(jdbcClient.sql(anyString())).thenReturn(statementSpec);
        when(statementSpec.param(anyString(), any())).thenReturn(statementSpec);
        when(statementSpec.query(any(org.springframework.jdbc.core.RowMapper.class))).thenReturn(mappedSpec);
        when(mappedSpec.list()).thenReturn(List.of());

        service.findKlines("AAPL", KlineInterval.ONE_MINUTE, FROM, TO, 99999);

        // 驗證 :limit 被截斷至 MAX_LIMIT = 5000
        verify(statementSpec).param("limit", KlineQueryService.MAX_LIMIT);
    }
}
