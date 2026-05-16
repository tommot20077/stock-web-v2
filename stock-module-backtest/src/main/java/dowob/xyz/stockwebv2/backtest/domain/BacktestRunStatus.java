package dowob.xyz.stockwebv2.backtest.domain;

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
}
