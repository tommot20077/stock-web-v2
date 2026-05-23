package dowob.xyz.stockwebv2.marketdata.provider;

/**
 * 當 {@link DataProvider} 不支援指定 symbol 時拋出的例外。
 *
 * <p>此例外繼承 {@link DataProviderException}，屬於 unchecked exception。
 * 呼叫端可透過 {@link #symbol()} 取得觸發此例外的 symbol，便於 logging 與告警。
 *
 * @author Yuan
 * @version 1.0.0
 */
public class UnknownSymbolException extends DataProviderException {

    /** 觸發此例外的資產代號。 */
    private final String symbol;

    /**
     * 建立 UnknownSymbolException。
     *
     * @param symbol 不被 provider 支援的資產代號；不可為 null
     */
    public UnknownSymbolException(String symbol) {
        super("Unknown symbol: " + symbol);
        this.symbol = symbol;
    }

    /**
     * 回傳觸發此例外的資產代號。
     *
     * @return 資產代號，不會為 null
     */
    public String symbol() {
        return symbol;
    }
}
