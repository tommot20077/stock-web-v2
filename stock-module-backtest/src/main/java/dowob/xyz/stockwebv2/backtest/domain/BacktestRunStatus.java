package dowob.xyz.stockwebv2.backtest.domain;

import java.util.Arrays;

public enum BacktestRunStatus {
    QUEUED("queued"),
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    REJECTED("rejected");

    private final String apiValue;

    BacktestRunStatus(String apiValue) {
        this.apiValue = apiValue;
    }

    public String apiValue() {
        return apiValue;
    }

    public static BacktestRunStatus fromApiValue(String value) {
        return Arrays.stream(values())
            .filter(status -> status.apiValue.equals(value))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unsupported status: " + value));
    }
}
