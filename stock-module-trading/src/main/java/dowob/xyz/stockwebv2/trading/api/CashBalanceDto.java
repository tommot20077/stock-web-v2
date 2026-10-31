package dowob.xyz.stockwebv2.trading.api;

import java.math.BigDecimal;

/**
 * 單一幣別的可用現金餘額（原幣），為 {@link PortfolioSummaryDto#cashBalances()} 的元素。
 *
 * <p>餘額由入出金與全部交易現金流即時推導，不存任何餘額欄位（D-10）；只買不入金時為負值，
 * 照實回報、不夾 0（D-08）。</p>
 *
 * @param currency 幣別代碼（{@code CurrencyCode} 名稱）
 * @param amount   該幣別的原幣餘額，scale 8；可為負
 * @author Yuan
 * @version 1.0
 */
public record CashBalanceDto(String currency, BigDecimal amount) {
}
