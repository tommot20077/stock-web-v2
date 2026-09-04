package dowob.xyz.stockwebv2.asset.service;

import dowob.xyz.stockwebv2.asset.api.AssetDto;
import dowob.xyz.stockwebv2.asset.domain.Asset;
import dowob.xyz.stockwebv2.asset.repository.AssetRepository;
import dowob.xyz.stockwebv2.common.api.PageResponse;

import org.springframework.stereotype.Service;

@Service
public class AssetQueryService {
    private final AssetRepository assetRepository;

    public AssetQueryService(AssetRepository assetRepository) {
        this.assetRepository = assetRepository;
    }

    public PageResponse<AssetDto> search(String query, int page, int size) {
        var items = assetRepository.search(query, page, size).stream().map(this::toDto).toList();
        long total = assetRepository.count(query);
        return PageResponse.of(items, page, size, total);
    }

    private AssetDto toDto(Asset asset) {
        return new AssetDto(
            asset.uuid(),
            asset.symbol(),
            asset.name(),
            asset.assetType(),
            asset.market(),
            asset.currency(),
            asset.sector(),
            asset.tradeable(),
            asset.latestPrice(),
            asset.change(),
            asset.changePercent(),
            asset.volumeText(),
            asset.high(),
            asset.low()
        );
    }
}
