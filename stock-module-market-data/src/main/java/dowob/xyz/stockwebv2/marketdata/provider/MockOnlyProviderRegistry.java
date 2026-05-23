package dowob.xyz.stockwebv2.marketdata.provider;

import dowob.xyz.stockwebv2.infrastructure.asset.AssetSummary;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Mock 階段 ProviderRegistry 實作 — 一律回 mockProvider 不做任何 routing。
 * 未來接真實 provider 時應替換為 MultiProviderRegistry。
 *
 * @author Yuan
 * @version 1.0.0
 */
@Component
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
