package dowob.xyz.stockwebv2.marketdata.ws;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 維護 WebSocket session 與 channel 的雙向訂閱對映，執行緒安全（thread-safe）。
 *
 * <p>內部使用 forward map（{@code sessionId → channels}）及
 * reverse map（{@code channel → sessionIds}）雙向維護，確保查詢效率。
 *
 * <p>每個連線最多訂閱 {@link #MAX_CHANNELS_PER_SESSION} 個 channels。
 * 同一 session 重複訂閱已存在的 channel 視為 no-op，不計入 accepted 也不進入 rejected。
 *
 * <p>執行緒安全策略：所有對 forward/reverse map 的複合變更（read-modify-write）
 * 均在 {@code writeLock} synchronized block 內執行，避免兩個 map 之間的不一致。
 * 讀取操作（{@link #subscribersOf}、{@link #channelsOf}）回傳 immutable snapshot，
 * 無需鎖定即可安全在 broadcast thread 中使用。
 *
 * @author Yuan
 * @version 1.0.0
 */
@Component
public class SubscriptionManager {

    /** 每個 session 最多可訂閱的 channel 數量上限。 */
    public static final int MAX_CHANNELS_PER_SESSION = 10;

    /** forward map：sessionId → 不可變 channel set（snapshot）。 */
    private final ConcurrentMap<String, Set<String>> sessionToChannels = new ConcurrentHashMap<>();

    /**
     * reverse map：channel → 可並發修改的 session id set。
     *
     * <p>使用 {@link ConcurrentHashMap#newKeySet()} 產生的 set，本身支援並發讀，
     * 但修改仍在 writeLock 內進行，確保與 forward map 一致。
     */
    private final ConcurrentMap<String, Set<String>> channelToSessions = new ConcurrentHashMap<>();

    /**
     * 全域寫入鎖：保護 forward + reverse map 的複合變更原子性。
     *
     * <p>選擇 global lock 而非 per-session lock 的原因：
     * reverse map 的 channel entry 可能被多個 session 同時修改，
     * per-session lock 難以防止 channel 層級的 race，而訂閱操作頻率低，
     * 全域鎖的競爭成本可接受。
     */
    private final Object writeLock = new Object();

    /**
     * 嘗試讓 sessionId 訂閱指定的 channels，回傳本次接受與被拒項目。
     *
     * <p>規則：
     * <ol>
     *   <li>已訂閱的 channel 視為 no-op（不計入 accepted，不計入 rejected）</li>
     *   <li>若加入後總數超過 {@link #MAX_CHANNELS_PER_SESSION}，多餘者進入 rejected</li>
     * </ol>
     *
     * @param sessionId 目標 WebSocket session ID，不可 null
     * @param channels  欲訂閱的 channel 名稱集合，不可 null
     * @return 包含 accepted 與 rejected 清單的結果物件
     * @throws NullPointerException 若 sessionId 或 channels 為 null
     */
    public SubscribeResult subscribe(String sessionId, Collection<String> channels) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(channels, "channels must not be null");

        List<String> accepted = new ArrayList<>();
        List<SubscribeResult.RejectedChannel> rejected = new ArrayList<>();

        synchronized (writeLock) {
            Set<String> current = sessionToChannels.getOrDefault(sessionId, Set.of());
            Set<String> next = new HashSet<>(current);

            for (String ch : channels) {
                if (current.contains(ch)) {
                    // 重複訂閱：no-op，不加入 accepted 或 rejected
                    continue;
                }
                if (next.size() >= MAX_CHANNELS_PER_SESSION) {
                    rejected.add(new SubscribeResult.RejectedChannel(ch, "SUBSCRIPTION_LIMIT_EXCEEDED"));
                    continue;
                }
                next.add(ch);
                accepted.add(ch);
                channelToSessions.computeIfAbsent(ch, k -> ConcurrentHashMap.newKeySet()).add(sessionId);
            }

            if (!next.isEmpty()) {
                sessionToChannels.put(sessionId, Set.copyOf(next));
            }
        }

        return new SubscribeResult(accepted, rejected);
    }

    /**
     * 移除 sessionId 對指定 channels 的訂閱，並同步更新 reverse index。
     *
     * <p>若 session 或 channel 不存在，則靜默忽略（no-op）。
     *
     * @param sessionId 目標 WebSocket session ID，不可 null
     * @param channels  欲取消訂閱的 channel 名稱集合，不可 null
     * @throws NullPointerException 若 sessionId 或 channels 為 null
     */
    public void unsubscribe(String sessionId, Collection<String> channels) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(channels, "channels must not be null");

        synchronized (writeLock) {
            Set<String> current = sessionToChannels.get(sessionId);
            if (current == null) {
                return;
            }

            Set<String> next = new HashSet<>(current);
            for (String ch : channels) {
                if (next.remove(ch)) {
                    cleanReverseEntry(ch, sessionId);
                }
            }

            if (next.isEmpty()) {
                sessionToChannels.remove(sessionId);
            } else {
                sessionToChannels.put(sessionId, Set.copyOf(next));
            }
        }
    }

    /**
     * 回傳指定 channel 目前訂閱中的 session id 集合（immutable snapshot）。
     *
     * <p>此方法不需要持有鎖，呼叫者取得的是當時的快照，適合 broadcast thread 使用。
     *
     * @param channel channel 名稱
     * @return 訂閱中的 session id 集合；若無訂閱者則回傳空集合
     */
    public Set<String> subscribersOf(String channel) {
        Set<String> sessions = channelToSessions.get(channel);
        return sessions == null ? Set.of() : Set.copyOf(sessions);
    }

    /**
     * 回傳指定 session 目前訂閱中的 channel 集合（immutable snapshot）。
     *
     * @param sessionId WebSocket session ID
     * @return 訂閱中的 channel 集合；若 session 不存在則回傳空集合
     */
    public Set<String> channelsOf(String sessionId) {
        Set<String> channels = sessionToChannels.get(sessionId);
        // sessionToChannels 內的 value 已是 Set.copyOf（不可變），可直接回傳
        return channels == null ? Set.of() : channels;
    }

    /**
     * 移除指定 session 及其所有訂閱（用於 WebSocket disconnect 事件）。
     *
     * <p>同步清理 forward map 與 reverse map，確保兩者一致。
     *
     * @param sessionId 目標 WebSocket session ID，不可 null
     * @throws NullPointerException 若 sessionId 為 null
     */
    public void removeSession(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");

        synchronized (writeLock) {
            Set<String> channels = sessionToChannels.remove(sessionId);
            if (channels == null) {
                return;
            }
            for (String ch : channels) {
                cleanReverseEntry(ch, sessionId);
            }
        }
    }

    /**
     * 從 reverse map 移除指定 channel 的 sessionId；若 channel 已無訂閱者，則移除 channel entry。
     *
     * <p>必須在 {@code writeLock} 內呼叫。
     *
     * @param channel   channel 名稱
     * @param sessionId 欲移除的 session ID
     */
    private void cleanReverseEntry(String channel, String sessionId) {
        Set<String> sessions = channelToSessions.get(channel);
        if (sessions != null) {
            sessions.remove(sessionId);
            if (sessions.isEmpty()) {
                channelToSessions.remove(channel);
            }
        }
    }

    // ── 測試輔助方法 ──────────────────────────────────────────────────────────

    /**
     * 回傳目前 forward map 中的 session 數量（供測試驗證用）。
     *
     * @return session 數量
     */
    int sessionCount() {
        return sessionToChannels.size();
    }

    /**
     * 回傳目前 reverse map 中的 channel 數量（供測試驗證用）。
     *
     * @return channel 數量
     */
    int channelCount() {
        return channelToSessions.size();
    }
}
