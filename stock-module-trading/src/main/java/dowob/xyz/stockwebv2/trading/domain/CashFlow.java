package dowob.xyz.stockwebv2.trading.domain;

import dowob.xyz.stockwebv2.common.model.CurrencyCode;

import java.math.BigDecimal;

/**
 * 單一現金流元素，是 {@link CashBalanceCalculator} 的輸入單位。
 *
 * <p>現金流可來自入出金分錄、交易（買入為流出、賣出為流入），日後 04.2 的現金股利也只需
 * 多提供一個來源。</p>
 *
 * @param currency 幣別；交易現金流使用該標的的幣別，入出金使用分錄的幣別
 * @param amount   金額；正值為流入、負值為流出
 * @author Yuan
 * @version 1.0
 */
public record CashFlow(CurrencyCode currency, BigDecimal amount) {
}
