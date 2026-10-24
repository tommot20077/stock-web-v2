package dowob.xyz.stockwebv2.trading.domain;

import dowob.xyz.stockwebv2.trading.api.AllocationSliceDto;
import dowob.xyz.stockwebv2.trading.api.SectorAllocationDto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AllocationCalculator} 的單元測試（PORT-12；D-18 ~ D-21）。
 *
 * <p>計算器是無 I/O 的純函式，所有期望值皆為手算值：類別配置含正現金、產業配置不含現金、
 * 沒有持倉的類別不出現、null 產業原樣保留且排最後、權重 scale 為 8。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("資產配置計算器")
class AllocationCalculatorTest {

    @Test
    @DisplayName("類別配置含正現金：STOCK 32418、CRYPTO 32418、cash 35164 → CASH 0.35164，同值依類別字母排序")
    void categoriesIncludePositiveCash() {
        AllocationCalculator.Result result = AllocationCalculator.calculate(List.of(
            input("STOCK", "Auto", "32418", "0"),
            input("CRYPTO", "Crypto", "32418", "0")
        ), new BigDecimal("35164"));

        assertThat(result.allocation()).extracting(AllocationSliceDto::category)
            .containsExactly("CASH", "CRYPTO", "STOCK");
        assertThat(result.allocation().get(0).value()).isEqualTo(new BigDecimal("35164.00000000"));
        assertThat(result.allocation().get(0).weight()).isEqualTo(new BigDecimal("0.35164000"));
        assertThat(result.allocation().get(1).value()).isEqualTo(new BigDecimal("32418.00000000"));
        assertThat(result.allocation().get(1).weight()).isEqualTo(new BigDecimal("0.32418000"));
        assertThat(result.allocation().get(2).weight()).isEqualTo(new BigDecimal("0.32418000"));
    }

    @Test
    @DisplayName("同類別的多筆持倉先加總再計權重")
    void sameCategoryHoldingsAreSummed() {
        AllocationCalculator.Result result = AllocationCalculator.calculate(List.of(
            input("STOCK", "Tech", "300", "0"),
            input("STOCK", "Auto", "100", "0"),
            input("BOND", "Bond", "100", "0")
        ), BigDecimal.ZERO);

        assertThat(result.allocation()).extracting(AllocationSliceDto::category).containsExactly("STOCK", "BOND");
        assertThat(result.allocation().get(0).value()).isEqualByComparingTo("400");
        assertThat(result.allocation().get(0).weight()).isEqualByComparingTo("0.8");
        assertThat(result.allocation().get(1).weight()).isEqualByComparingTo("0.2");
    }

    @Test
    @DisplayName("現金 <= 0 不列為一塊，權重以持倉總值為分母（D-18）")
    void negativeOrZeroCashIsNotASlice() {
        AllocationCalculator.Result negative = AllocationCalculator.calculate(List.of(
            input("STOCK", "Tech", "300", "0"),
            input("CRYPTO", "Crypto", "100", "0")
        ), new BigDecimal("-14836"));
        AllocationCalculator.Result zero = AllocationCalculator.calculate(List.of(
            input("STOCK", "Tech", "300", "0"),
            input("CRYPTO", "Crypto", "100", "0")
        ), BigDecimal.ZERO);

        for (AllocationCalculator.Result result : List.of(negative, zero)) {
            assertThat(result.allocation()).extracting(AllocationSliceDto::category)
                .containsExactly("STOCK", "CRYPTO");
            assertThat(result.allocation().get(0).weight()).isEqualByComparingTo("0.75");
            assertThat(result.allocation().get(1).weight()).isEqualByComparingTo("0.25");
        }
    }

