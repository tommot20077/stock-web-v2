package dowob.xyz.stockwebv2.trading.repository;

import dowob.xyz.stockwebv2.common.api.PageResponse;
import dowob.xyz.stockwebv2.trading.domain.Holding;
import dowob.xyz.stockwebv2.trading.domain.HoldingPosition;
import dowob.xyz.stockwebv2.trading.domain.TradeTransaction;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface TradingRepository {
    /**
     * 以 {@code (user_id, idempotency_key)} 部分唯一索引為條件插入交易；若該使用者已用過同一個冪等鍵，
     * 則 do nothing 並回傳 {@link Optional#empty()}，<strong>不拋例外、也不中止當前交易</strong>，
     * 由呼叫端重讀既有交易後決定回傳既有結果或回報衝突。
     *
     * <p>這是交易的<strong>唯一</strong>寫入路徑。刻意不另外保留一個沒有冪等保護的版本——留著就會有人繞過它，
     * 而 {@code transactions} 是 append-only 帳本，誤建的重複交易永遠刪不掉。{@code idempotencyKey}
     * 為 null 時不落在該部分索引的範圍內，行為等同一般 insert（必定建列），既有的無 key 交易因此不受影響。</p>
     *
     * @param transaction 待寫入的交易；{@code id} 與 {@code createdAt} 由資料庫產生
     * @return 實際建立的交易；該使用者的冪等鍵已被用過時回傳空 Optional
     */
    Optional<TradeTransaction> insertTransactionIfAbsent(TradeTransaction transaction);

    /**
     * 以 {@code (user_id, idempotency_key)} 查出該使用者既有的交易。
     *
     * <p>查詢<strong>必須</strong>同時綁 {@code user_id}：冪等鍵只在單一使用者的範圍內唯一，少了這個條件，
     * A 送出的 key 會命中 B 的交易並把 B 的內容回給 A（威脅 T-04-02）。</p>
     *
     * <p>刻意<strong>不加</strong> {@code FOR UPDATE}：要查的列可能根本還不存在，而列鎖對不存在的列不生效，
     * 加了只會製造「已經防住併發」的錯覺。併發競爭由上述部分唯一索引承擔，不是由這個查詢承擔。</p>
     *
     * @param userId         交易擁有者 id
     * @param idempotencyKey 冪等鍵
     * @return 既有交易；查無此 key 時回傳空 Optional
     */
    Optional<TradeTransaction> findByIdempotencyKey(Long userId, String idempotencyKey);

    Optional<Holding> findHoldingForUpdate(Long userId, Long assetId);

    /**
     * 以 {@code (user_id, asset_id)} 唯一鍵為條件插入持倉；若已存在（併發首次建倉）則 do nothing，
     * 回傳 {@link Optional#empty()}，由呼叫端重讀後改以 update 併倉，避免唯一鍵衝突拋 500。
     */
    Optional<Holding> insertHoldingIfAbsent(Holding holding);

    Holding updateHolding(Holding holding);

    /**
     * 依查詢物件的篩選（標的、交易類型、成交時間半開區間）與排序（白名單排序鍵與方向）
     * 分頁查詢交易紀錄。{@code totalElements} 與 {@code items} 必須套用同一組篩選條件。
     *
     * @param query 已由 service 層驗證完成的型別化查詢物件
     * @return 該頁交易紀錄與符合篩選條件的總筆數
     */
    PageResponse<TradeTransaction> listTransactions(TradeQuery query);

    List<HoldingPosition> listHoldings(Long userId);

    /**
     * 加總該使用者所有持倉（含已平倉 {@code total_quantity = 0}）的已實現損益。
     * 供 portfolio summary 使用，避免平倉部位的 realized PnL 因 {@link #listHoldings} 過濾而遺失。
     */
    BigDecimal sumRealizedPnl(Long userId);

    Optional<LatestAssetPrice> findLatestPrice(Long assetId);
}
