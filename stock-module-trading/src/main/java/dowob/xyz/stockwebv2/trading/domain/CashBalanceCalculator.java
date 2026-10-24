package dowob.xyz.stockwebv2.trading.domain;

import dowob.xyz.stockwebv2.common.model.CurrencyCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 依幣別加總現金流，推導各幣別的可用現金餘額（CASH-02；D-06 / D-07 / D-10）。
 *
 * <p>輸入是「多個現金流來源合併後的 list」：04.1 的來源為入出金分錄淨額與交易現金流，
 * 04.2 的現金股利只需再多加一個來源，不需修改本類別（RESEARCH Q6.2 擴充點）。</p>
 *
 * <p>本類別是無 I/O、無 Spring 的純函式，不存任何餘額；餘額一律由現金流即時推導（D-10）。
 * 負值照實保留、不夾成 0，由前端負責提示（D-08）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public final class CashBalanceCalculator {

    /**
     * 餘額的小數位數，與資金欄位 NUMERIC(24, 8) 一致。
     */
    private static final int MONEY_SCALE = 8;

    private CashBalanceCalculator() {
    }

    /**
     * 依幣別加總現金流。
     *
     * @param flows 現金流列表；正值為流入、負值為流出，允許為空
     * @return 依 {@link CurrencyCode} 宣告順序排列的各幣別餘額，scale 一律為 8（HALF_UP）；
     *         只包含出現過的幣別，同幣別正負抵銷為 0 時仍保留該鍵；輸入為空則回空 map
     */
    public static Map<CurrencyCode, BigDecimal> balances(List<CashFlow> flows) {
        Map<CurrencyCode, BigDecimal> totals = new EnumMap<>(CurrencyCode.class);
        for (CashFlow flow : flows) {
            totals.merge(flow.currency(), flow.amount(), BigDecimal::add);
        }
        totals.replaceAll((currency, total) -> total.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        return totals;
    }
}