    @Test
    @DisplayName("市值為 0 的類別不出現")
    void zeroValueCategoryIsOmitted() {
        AllocationCalculator.Result result = AllocationCalculator.calculate(List.of(
            input("STOCK", "Tech", "500", "0"),
            input("CRYPTO", "Crypto", "0", "0")
        ), BigDecimal.ZERO);

        assertThat(result.allocation()).extracting(AllocationSliceDto::category).containsExactly("STOCK");
        assertThat(result.allocation().get(0).weight()).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("null 產業原樣保留並排最後（即使金額最大），其餘依金額降冪；檔數與未實現損益正確加總（D-19）")
    void nullSectorIsKeptAndSortedLast() {
        AllocationCalculator.Result result = AllocationCalculator.calculate(List.of(
            input("STOCK", "Tech", "150", "10"),
            input("STOCK", "Tech", "50", "-4"),
            input("STOCK", null, "500", "7"),
            input("STOCK", "Auto", "100", "-1")
        ), new BigDecimal("999"));

        assertThat(result.sectors()).extracting(SectorAllocationDto::sector).containsExactly("Tech", "Auto", null);
        SectorAllocationDto tech = result.sectors().get(0);
        assertThat(tech.value()).isEqualByComparingTo("200");
        assertThat(tech.holdingCount()).isEqualTo(2);
        assertThat(tech.unrealizedPnl()).isEqualByComparingTo("6");
        SectorAllocationDto auto = result.sectors().get(1);
        assertThat(auto.holdingCount()).isEqualTo(1);
        assertThat(auto.unrealizedPnl()).isEqualByComparingTo("-1");
        SectorAllocationDto unclassified = result.sectors().get(2);
        assertThat(unclassified.value()).isEqualByComparingTo("500");
        assertThat(unclassified.unrealizedPnl()).isEqualByComparingTo("7");
        // 產業分母 = 持倉總值 800，不含現金 999
        assertThat(tech.weight()).isEqualByComparingTo("0.25");
        assertThat(auto.weight()).isEqualByComparingTo("0.125");
        assertThat(unclassified.weight()).isEqualByComparingTo("0.625");
    }

    @Test
    @DisplayName("產業同值時依產業名字母升冪")
    void equalSectorValuesAreOrderedByName() {
        AllocationCalculator.Result result = AllocationCalculator.calculate(List.of(
            input("STOCK", "Tech", "100", "0"),
            input("STOCK", "Auto", "100", "0"),
            input("STOCK", null, "100", "0"),
            input("STOCK", "Retail", "100", "0")
        ), BigDecimal.ZERO);

        assertThat(result.sectors()).extracting(SectorAllocationDto::sector)
            .containsExactly("Auto", "Retail", "Tech", null);
    }

    @Test
    @DisplayName("沒有持倉也沒有現金：兩個 list 皆為空")
    void emptyHoldingsAndNoCashYieldEmptyLists() {
        AllocationCalculator.Result noCash = AllocationCalculator.calculate(List.of(), BigDecimal.ZERO);
        AllocationCalculator.Result negativeCash = AllocationCalculator.calculate(List.of(), new BigDecimal("-5"));

        assertThat(noCash.allocation()).isEmpty();
        assertThat(noCash.sectors()).isEmpty();
        assertThat(negativeCash.allocation()).isEmpty();
        assertThat(negativeCash.sectors()).isEmpty();
    }

    @Test
    @DisplayName("只有正現金：allocation 只有 CASH 1.0，sectorAllocation 為空")
    void onlyPositiveCashYieldsSingleCashSlice() {
        AllocationCalculator.Result result = AllocationCalculator.calculate(List.of(), new BigDecimal("1234.5"));

        assertThat(result.allocation()).hasSize(1);
        assertThat(result.allocation().get(0).category()).isEqualTo("CASH");
        assertThat(result.allocation().get(0).value()).isEqualTo(new BigDecimal("1234.50000000"));
        assertThat(result.allocation().get(0).weight()).isEqualTo(new BigDecimal("1.00000000"));
        assertThat(result.sectors()).isEmpty();
    }

    @Test
    @DisplayName("權重 scale 一律為 8，HALF_UP：1/3 為 0.33333333、2/3 為 0.66666667")
    void weightsUseScaleEight() {
        AllocationCalculator.Result result = AllocationCalculator.calculate(List.of(
            input("STOCK", "Tech", "200", "0"),
            input("CRYPTO", "Crypto", "100", "0")
        ), BigDecimal.ZERO);

        assertThat(result.allocation().get(0).weight()).isEqualTo(new BigDecimal("0.66666667"));
        assertThat(result.allocation().get(1).weight()).isEqualTo(new BigDecimal("0.33333333"));
        assertThat(result.sectors().get(0).weight()).isEqualTo(new BigDecimal("0.66666667"));
        assertThat(result.sectors().get(0).value().scale()).isEqualTo(8);
    }

    /**
     * 建立一筆測試用持倉輸入。
     *
     * @param category 類別
     * @param sector   產業，可為 null
     * @param value    基準幣市值
     * @param pnl      基準幣未實現損益
     * @return 計算輸入
     */
    private static AllocationCalculator.Input input(String category, String sector, String value, String pnl) {
        return new AllocationCalculator.Input(category, sector, new BigDecimal(value), new BigDecimal(pnl));
    }
}
