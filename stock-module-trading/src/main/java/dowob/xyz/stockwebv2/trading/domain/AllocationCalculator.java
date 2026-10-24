package dowob.xyz.stockwebv2.trading.domain;

import dowob.xyz.stockwebv2.trading.api.AllocationSliceDto;
import dowob.xyz.stockwebv2.trading.api.SectorAllocationDto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 資產配置與產業分組的純函式計算（PORT-12；D-18 ~ D-21）。
 *
 * <p>分組由後端算好，匯率只在後端折算一次，前端不需要知道匯率（D-21）：輸入已經是「可折算、持有數量 &gt; 0」
 * 的持倉的基準幣市值與基準幣未實現損益，本類別不呼叫其他模組、不做 I/O，也不是 Spring bean。</p>
 *
 * <p><strong>兩個分母不同</strong>（刻意的，與 mock 的產業分布語意一致）：</p>
 * <ul>
 *   <li>類別配置 {@link Result#allocation()}（D-18，donut）：{@code asset_type} 分塊<strong>加上正現金</strong>
 *       {@value #CASH_CATEGORY}，分母 = 持倉市值合計 + max(現金, 0)。現金 &lt;= 0（只買不入金、D-08 的負現金）時
 *       不列 {@value #CASH_CATEGORY} 塊，權重以持倉合計為分母；沒有價值的類別不出現。</li>
 *   <li>產業配置 {@link Result#sectors()}（D-19，產業卡）：直接用資產的 {@code sector} 原值分組，
 *       分母 = 持倉市值合計，<strong>不含現金</strong>。{@code sector = null}（未分類）原樣保留、一律排最後
 *       （前端顯示「未分類」，後端不回在地化字串），每組帶檔數與基準幣未實現損益（UI-SPEC U-20）。</li>
 * </ul>
 *
 * <p>分母為 0（沒有可計價的持倉，且現金不為正）時兩個 list 皆為空，不拋例外也不回 null。
 * 權重為比值 0..1（不是百分比），scale 8、HALF_UP；金額欄位 scale 一律為 8。</p>
 *
 * <p>排序（結果穩定、可測）：{@code allocation} 依金額降冪、同值依類別字母升冪；{@code sectors} 依金額降冪、
 * 同值依產業字母升冪，null 產業一律最後（即使金額最大）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public final class AllocationCalculator {

    /**
     * 現金在類別配置中的類別名稱，與 {@code AssetType} 常數名並列（互不衝突）。
     */
    public static final String CASH_CATEGORY = "CASH";

    /**
     * 金額與權重的小數位數，與資金欄位 NUMERIC(24, 8) 一致。
     */
    private static final int SCALE = 8;

    private AllocationCalculator() {
    }

    /**
     * 一筆可計價持倉的配置輸入。
     *
     * @param category      類別：{@code AssetType} 常數名；不得為 null
     * @param sector        產業分類原值；可為 null（未分類）
     * @param value         基準幣市值；不得為 null
     * @param unrealizedPnl 基準幣未實現損益；不得為 null
     */
    public record Input(String category, String sector, BigDecimal value, BigDecimal unrealizedPnl) {

        /**
         * 驗證必填欄位。
         */
        public Input {
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(unrealizedPnl, "unrealizedPnl");
        }
    }

    /**
     * 計算結果。
     *
     * @param allocation 類別配置（含正現金）；不為 null
     * @param sectors    產業配置（不含現金）；不為 null
     */
    public record Result(List<AllocationSliceDto> allocation, List<SectorAllocationDto> sectors) {
    }

    /**
     * 計算類別配置與產業配置。
     *
     * @param holdings 可計價持倉的輸入；允許為空
     * @param cash     基準幣可用現金；正值才會成為 {@value #CASH_CATEGORY} 塊，其餘忽略
     * @return 類別配置與產業配置；兩者皆不為 null
     */
    public static Result calculate(List<Input> holdings, BigDecimal cash) {
        BigDecimal positiveCash = cash.signum() > 0 ? cash : BigDecimal.ZERO;
        BigDecimal holdingsTotal = BigDecimal.ZERO;
        Map<String, BigDecimal> valueByCategory = new LinkedHashMap<>();
        Map<String, SectorTotals> totalsBySector = new LinkedHashMap<>();
        for (Input holding : holdings) {
            holdingsTotal = holdingsTotal.add(holding.value());
            valueByCategory.merge(holding.category(), holding.value(), BigDecimal::add);
            // LinkedHashMap 允許 null 鍵：未分類的持倉自成一組
            totalsBySector.computeIfAbsent(holding.sector(), key -> new SectorTotals()).add(holding);
        }
        if (positiveCash.signum() > 0) {
            valueByCategory.merge(CASH_CATEGORY, positiveCash, BigDecimal::add);
        }
        return new Result(
            allocation(valueByCategory, holdingsTotal.add(positiveCash)),
            sectors(totalsBySector, holdingsTotal));
    }

    /**
     * 組出類別配置：去掉沒有價值的類別、算權重並排序。
     *
     * @param valueByCategory 類別 → 基準幣價值（已含正現金）
     * @param total           分母（持倉合計 + 正現金）
     * @return 已排序的類別配置；分母不為正時為空 list
     */
    private static List<AllocationSliceDto> allocation(Map<String, BigDecimal> valueByCategory, BigDecimal total) {
        if (total.signum() <= 0) {
            return List.of();
        }
        List<AllocationSliceDto> slices = new ArrayList<>();
        valueByCategory.forEach((category, value) -> {
            if (value.signum() != 0) {
                slices.add(new AllocationSliceDto(category, scaled(value), weight(value, total)));
            }
        });
        slices.sort(Comparator.comparing(AllocationSliceDto::value).reversed()
            .thenComparing(AllocationSliceDto::category));
        return List.copyOf(slices);
    }

    /**
     * 組出產業配置：算權重並排序，null 產業最後。
     *
     * @param totalsBySector 產業（可為 null）→ 該產業的累計
     * @param total          分母（持倉合計，不含現金）
     * @return 已排序的產業配置；分母不為正時為空 list
     */
    private static List<SectorAllocationDto> sectors(Map<String, SectorTotals> totalsBySector, BigDecimal total) {
        if (total.signum() <= 0) {
            return List.of();
        }
        List<SectorAllocationDto> rows = new ArrayList<>();
        totalsBySector.forEach((sector, totals) -> rows.add(new SectorAllocationDto(
            sector,
            scaled(totals.value),
            weight(totals.value, total),
            totals.holdingCount,
            scaled(totals.unrealizedPnl))));
        // false 排在 true 之前：sector 為 null 者一律最後
        rows.sort(Comparator.comparing((SectorAllocationDto row) -> row.sector() == null)
            .thenComparing(Comparator.comparing(SectorAllocationDto::value).reversed())
            .thenComparing(SectorAllocationDto::sector, Comparator.nullsLast(Comparator.naturalOrder())));
        return List.copyOf(rows);
    }

    /**
     * 計算權重：{@code value / total}，scale 8、HALF_UP。
     *
     * @param value 分子
     * @param total 分母，必須為正
     * @return 權重
     */
    private static BigDecimal weight(BigDecimal value, BigDecimal total) {
        return value.divide(total, SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 將金額統一為 scale 8。
     *
     * @param amount 金額
     * @return scale 為 8 的金額
     */
    private static BigDecimal scaled(BigDecimal amount) {
        return amount.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 單一產業的可變累計，僅限單次 {@link #calculate} 內使用。
     */
    private static final class SectorTotals {

        /**
         * 基準幣市值合計。
         */
        private BigDecimal value = BigDecimal.ZERO;

        /**
         * 基準幣未實現損益合計。
         */
        private BigDecimal unrealizedPnl = BigDecimal.ZERO;

        /**
         * 持倉檔數。
         */
        private int holdingCount;

        /**
         * 併入一筆持倉。
         *
         * @param holding 持倉輸入
         */
        private void add(Input holding) {
            value = value.add(holding.value());
            unrealizedPnl = unrealizedPnl.add(holding.unrealizedPnl());
            holdingCount++;
        }
    }
}
