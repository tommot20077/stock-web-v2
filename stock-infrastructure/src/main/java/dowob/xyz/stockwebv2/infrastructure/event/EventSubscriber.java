package dowob.xyz.stockwebv2.infrastructure.event;

/**
 * 領域事件訂閱介面 —— <strong>預留擴充點,目前沒有實作也沒有呼叫者</strong>(2026-09-28 Yuan 決定保留)。
 *
 * <p>原始設計(2026-03 分階段引入):業務模組只呼叫 publish,底層由 Spring 內部事件演進到 Kafka。實際發展中行情改走 KafkaTemplate、持倉估值改為讀時計算,目前沒有業務事件需要發布。
 * 新功能<strong>不要</strong>為了使用它而使用它;真正需要時再依當時需求實作並更新
 * {@code ai-docs/architecture.md} 的 Abstraction Layer 表。
 *
 * @author Yuan
 * @version 1.0.0
 */
public interface EventSubscriber<T extends DomainEvent> {
    void handle(T event);
}
