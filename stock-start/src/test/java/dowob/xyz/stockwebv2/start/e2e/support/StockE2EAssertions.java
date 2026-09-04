package dowob.xyz.stockwebv2.start.e2e.support;

import org.springframework.test.web.servlet.ResultMatcher;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

public final class StockE2EAssertions {

    private StockE2EAssertions() {
    }

    public static ResultMatcher apiSuccess() {
        return jsonPath("$.success").value(true);
    }

    public static ResultMatcher apiError(String errorCode) {
        return jsonPath("$.error.code").value(errorCode);
    }

    public static ResultMatcher hasData() {
        return jsonPath("$.data").exists();
    }
}
