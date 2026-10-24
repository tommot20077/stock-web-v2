package dowob.xyz.stockwebv2.trading.domain;

import dowob.xyz.stockwebv2.common.model.CurrencyCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CashBalanceCalculator} 的單元測試（CASH-02；D-06 / D-07 / D-08 / D-10）。
 *
 * <p>計算器是無 I/O 的純函式，所有期望值皆為手算值：依幣別加總多個現金流來源、
 * 負值照實保留、正負抵銷為 0 時仍保留該幣別鍵、scale 一律為 8。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("現金餘額計算器")
class CashBalanceCalculatorTest {

    @Test
    @DisplayName("多個來源依幣別加總：[TWD +50000, USD -1000, USD +439] 得 USD -561、TWD 50000")
    void sumsFlowsPerCurrency() {
        Map<CurrencyCode, BigDecimal> balances = CashBalanceCalculator.balances(List.of(
            new CashFlow(CurrencyCode.TWD, new BigDecimal("50000")),
            new CashFlow(CurrencyCode.USD, new BigDecimal("-1000")),
            new CashFlow(CurrencyCode.USD, new BigDecimal("439"))
        ));

        assertThat(balances).hasSize(2);
        assertThat(balances.get(CurrencyCode.USD)).isEqualByComparingTo("-561");
        assertThat(balances.get(CurrencyCode.TWD)).isEqualByComparingTo("50000");
        assertThat(balances.get(CurrencyCode.USD)).isEqualTo(new BigDecimal("-561.00000000"));
        assertThat(balances.get(CurrencyCode.TWD)).isEqualTo(new BigDecimal("50000.00000000"));
    }

    @Test
    @DisplayName("空輸入回空 map")
    void emptyFlowsReturnEmptyMap() {
        assertThat(CashBalanceCalculator.balances(List.of())).isEmpty();
    }

    @Test
    @DisplayName("同幣別正負抵銷為 0 時仍保留該幣別鍵（D-08）")
    void offsettingFlowsKeepCurrencyKeyWithZero() {
        Map<CurrencyCode, BigDecimal> balances = CashBalanceCalculator.balances(List.of(
            new CashFlow(CurrencyCode.USD, new BigDecimal("250.5")),
            new CashFlow(CurrencyCode.USD, new BigDecimal("-250.5"))
        ));

        assertThat(balances).containsOnlyKeys(CurrencyCode.USD);
        assertThat(balances.get(CurrencyCode.USD)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("只有流出時負值照實保留，不夾成 0（D-08）")
    void outflowOnlyKeepsNegativeBalance() {
        Map<CurrencyCode, BigDecimal> balances = CashBalanceCalculator.balances(List.of(
            new CashFlow(CurrencyCode.JPY, new BigDecimal("-120000")),
            new CashFlow(CurrencyCode.JPY, new BigDecimal("-30.25"))
        ));

        assertThat(balances.get(CurrencyCode.JPY)).isEqualTo(new BigDecimal("-120030.25000000"));
    }

    @Test
    @DisplayName("scale 一律為 8：輸入 100.5 得 100.50000000")
    void resultScaleIsAlwaysEight() {
        Map<CurrencyCode, BigDecimal> balances = CashBalanceCalculator.balances(List.of(
            new CashFlow(CurrencyCode.EUR, new BigDecimal("100.5"))
        ));

        assertThat(balances.get(CurrencyCode.EUR)).isEqualTo(new BigDecimal("100.50000000"));
        assertThat(balances.get(CurrencyCode.EUR).scale()).isEqualTo(8);
    }

    @Test
    @DisplayName("超過 8 位小數的合計以 HALF_UP 收斂到 scale 8")
    void sumBeyondEightDecimalsIsRoundedHalfUp() {
        Map<CurrencyCode, BigDecimal> balances = CashBalanceCalculator.balances(List.of(
            new CashFlow(CurrencyCode.USD, new BigDecimal("0.000000005")),
            new CashFlow(CurrencyCode.USD, new BigDecimal("1"))
        ));

        assertThat(balances.get(CurrencyCode.USD)).isEqualTo(new BigDecimal("1.00000001"));
    }

    @Test
    @DisplayName("回傳 map 依 CurrencyCode 宣告順序迭代，且只含出現過的幣別")
    void iterationFollowsCurrencyCodeDeclarationOrder() {
        Map<CurrencyCode, BigDecimal> balances = CashBalanceCalculator.balances(List.of(
            new CashFlow(CurrencyCode.JPY, BigDecimal.ONE),
            new CashFlow(CurrencyCode.TWD, BigDecimal.ONE),
            new CashFlow(CurrencyCode.USD, BigDecimal.ONE)
        ));

        assertThat(balances.keySet()).containsExactly(CurrencyCode.USD, CurrencyCode.TWD, CurrencyCode.JPY);
        assertThat(balances).doesNotContainKey(CurrencyCode.EUR);
    }
}
