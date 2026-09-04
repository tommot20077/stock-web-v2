package dowob.xyz.stockwebv2.marketdata.provider;

import dowob.xyz.stockwebv2.common.model.KlineInterval;

import java.time.Instant;
import java.util.List;

/**
 * 市場資料提供者抽象，所有外部資料源（mock / Binance / Finnhub 等）統一實作此介面。
 *
 * <p>實作必須是 thread-safe — {@code ScheduledIngestor} 與 {@code BackfillJob} 會並行呼叫。
 *
 * <p>Symbol 為外部 provider 識別資產所用的代號（例如 {@code "AAPL"}），
 * 與內部 {@code assetId} 的對應由更上游的 ingest service 處理。
 *
 * @author Yuan
 * @version 1.0.0
 */
public interface DataProvider {

    /**
     * 回傳此 provider 的識別名稱，例如 {@code "mock"}、{@code "binance"}。
     * 用於 routing 決策與 audit log，不可為 null 或空字串。
     *
     * @return provider 名稱；不會為 null 或空字串
     */
    String name();

    /**
     * 取得指定 symbol 的當前最新 tick。
     *
     * @param symbol 資產代號，不可為 null
     * @return 最新 {@link PriceTick}；不會為 null
     * @throws UnknownSymbolException   若 provider 不支援該 symbol
     * @throws DataProviderException    若發生其他取資料失敗（網路、上游錯誤等）
     * @throws NullPointerException     若 {@code symbol} 為 null
     */
    PriceTick fetchLatest(String symbol);

    /**
     * 取得指定 symbol 在 {@code [from, to)} 半開區間內的歷史 tick，以 {@code interval} 為粒度。
     *
     * <p>回傳結果按 {@link PriceTick#time()} 升冪排序；若範圍內無資料，回傳空 list（非 null）。
     *
     * @param symbol   資產代號，不可為 null
     * @param from     範圍下界（含），不可為 null
     * @param to       範圍上界（不含），不可為 null；必須嚴格大於 {@code from}
     * @param interval K 線粒度，不可為 null
     * @return 符合範圍的 {@link PriceTick} list，按 time 升冪排序；不會為 null
     * @throws UnknownSymbolException   若 provider 不支援該 symbol
     * @throws DataProviderException    若發生其他取資料失敗
     * @throws IllegalArgumentException 若 {@code to <= from}
     * @throws NullPointerException     若任一參數為 null
     */
    List<PriceTick> fetchHistorical(String symbol, Instant from, Instant to, KlineInterval interval);
}
