package dowob.xyz.stockwebv2.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

/**
 * 認證端點限流與帳號鎖定的可設定參數（security.md §15）。預設值即憲法規定值；
 * 各環境可覆寫，{@code enabled=false} 可整體停用（供測試套件避免 per-IP 計數互相污染）。
 *
 * @param enabled  是否啟用限流與帳號鎖定
 * @param login    登入端點限流規則（預設 10 次 / 分鐘 / IP）
 * @param register 註冊端點限流規則（預設 5 次 / 小時 / IP）
 * @param refresh  換發 token 端點限流規則（預設 5 次 / 分鐘 / 使用者）
 * @param lockout  帳號鎖定規則（預設連續 5 次失敗鎖定 15 分鐘）
 * @author Yuan
 * @version 1.0
 */
@ConfigurationProperties(prefix = "stock.security.rate-limit")
public record RateLimitProperties(
    Boolean enabled,
    Rule login,
    Rule register,
    Rule refresh,
    Lockout lockout
) {

    public RateLimitProperties {
        // 常數預設用 requireNonNullElse;需要 new 的預設用 requireNonNullElseGet,
        // 後者的 supplier 僅在值為 null 時才求值,避免值已存在時仍白建物件。
        enabled = Objects.requireNonNullElse(enabled, Boolean.TRUE);
        login = Objects.requireNonNullElseGet(login, () -> new Rule(10, Duration.ofMinutes(1)));
        register = Objects.requireNonNullElseGet(register, () -> new Rule(5, Duration.ofHours(1)));
        refresh = Objects.requireNonNullElseGet(refresh, () -> new Rule(5, Duration.ofMinutes(1)));
        lockout = Objects.requireNonNullElseGet(lockout, () -> new Lockout(5, Duration.ofMinutes(15)));
    }

    /**
     * @return 是否啟用限流與帳號鎖定
     */
    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    /**
     * 固定視窗限流規則。
     *
     * @param limit  視窗內允許的最大請求數
     * @param window 視窗長度
     */
    public record Rule(Integer limit, Duration window) {
        public Rule {
            limit = Objects.requireNonNullElse(limit, Integer.MAX_VALUE);
            window = Objects.requireNonNullElseGet(window, () -> Duration.ofMinutes(1));
        }
    }

    /**
     * 帳號鎖定規則。
     *
     * @param threshold 觸發鎖定的連續失敗次數
     * @param duration  鎖定持續時間
     */
    public record Lockout(Integer threshold, Duration duration) {
        public Lockout {
            threshold = Objects.requireNonNullElse(threshold, 5);
            duration = Objects.requireNonNullElseGet(duration, () -> Duration.ofMinutes(15));
        }
    }
}
