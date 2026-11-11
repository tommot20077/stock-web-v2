package dowob.xyz.stockwebv2.start.support;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeDiagnosingMatcher;

import java.math.BigDecimal;

/**
 * IT / E2E 共用的金額字串 Hamcrest matcher(Phase 5 D-05 ~ D-09:API 的 BigDecimal 欄位一律輸出 JSON 字串)。
 *
 * <p>為什麼不直接用 {@code jsonPath(...).value(120)} 或 {@code equalTo(120.0)}:前者會先把實際值轉成期望的型別
 * 再比,{@code "120"} 與 {@code 120} 分不出來,證明不了 wire 型別;後者在欄位變字串後必紅,但改成
 * {@code equalTo("120.00000000")} 又得在每個計算欄位猜後端的 scale(乘法、匯率折算後的 scale 不一定是 8)。
 * 本 matcher 同時檢查兩件事:實際值必須是 {@link String}(不是 JSON number),而且以 {@link BigDecimal#compareTo}
 * 比對數值 —— 不在乎 scale,只在乎型別與數值。</p>
 *
 * <p>scale 本身就是測試重點的地方(例如精度回歸、DB {@code NUMERIC(24,8)} 原樣回傳)請改用
 * {@code equalTo("100.00000000")} 逐字比對。</p>
 */
public final class DecimalStringMatchers {

    private DecimalStringMatchers() {
    }

    /**
     * 實際值必須是 JSON 字串,且數值(compareTo)等於 {@code expected}。
     *
     * @param expected 期望數值的十進位字面,例如 {@code "995"}、{@code "32455.91"}
     * @return 比對 jsonPath 原始值的 matcher
     */
    public static Matcher<Object> decimalString(String expected) {
        BigDecimal expectedValue = new BigDecimal(expected);
        return new TypeSafeDiagnosingMatcher<>(Object.class) {
            @Override
            protected boolean matchesSafely(Object actual, Description mismatch) {
                if (!(actual instanceof String text)) {
                    mismatch.appendText("was not a JSON string: ").appendValue(actual)
                        .appendText(" (").appendText(actual.getClass().getSimpleName()).appendText(")");
                    return false;
                }
                BigDecimal actualValue;
                try {
                    actualValue = new BigDecimal(text);
                } catch (NumberFormatException e) {
                    mismatch.appendText("was a non-numeric string ").appendValue(text);
                    return false;
                }
                if (actualValue.compareTo(expectedValue) != 0) {
                    mismatch.appendText("was ").appendValue(text);
                    return false;
                }
                return true;
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("a JSON decimal string numerically equal to ").appendValue(expected);
            }
        };
    }
}
