package dowob.xyz.stockwebv2.start.support;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.JsonPathExpectationsHelper;

import static dowob.xyz.stockwebv2.start.support.DecimalStringMatchers.decimalString;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;

/**
 * {@link DecimalStringMatchers} 的行為樣本:IT / E2E 本機無 Docker 跑不了,這裡以與 MockMvc {@code jsonPath(expr, matcher)}
 * 相同的 {@link JsonPathExpectationsHelper} 證明 matcher 分得出 JSON 字串與 JSON number、且數值比對不看 scale。
 */
class DecimalStringMatchersTest {

    @Test
    void acceptsDecimalStringWithDifferentScale() {
        assertThatCode(() -> new JsonPathExpectationsHelper("$.amount")
            .assertValue("{\"amount\":\"120.00000000\"}", decimalString("120")))
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsJsonNumberEvenWhenNumericallyEqual() {
        assertThatThrownBy(() -> new JsonPathExpectationsHelper("$.amount")
            .assertValue("{\"amount\":120.0}", decimalString("120")))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("was not a JSON string");
    }

    @Test
    void rejectsDifferentValue() {
        assertThatThrownBy(() -> new JsonPathExpectationsHelper("$.amount")
            .assertValue("{\"amount\":\"120.00000001\"}", decimalString("120")))
            .isInstanceOf(AssertionError.class);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> new JsonPathExpectationsHelper("$.amount")
            .assertValue("{\"amount\":null}", decimalString("0")))
            .isInstanceOf(AssertionError.class);
    }

    @Test
    void worksInsideHasItemForFilteredPaths() {
        String body = "{\"data\":[{\"symbol\":\"AAPL\",\"marketValue\":\"2000.00000000\"},"
            + "{\"symbol\":\"TSLA\",\"marketValue\":\"1.5\"}]}";
        assertThatCode(() -> new JsonPathExpectationsHelper("$.data[?(@.symbol=='AAPL')].marketValue")
            .assertValue(body, hasItem(decimalString("2000"))))
            .doesNotThrowAnyException();
    }
}
