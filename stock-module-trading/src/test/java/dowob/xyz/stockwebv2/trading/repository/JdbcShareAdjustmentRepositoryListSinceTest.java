package dowob.xyz.stockwebv2.trading.repository;

import dowob.xyz.stockwebv2.trading.repository.ShareAdjustmentRepository.DayAdjustment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link JdbcShareAdjustmentRepository#listSince} 的 SQL 形狀與列對應單元測試（ADJ-02 / D-10）。
 *
 * <p>今日損益只需要「某瞬間之後生效」的股數調整，且必須<strong>只讀本人</strong>的列（T-04.2-21）；
 * 沖銷列以反號回傳，同日記錄又沖銷時淨額為 0。</p>
 *
 * <p><strong>能力界線</strong>：單元測試沒有資料庫，這裡只鎖住送進 {@link JdbcClient} 的 SQL、綁定的參數與列對應；
 * SQL 在 PostgreSQL 上的實際行為由 {@code PortfolioDayPnlIT}（CI Integration job）驗收。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("股數調整帳本：今日損益用的生效調整查詢")
class JdbcShareAdjustmentRepositoryListSinceTest {

    private static final OffsetDateTime SINCE = OffsetDateTime.parse("2026-09-04T04:00:00Z");

    @Test
    @DisplayName("SQL 綁 user_id 與 effective_at >= since、沖銷列反號；列對應為 assetId / signedDelta / effectiveAt")
    @SuppressWarnings("unchecked")
    void listSinceIsScopedToUserAndSignsReversals() throws Exception {
        JdbcClient jdbcClient = mock(JdbcClient.class);
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<DayAdjustment> mapped = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbcClient.sql(anyString())).thenReturn(spec);
        when(spec.param(anyString(), any())).thenReturn(spec);
        when(spec.query(any(RowMapper.class))).thenReturn(mapped);
        when(mapped.list()).thenReturn(List.of());

        new JdbcShareAdjustmentRepository(jdbcClient).listSince(7L, SINCE);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcClient).sql(sql.capture());
        String statement = sql.getValue().replaceAll("\\s+", " ");
        assertThat(statement).contains("from share_adjustments")
            .contains("user_id = :userId")
            .contains("effective_at >= :since")
            .contains("when reversal_of_id is null then quantity_delta")
            .contains("else -quantity_delta");
        verify(spec).param("userId", 7L);
        verify(spec).param("since", SINCE);

        ArgumentCaptor<RowMapper<DayAdjustment>> mapper = ArgumentCaptor.captor();
        verify(spec).query(mapper.capture());
        ResultSet rs = mock(ResultSet.class);
        OffsetDateTime effectiveAt = OffsetDateTime.parse("2026-09-04T04:00:00Z");
        when(rs.getLong("asset_id")).thenReturn(42L);
        when(rs.getBigDecimal("signed_delta")).thenReturn(new BigDecimal("-300"));
        when(rs.getObject("effective_at", OffsetDateTime.class)).thenReturn(effectiveAt);

        DayAdjustment row = mapper.getValue().mapRow(rs, 0);

        assertThat(row).isEqualTo(new DayAdjustment(42L, new BigDecimal("-300"), effectiveAt));
    }
}
