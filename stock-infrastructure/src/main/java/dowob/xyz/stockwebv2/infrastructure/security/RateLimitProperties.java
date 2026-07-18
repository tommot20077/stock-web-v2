package dowob.xyz.stockwebv2.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

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
        enabled = enabled == null ? Boolean.TRUE : enabled;
        login = login == null ? new Rule(10, Duration.ofMinutes(1)) : login;
        register = register == null ? new Rule(5, Duration.ofHours(1)) : register;
        refresh = refresh == null ? new Rule(5, Duration.ofMinutes(1)) : refresh;
        lockout = lockout == null ? new Lockout(5, Duration.ofMinutes(15)) : lockout;
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
            limit = limit == null ? Integer.MAX_VALUE : limit;
            window = window == null ? Duration.ofMinutes(1) : window;
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
            threshold = threshold == null ? 5 : threshold;
            duration = duration == null ? Duration.ofMinutes(15) : duration;
        }
    }
}
