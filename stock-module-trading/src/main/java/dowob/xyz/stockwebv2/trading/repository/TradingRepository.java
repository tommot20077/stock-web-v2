package dowob.xyz.stockwebv2.trading.repository;

import dowob.xyz.stockwebv2.common.api.PageResponse;
import dowob.xyz.stockwebv2.trading.domain.DayFill;
import dowob.xyz.stockwebv2.trading.domain.Holding;
import dowob.xyz.stockwebv2.trading.domain.HoldingPosition;
import dowob.xyz.stockwebv2.trading.domain.HoldingSnapshot;
import dowob.xyz.stockwebv2.trading.domain.TradeTransaction;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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
     * 列出該使用者<strong>所有</strong>持倉列（含已平倉 {@code total_quantity = 0}），不 join {@code assets}。
     *
     * <p>供 portfolio summary 逐資產折算已實現損益：已實現損益依各資產自己的幣別折算，
     * 不能再以 SQL 跨幣別 {@code sum}。與 {@link #listHoldings} 不同，本方法不過濾已平倉部位，
     * 平倉後的已實現損益才不會遺失。
     *
     * @param userId 使用者 id
     * @return 該使用者所有持倉快照
     */
    List<HoldingSnapshot> listHoldingSnapshots(Long userId);

    /**
     * 列出該使用者在 {@code since} 之後（含）的所有成交，供計算今日價格損益。
     *
     * <p>where 一律綁 {@code user_id}，不 join {@code assets}（D-21 / H-1）；以既有的
     * {@code idx_transactions_user_executed} 走索引。呼叫端以相關資產交易日起點的最小值作 {@code since}，
     * 再逐資產以各自的交易日起點過濾。
     *
     * @param userId 使用者 id
     * @param since  起算時間（含）
     * @return 成交紀錄，順序不保證
     */
    List<DayFill> listFillsSince(Long userId, OffsetDateTime since);

    /**
     * 依標的彙總該使用者的交易現金流（以標的自己的幣別計），供推導可用現金（CASH-02）。
     *
     * <p>賣出為流入 {@code quantity × price − fee}，買入為流出 {@code −(quantity × price + fee)}；
     * 結果為各標的的淨額，正 = 淨流入、負 = 淨流出。幣別不在此處決定：本方法不 join {@code assets}，
     * 呼叫端以 {@code AssetFacade#findProfiles} 取得標的幣別（D-07）。
     *
     * <p><strong>04.2 擴充 {@code transactions.type} 時必改</strong>：SQL 的 {@code CASE} 刻意明寫 BUY 與 SELL 兩支、
     * 沒有 {@code else}，未知的交易類型結果為 NULL，{@code sum} 會略過它，不會被誤當買入而扣錢（Pitfall 9）。
     * 新增類型（例如股利、拆股）時必須在此補上對應的現金流分支，否則其現金流會被靜默忽略。
     *
     * <p>不分頁、不限時間：功能上線前的既有交易也計入（D-08），所以只買不入金的帳戶現金為負。
     *
     * @param userId 使用者 id，where 一律綁定
     * @return 標的 id → 淨現金流；沒有任何交易的標的不出現
     */
    Map<Long, BigDecimal> tradeCashFlowByAsset(Long userId);
}
