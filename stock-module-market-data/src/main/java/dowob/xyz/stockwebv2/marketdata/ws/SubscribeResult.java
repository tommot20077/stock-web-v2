package dowob.xyz.stockwebv2.marketdata.ws;

import java.util.List;
import java.util.Objects;

/**
 * {@link SubscriptionManager#subscribe} 的回傳結果，區分本次接受與被拒絕的 channels。
 *
 * <p>兩個 list 皆為不可變（immutable），確保執行緒安全的傳遞。
 *
 * @param accepted 本次新加入的 channels（不含已訂閱的重複項）
 * @param rejected 被拒絕的 channels 及拒絕原因（例如超過上限）
 *
 * @author Yuan
 * @version 1.0.0
 */
public record SubscribeResult(
    List<String> accepted,
    List<RejectedChannel> rejected
) {

    /**
     * compact constructor：確保 list 為不可變副本。
     */
    public SubscribeResult {
        accepted = List.copyOf(Objects.requireNonNull(accepted, "accepted"));
        rejected = List.copyOf(Objects.requireNonNull(rejected, "rejected"));
    }

    /**
     * 被拒絕的 channel 資訊。
     *
     * @param channel 被拒絕的 channel 名稱
     * @param reason  拒絕原因（例如 {@code "SUBSCRIPTION_LIMIT_EXCEEDED"}）
     */
    public record RejectedChannel(String channel, String reason) {}
}
