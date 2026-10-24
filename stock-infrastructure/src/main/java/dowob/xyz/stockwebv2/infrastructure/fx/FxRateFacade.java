package dowob.xyz.stockwebv2.infrastructure.fx;

/**
 * 提供其他 module 查詢目前匯率的 facade。
 *
 * <p>匯率只在後端折算一次，前端不需要知道匯率（D-21）。目前來源為 V2 seed 的靜態值；
 * 日後匯率歷史（Deferred：fx-pnl-and-rate-history）只需更換實作，介面不變。
 * 實作類別 {@code FxRateFacadeImpl} 位於 stock-module-asset。
 *
 * @author Yuan
 * @version 1.0.0
 */
public interface FxRateFacade {

    /**
     * 取得目前所有可用的匯率。
     *
     * <p>無法解析的幣別與非正匯率列會被略過，不會拋例外。
     *
     * @return 匯率值物件；表為空時為 {@link FxRates#empty()} 等價物件
     */
    FxRates currentRates();
}
