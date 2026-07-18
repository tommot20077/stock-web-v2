package dowob.xyz.stockwebv2.marketdata.ws;

import dowob.xyz.stockwebv2.marketdata.config.WebSocketLimitProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket 連線治理：追蹤全域、每 IP、每帳號的活躍連線數，實作 security.md §18 的連線上限。
 *
 * <ul>
 *   <li>全域上限（預設 1000）：{@link #globalLimitReached()} 於 handshake 檢查，超過回 503</li>
 *   <li>每 IP 上限（預設 5）：{@link #ipLimitReached(String)} 於 handshake 檢查，超過回 429</li>
 *   <li>每帳號上限（預設 2）：{@link #register(String, Long, String)} 於連線建立時 FIFO 驅逐最舊 session</li>
 * </ul>
 *
 * <p>計數於 {@link #register} 遞增、{@link #unregister} 遞減。handshake 的上限檢查為讀取快照，
 * 與實際遞增之間存在極小競態，屬 DoS 軟性防護可接受範圍。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Component
public class WebSocketConnectionManager {

    private final int maxGlobal;
    private final int maxPerIp;
    private final int maxPerAccount;

    /**
     * 目前全域活躍連線總數。
     */
    private final AtomicInteger globalCount = new AtomicInteger();

    /**
     * 每個來源 IP 的活躍連線計數：ip → count。
     */
    private final ConcurrentMap<String, AtomicInteger> perIpCounts = new ConcurrentHashMap<>();

    /**
     * 每個帳號的活躍 session id（依建立順序，供 FIFO 驅逐）：userId → ordered sessionIds。
     */
    private final ConcurrentMap<Long, Deque<String>> perAccountSessions = new ConcurrentHashMap<>();

    /**
     * 每個 session 的登錄資訊，供關閉時遞減對應計數：sessionId → registration。
     */
    private final ConcurrentMap<String, Registration> registrations = new ConcurrentHashMap<>();

    public WebSocketConnectionManager(WebSocketLimitProperties properties) {
        this.maxGlobal = properties.maxConnectionsGlobal();
        this.maxPerIp = properties.maxConnectionsPerIp();
        this.maxPerAccount = properties.maxConnectionsPerAccount();
    }

    /**
     * @return 目前全域活躍連線數是否已達上限
     */
    public boolean globalLimitReached() {
        return globalCount.get() >= maxGlobal;
    }

    /**
     * @param ip 來源 IP
     * @return 該 IP 的活躍連線數是否已達上限
     */
    public boolean ipLimitReached(String ip) {
        AtomicInteger count = perIpCounts.get(ip);
        return count != null && count.get() >= maxPerIp;
    }

    /**
     * 登錄一個新建立的連線並遞增全域 / 每 IP / 每帳號計數；若該帳號連線數超過上限，
     * 回傳需以 FIFO 驅逐（關閉）的最舊 session id 清單。
     *
     * @param sessionId 新連線的 session id
     * @param userId    連線所屬使用者 id（可為 null）
     * @param ip        來源 IP
     * @return 需驅逐的 session id 清單（通常為空或單一）
     */
    public List<String> register(String sessionId, Long userId, String ip) {
        String ipKey = ip == null ? "unknown" : ip;
        globalCount.incrementAndGet();
        perIpCounts.computeIfAbsent(ipKey, key -> new AtomicInteger()).incrementAndGet();
        registrations.put(sessionId, new Registration(userId, ipKey));

        List<String> evicted = new ArrayList<>();
        if (userId != null) {
            Deque<String> sessionIds = perAccountSessions.computeIfAbsent(userId, key -> new ArrayDeque<>());
            synchronized (sessionIds) {
                sessionIds.addLast(sessionId);
                while (sessionIds.size() > maxPerAccount) {
                    evicted.add(sessionIds.pollFirst());
                }
            }
        }
        return evicted;
    }

    /**
     * 註銷一個已關閉的連線並遞減對應計數。
     *
     * @param sessionId 已關閉的 session id
     */
    public void unregister(String sessionId) {
        Registration registration = registrations.remove(sessionId);
        if (registration == null) {
            return;
        }
        globalCount.decrementAndGet();

        AtomicInteger count = perIpCounts.get(registration.ip());
        if (count != null && count.decrementAndGet() <= 0) {
            perIpCounts.remove(registration.ip(), count);
        }

        if (registration.userId() != null) {
            Deque<String> sessionIds = perAccountSessions.get(registration.userId());
            if (sessionIds != null) {
                synchronized (sessionIds) {
                    sessionIds.remove(sessionId);
                    if (sessionIds.isEmpty()) {
                        perAccountSessions.remove(registration.userId(), sessionIds);
                    }
                }
            }
        }
    }

    /**
     * @return 目前全域活躍連線數
     */
    public int activeConnections() {
        return globalCount.get();
    }

    /**
     * 單一連線的登錄資訊。
     *
     * @param userId 所屬使用者 id（可為 null）
     * @param ip     來源 IP
     */
    private record Registration(Long userId, String ip) {
    }
}
