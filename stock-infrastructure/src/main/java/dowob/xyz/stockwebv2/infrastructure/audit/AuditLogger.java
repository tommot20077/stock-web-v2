package dowob.xyz.stockwebv2.infrastructure.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 稽核日誌輸出點（security.md §13）。
 *
 * <p>統一以固定格式 {@code [AUDIT] userId={} action={} target={} result={} ip={}} 寫入名為
 * {@code AUDIT} 的 SLF4J logger；該 logger 由 logback 設定導向專用的 {@code audit.log} appender。
 * 所有需稽核的事件（登入/登出、交易操作、權限變更、帳號狀態變更、管理操作）皆應透過本類別輸出，
 * 以確保格式一致、可被日誌管線解析。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Component
public class AuditLogger {

    /**
     * 專用稽核 logger，名稱固定為 {@code AUDIT}。
     */
    private static final Logger audit = LoggerFactory.getLogger("AUDIT");

    /**
     * 憲法規定的稽核格式（security.md §13）。
     */
    private static final String FORMAT = "[AUDIT] userId={} action={} target={} result={} ip={}";

    /**
     * 缺漏欄位的佔位符，避免輸出 {@code null}。
     */
    private static final String PLACEHOLDER = "-";

    /**
     * 輸出一筆稽核事件。
     *
     * @param userId 觸發事件的使用者 id；未知（如登入失敗且帳號不存在）時傳 {@code null}
     * @param action 動作名稱（如 {@code login} / {@code logout} / {@code trade_create}）
     * @param target 目標資源類型或識別（不得含機敏資料）
     * @param result 結果（{@code success} / {@code failure} / 具體結果字串）
     * @param ip     來源 IP；未知時傳 {@code null}
     */
    public void log(Long userId, String action, String target, String result, String ip) {
        audit.info(FORMAT, text(userId), text(action), text(target), text(result), text(ip));
    }

    /**
     * 將欄位值轉為字串，{@code null} 以佔位符取代。
     *
     * @param value 欄位值
     * @return 字串化的欄位值
     */
    private static String text(Object value) {
        return value == null ? PLACEHOLDER : String.valueOf(value);
    }
}
