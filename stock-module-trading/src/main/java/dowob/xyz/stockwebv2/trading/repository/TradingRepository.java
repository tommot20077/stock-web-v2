package dowob.xyz.stockwebv2.trading.repository;

import dowob.xyz.stockwebv2.common.api.PageResponse;
import dowob.xyz.stockwebv2.trading.domain.Holding;
import dowob.xyz.stockwebv2.trading.domain.HoldingPosition;
import dowob.xyz.stockwebv2.trading.domain.TradeTransaction;

import java.util.List;
import java.util.Optional;

public interface TradingRepository {
    TradeTransaction insertTransaction(TradeTransaction transaction);

    Optional<Holding> findHoldingForUpdate(Long userId, Long assetId);

    Holding insertHolding(Holding holding);

    Holding updateHolding(Holding holding);

    PageResponse<TradeTransaction> listTransactions(Long userId, Long assetId, int page, int size);

    List<HoldingPosition> listHoldings(Long userId);

    Optional<LatestAssetPrice> findLatestPrice(Long assetId);
}
