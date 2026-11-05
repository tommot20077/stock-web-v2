package dowob.xyz.stockwebv2.trading.domain;

import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Map;

public class HoldingCalculator {
    private static final int MONEY_SCALE = 8;

    public Holding applyBuy(
        Holding existing,
        Long userId,
        Long assetId,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal fee,
        OffsetDateTime now
    ) {
        validatePositive(quantity, ErrorCode.TRADE_INVALID_QUANTITY);
        validatePositive(price, ErrorCode.TRADE_INVALID_PRICE);
        BigDecimal safeFee = nonNegativeFee(fee);
        BigDecimal currentQuantity = existing == null ? BigDecimal.ZERO : existing.totalQuantity();
        BigDecimal currentCost = currentQuantity.multiply(existing == null ? BigDecimal.ZERO : existing.avgCost());
        BigDecimal addedCost = quantity.multiply(price).add(safeFee);
        BigDecimal nextQuantity = currentQuantity.add(quantity);
        BigDecimal nextAvgCost = currentCost.add(addedCost).divide(nextQuantity, MONEY_SCALE, RoundingMode.HALF_UP);
        return new Holding(
            existing == null ? null : existing.id(),
            userId,
            assetId,
            nextQuantity,
            nextAvgCost,
            existing == null ? BigDecimal.ZERO : existing.realizedPnl(),
            existing == null ? 0 : existing.version(),
            now
        );
    }

    public Holding applySell(Holding existing, BigDecimal quantity, BigDecimal price, BigDecimal fee, OffsetDateTime now) {
        validatePositive(quantity, ErrorCode.TRADE_INVALID_QUANTITY);
        validatePositive(price, ErrorCode.TRADE_INVALID_PRICE);
        BigDecimal safeFee = nonNegativeFee(fee);
        if (existing == null || existing.totalQuantity().compareTo(quantity) < 0) {
            throw new BusinessException(ErrorCode.TRADE_INSUFFICIENT_HOLDING, ErrorCode.TRADE_INSUFFICIENT_HOLDING.defaultMessage());
        }
        BigDecimal nextQuantity = existing.totalQuantity().subtract(quantity);
        BigDecimal realized = price.subtract(existing.avgCost()).multiply(quantity).subtract(safeFee);
        BigDecimal nextAvgCost = nextQuantity.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO : existing.avgCost();
        return new Holding(
            existing.id(),
            existing.userId(),
            existing.assetId(),
            nextQuantity,
            nextAvgCost,
            existing.realizedPnl().add(realized),
            existing.version(),
            now
        );
    }

    /**
     * 套用非現金的股數調整（配股、拆股、合股共用，D-07）：股數變、總成本不變。
     *
     * <p>新均價 = 原持股 × 原均價 ÷ 新持股，以 scale 8、HALF_UP 捨入（與 {@link #applyBuy} 相同），
     * 所以調整後的 {@code totalQuantity × avgCost} 與原總成本只差 scale-8 的捨入誤差，不保證逐位元相等。
     * 已實現損益不變；id 與 version 沿用，由持倉更新的樂觀鎖保護。
     *
     * @param existing      目前持倉；null 或持股為 0 表示無持倉
     * @param quantityDelta 股數變動（配股為實領股數；拆股 / 合股為 {@code ShareAdjustmentCalculator.splitDelta} 的結果，可為負）
     * @param now           更新時間
     * @return 調整後的持倉
     * @throws BusinessException 無持倉，或調整後持股小於等於 0 時為 {@link ErrorCode#TRADE_INSUFFICIENT_HOLDING}
     */
    public Holding applyShareAdjustment(Holding existing, BigDecimal quantityDelta, OffsetDateTime now) {
        if (existing == null || existing.totalQuantity().signum() <= 0) {
            throw new BusinessException(
                ErrorCode.TRADE_INSUFFICIENT_HOLDING,
                ErrorCode.TRADE_INSUFFICIENT_HOLDING.defaultMessage(),
                Map.of("symbol", "no holding")
            );
        }
        BigDecimal nextQuantity = existing.totalQuantity().add(quantityDelta);
        // 歸零會讓剩餘成本憑空消失（沒有對應的賣出現金流），為負則違反 ck_holdings_quantity_non_negative
        if (nextQuantity.signum() <= 0) {
            throw new BusinessException(ErrorCode.TRADE_INSUFFICIENT_HOLDING, ErrorCode.TRADE_INSUFFICIENT_HOLDING.defaultMessage());
        }
        BigDecimal nextAvgCost = existing.totalQuantity()
            .multiply(existing.avgCost())
            .divide(nextQuantity, MONEY_SCALE, RoundingMode.HALF_UP);
        return new Holding(
            existing.id(),
            existing.userId(),
            existing.assetId(),
            nextQuantity,
            nextAvgCost,
            existing.realizedPnl(),
            existing.version(),
            now
        );
    }

    private void validatePositive(BigDecimal value, ErrorCode code) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(code, code.defaultMessage());
        }
    }

    private BigDecimal nonNegativeFee(BigDecimal fee) {
        if (fee == null) {
            return BigDecimal.ZERO;
        }
        if (fee.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "fee must be greater than or equal to 0");
        }
        return fee;
    }
}
