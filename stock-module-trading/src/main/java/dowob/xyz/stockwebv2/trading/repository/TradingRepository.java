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

    PageResponse<TradeTransaction> listTransactions(Long userId, Long assetId, int page, int size);

    List<HoldingPosition> listHoldings(Long userId);

    /**
     * 加總該使用者所有持倉（含已平倉 {@code total_quantity = 0}）的已實現損益。
     * 供 portfolio summary 使用，避免平倉部位的 realized PnL 因 {@link #listHoldings} 過濾而遺失。
     */
    BigDecimal sumRealizedPnl(Long userId);

    Optional<LatestAssetPrice> findLatestPrice(Long assetId);
}
