package dowob.xyz.stockwebv2.asset.service;

import dowob.xyz.stockwebv2.asset.service.AssetFacadeImpl.RawProfile;
import dowob.xyz.stockwebv2.asset.service.AssetFacadeImpl.RawSummary;
import dowob.xyz.stockwebv2.common.model.AssetType;
import dowob.xyz.stockwebv2.common.model.CurrencyCode;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetProfile;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetSummary;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * {@link AssetFacadeImpl} 壞列容錯的單元測試（04.1 REVIEW WR-02）。
 *
 * <p>只驗證「原始列 → enum」的純函式：{@code asset_type} 或 {@code currency} 不在 enum 內的列必須被略過
 * （回傳 empty、不拋例外），否則一列壞資料會讓 summary / holdings / trades 全部 500、
 * 讓 market-data ingestor 整批失敗。真 DB 路徑由 {@link AssetFacadeImplIT} 驗證。</p>
 *
 * @author Yuan
 * @version 1.0.0
 */
@DisplayName("AssetFacadeImpl 壞列容錯")
class AssetFacadeImplTest {

    @Test
    @DisplayName("findProfiles：合法列照常轉成 AssetProfile")
    void validProfileRowIsMapped() {
        Optional<AssetProfile> profile = AssetFacadeImpl.toProfile(
                new RawProfile(41L, "AAPL", "STOCK", "US", "USD", "Tech"));

        assertThat(profile).contains(new AssetProfile(41L, "AAPL", AssetType.STOCK, "US", CurrencyCode.USD, "Tech"));
    }

    @Test
    @DisplayName("findProfiles：currency 不在 CurrencyCode 內的列被略過，不拋例外")
    void profileRowWithUnsupportedCurrencyIsSkipped() {
        RawProfile raw = new RawProfile(42L, "0700.HK", "STOCK", "HK", "HKD", "Tech");

        Optional<AssetProfile> profile = assertDoesNotThrow(() -> AssetFacadeImpl.toProfile(raw));

        assertThat(profile).isEmpty();
    }

    @Test
    @DisplayName("findProfiles：asset_type 不在 AssetType 內的列被略過，不拋例外")
    void profileRowWithUnsupportedAssetTypeIsSkipped() {
        RawProfile raw = new RawProfile(43L, "GOLD", "COMMODITY", "US", "USD", null);

        Optional<AssetProfile> profile = assertDoesNotThrow(() -> AssetFacadeImpl.toProfile(raw));

        assertThat(profile).isEmpty();
    }

    @Test
    @DisplayName("findAllTradeable / findBySymbol：合法列照常轉成 AssetSummary")
    void validSummaryRowIsMapped() {
        Optional<AssetSummary> summary = AssetFacadeImpl.toSummary(
                new RawSummary(41L, "AAPL", "Apple", "STOCK", "US", true, true));

        assertThat(summary).contains(new AssetSummary(41L, "AAPL", "Apple", AssetType.STOCK, "US", true, true));
    }

    @Test
    @DisplayName("findAllTradeable / findBySymbol：asset_type 不在 AssetType 內的列被略過，不拋例外")
    void summaryRowWithUnsupportedAssetTypeIsSkipped() {
        RawSummary raw = new RawSummary(43L, "GOLD", "Gold", "COMMODITY", "US", true, true);

        Optional<AssetSummary> summary = assertDoesNotThrow(() -> AssetFacadeImpl.toSummary(raw));

        assertThat(summary).isEmpty();
    }
}
