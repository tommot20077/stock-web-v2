package dowob.xyz.stockwebv2.marketdata.provider;

import dowob.xyz.stockwebv2.infrastructure.asset.AssetSummary;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Mock 階段 ProviderRegistry 實作 — 一律回 mockProvider 不做任何 routing。
 * 未來接真實 provider 時應替換為 MultiProviderRegistry。
 *
 * <p>此 Bean 與 {@link MockDataProvider} 共用條件，僅在 {@code market-data.mock.enabled=true}
 * 時載入，確保 mock 停用時不強制要求 {@link MockDataProvider}。
 *
 * @author Yuan
 * @version 1.0.0
 */
@Component
@ConditionalOnProperty(
        prefix = "market-data.mock",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class MockOnlyProviderRegistry implements ProviderRegistry {

    private final MockDataProvider mockProvider;

    public MockOnlyProviderRegistry(MockDataProvider mockProvider) {
        this.mockProvider = mockProvider;
    }

    @Override
    public DataProvider findFor(AssetSummary asset) {
        Objects.requireNonNull(asset, "asset must not be null");
        return mockProvider;
    }
}
