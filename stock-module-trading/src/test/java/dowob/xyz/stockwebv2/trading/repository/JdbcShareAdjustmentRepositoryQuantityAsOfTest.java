package dowob.xyz.stockwebv2.trading.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link JdbcShareAdjustmentRepository#quantityAsOf} 的切點語意單元測試（Yuan 2026-11-04 裁決「同日依記錄順序疊加」）。
 *
 * <p>新調整的 {@code Q_asOf} = 交易 {@code executed_at <} 生效瞬間 + 既有調整 {@code effective_at <=} 生效瞬間
 * （沖銷列反號）。調整那一段必須含「同一瞬間、已先記錄」的調整：例如 100 股同一除權日記兩次 2:1 拆股，
 * 第二筆的 {@code Q_asOf} 要是 200（含第一筆的 +100），結果才會是 400 股而不是 300。
 * 交易那一段維持嚴格小於：除權日起點（含）之後的成交是除權後的股數，不參與換算。</p>
 *
 * <p><strong>能力界線</strong>：單元測試沒有資料庫，這裡只能鎖住送進 {@link JdbcClient} 的兩段 SQL 的切點運算子、
 * 綁定的參數與加總；SQL 在 PostgreSQL 上的實際行為由 {@code ShareAdjustmentApiIT}（CI Integration job）驗收。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("股數調整帳本：Q_asOf 切點語意")
class JdbcShareAdjustmentRepositoryQuantityAsOfTest {

    private static final OffsetDateTime EFFECTIVE_AT = OffsetDateTime.parse("2026-10-30T04:00:00Z");

    @Test
    @DisplayName("交易段 executed_at < 生效瞬間；調整段 effective_at <= 生效瞬間（含同日先記錄者）；回傳兩段相加")
    @SuppressWarnings("unchecked")
    void quantityAsOfIncludesSameInstantAdjustmentsButNotSameInstantTrades() {
        JdbcClient jdbcClient = mock(JdbcClient.class);
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<BigDecimal> mapped = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbcClient.sql(anyString())).thenReturn(spec);
        when(spec.param(anyString(), any())).thenReturn(spec);
        when(spec.query(BigDecimal.class)).thenReturn(mapped);
        /* 100 股於除權日前買入；同日已先記一筆 2:1 拆股（+100）。 */
        when(mapped.single()).thenReturn(new BigDecimal("100"), new BigDecimal("100"));

        BigDecimal quantityAsOf = new JdbcShareAdjustmentRepository(jdbcClient).quantityAsOf(7L, 1L, EFFECTIVE_AT);

        assertThat(quantityAsOf).isEqualByComparingTo("200");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcClient, times(2)).sql(sql.capture());
        List<String> statements = sql.getAllValues().stream().map(s -> s.replaceAll("\\s+", " ")).toList();
        String traded = statements.stream().filter(s -> s.contains("from transactions")).findFirst().orElseThrow();
        String adjusted = statements.stream().filter(s -> s.contains("from share_adjustments")).findFirst().orElseThrow();
        assertThat(adjusted).contains("effective_at <= :effectiveAt");
        assertThat(traded).contains("executed_at < :effectiveAt").doesNotContain("<=");
        /* 沖銷列仍須反號：同一瞬間的「原列 + 沖銷列」淨額為 0。 */
        assertThat(adjusted).contains("when reversal_of_id is null then quantity_delta")
            .contains("else -quantity_delta");
        verify(spec, times(2)).param("effectiveAt", EFFECTIVE_AT);
    }
}
