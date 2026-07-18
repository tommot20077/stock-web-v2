package dowob.xyz.stockwebv2.trading.domain;

import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;

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
