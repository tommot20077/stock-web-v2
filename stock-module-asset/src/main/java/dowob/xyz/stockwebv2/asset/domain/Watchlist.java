package dowob.xyz.stockwebv2.asset.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 使用者的具名觀察清單（WATCH-01；D-14）。可變的使用者偏好，不是帳本。
 *
 * @param id        內部 id（不對外）
 * @param uuid      對外識別碼，API 路徑一律用它
 * @param userId    擁有者
 * @param name      清單名稱（trim 後 1–30 字，同使用者唯一）
 * @param createdAt 建立時間；清單之間依 created_at, id 排序
 * @author Yuan
 * @version 1.0
 */
public record Watchlist(Long id, UUID uuid, Long userId, String name, OffsetDateTime createdAt) {

    /** 使用者沒有任何清單時，{@code GET /api/v1/watchlists} 自動建立的預設清單名稱（D-17）。 */
    public static final String DEFAULT_NAME = "我的自選";
}
