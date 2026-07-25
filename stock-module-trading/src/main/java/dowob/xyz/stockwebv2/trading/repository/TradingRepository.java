package dowob.xyz.stockwebv2.trading.repository;

import dowob.xyz.stockwebv2.common.api.PageResponse;
import dowob.xyz.stockwebv2.trading.domain.Holding;
import dowob.xyz.stockwebv2.trading.domain.HoldingPosition;
import dowob.xyz.stockwebv2.trading.domain.TradeTransaction;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface TradingRepository {
    TradeTransaction insertTransaction(TradeTransaction transaction);

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
