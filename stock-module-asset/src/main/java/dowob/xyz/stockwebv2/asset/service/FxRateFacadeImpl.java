package dowob.xyz.stockwebv2.asset.service;

import dowob.xyz.stockwebv2.common.model.CurrencyCode;
import dowob.xyz.stockwebv2.infrastructure.fx.FxRate;
import dowob.xyz.stockwebv2.infrastructure.fx.FxRateFacade;
import dowob.xyz.stockwebv2.infrastructure.fx.FxRates;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link FxRateFacade} 的 JDBC 實作，部署於 stock-module-asset (L2)。
 *
 * <p>以單一查詢讀取整張 {@code fx_rates}（資料量為個位數幣對）。注意
 * {@code fx_rates.base_currency} 是「幣對的基礎幣」，與 {@code users.base_currency}
 * （使用者基準幣）同名異義。
 *
 * <p>無法解析的幣別或非正匯率列會 WARN 並略過（僅記列 id 與原因），不讓呼叫端 500。
 *
 * @author Yuan
 * @version 1.0.0
 */
@Service
public class FxRateFacadeImpl implements FxRateFacade {

    private static final Logger log = LoggerFactory.getLogger(FxRateFacadeImpl.class);

    private final JdbcClient jdbcClient;

    public FxRateFacadeImpl(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public FxRates currentRates() {
        List<RawRow> rawRows = jdbcClient.sql("""
                        select id, base_currency, quote_currency, rate, rate_time
                        from fx_rates
                        """)
                .query(this::mapRaw)
                .list();

        List<FxRate> parsed = new ArrayList<>(rawRows.size());
        for (RawRow raw : rawRows) {
            toFxRate(raw).ifPresent(parsed::add);
        }
        return FxRates.of(parsed);
    }

    private Optional<FxRate> toFxRate(RawRow raw) {
        if (raw.rate().signum() <= 0) {
            log.warn("fx_rates 略過非正匯率列：id={}", raw.id());
            return Optional.empty();
        }
        try {
            return Optional.of(new FxRate(
                    CurrencyCode.valueOf(raw.baseCurrency()),
                    CurrencyCode.valueOf(raw.quoteCurrency()),
                    raw.rate(),
                    raw.rateTime()));
        } catch (IllegalArgumentException e) {
            log.warn("fx_rates 略過無法解析幣別的列：id={}", raw.id());
            return Optional.empty();
        }
    }

    private RawRow mapRaw(ResultSet rs, int rowNum) throws SQLException {
        return new RawRow(
                rs.getLong("id"),
                rs.getString("base_currency"),
                rs.getString("quote_currency"),
                rs.getBigDecimal("rate"),
                rs.getObject("rate_time", OffsetDateTime.class));
    }

    /** 尚未驗證幣別的原始列。 */
    private record RawRow(
            long id,
            String baseCurrency,
            String quoteCurrency,
            BigDecimal rate,
            OffsetDateTime rateTime
    ) {
    }
}
