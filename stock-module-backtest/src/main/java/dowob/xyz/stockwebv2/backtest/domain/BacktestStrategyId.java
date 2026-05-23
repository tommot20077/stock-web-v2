package dowob.xyz.stockwebv2.backtest.domain;

import java.util.Arrays;

public enum BacktestStrategyId {
    MA_CROSS("ma_cross", "MA Cross (20/50)"),
    RSI("rsi", "RSI Mean Reversion"),
    MOMENTUM("momentum", "Momentum (3M)"),
    DCA("dca", "DCA Weekly"),
    CUSTOM("custom", "Custom JS");

    private final String apiValue;
    private final String label;

    BacktestStrategyId(String apiValue, String label) {
        this.apiValue = apiValue;
        this.label = label;
    }

    public String apiValue() {
        return apiValue;
    }

    public String label() {
        return label;
    }

    public static BacktestStrategyId fromApiValue(String value) {
        return Arrays.stream(values())
            .filter(strategy -> strategy.apiValue.equals(value))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unsupported strategy: " + value));
    }
}
