package dowob.xyz.stockwebv2.infrastructure.search;

import java.util.List;

/**
 * 搜尋服務介面 —— <strong>預留擴充點,目前沒有實作也沒有呼叫者</strong>(2026-09-28 Yuan 決定保留)。
 *
 * <p>原始設計(2026-03 分階段引入):搜尋第一階段用 PostgreSQL ILIKE,Phase 3 換 Elasticsearch,呼叫端不需改動。
 * 新功能<strong>不要</strong>為了使用它而使用它;真正需要時再依當時需求實作並更新
 * {@code ai-docs/architecture.md} 的 Abstraction Layer 表。
 *
 * @author Yuan
 * @version 1.0.0
 */
public interface SearchService<T> {
    List<T> search(String query, int limit);
}
