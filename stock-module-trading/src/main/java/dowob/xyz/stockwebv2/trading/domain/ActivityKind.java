package dowob.xyz.stockwebv2.trading.domain;

/**
 * 統一活動列表（{@code GET /api/v1/portfolio/activities}，PORT-13）的列種類。
 *
 * <p>常數名即對外契約值（{@code PortfolioActivityDto.kind}）：交易沿用 {@code transactions.type}，
 * 現金股利固定為 {@link #CASH_DIVIDEND}，股數調整沿用 {@code share_adjustments.type}。
 * 新增常數等於擴充對外契約，必須先改 {@code ai-docs/trading-portfolio-contract.md}。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public enum ActivityKind {
    /**
     * 買入成交（{@code transactions.type = 'BUY'}）。
     */
    BUY,

    /**
     * 賣出成交（{@code transactions.type = 'SELL'}）。
     */
    SELL,

    /**
     * 現金股利（{@code cash_dividends} 的一列，含沖銷列）。
     */
    CASH_DIVIDEND,

    /**
     * 配股（{@code share_adjustments.type = 'STOCK_DIVIDEND'}）。
     */
    STOCK_DIVIDEND,

    /**
     * 拆股 / 合股（{@code share_adjustments.type = 'SPLIT'}）。
     */
    SPLIT
}
