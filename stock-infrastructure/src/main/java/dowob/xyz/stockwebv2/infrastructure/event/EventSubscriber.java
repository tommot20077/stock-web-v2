package dowob.xyz.stockwebv2.infrastructure.event;

public interface EventSubscriber<T extends DomainEvent> {
    void handle(T event);
}
