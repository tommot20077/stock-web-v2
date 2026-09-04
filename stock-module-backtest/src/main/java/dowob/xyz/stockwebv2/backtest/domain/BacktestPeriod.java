package dowob.xyz.stockwebv2.backtest.domain;

import java.util.Arrays;

public enum BacktestPeriod {
    ONE_YEAR("1Y", 12),
    THREE_YEARS("3Y", 36),
    FIVE_YEARS("5Y", 60);

    private final String apiValue;
    private final int monthCount;

    BacktestPeriod(String apiValue, int monthCount) {
        this.apiValue = apiValue;
        this.monthCount = monthCount;
    }

    public String apiValue() {
        return apiValue;
    }

    public int monthCount() {
        return monthCount;
    }

    public static BacktestPeriod fromApiValue(String value) {
        return Arrays.stream(values())
            .filter(period -> period.apiValue.equals(value))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unsupported period: " + value));
    }
}
