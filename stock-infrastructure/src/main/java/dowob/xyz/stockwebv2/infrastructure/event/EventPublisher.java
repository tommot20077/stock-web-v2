package dowob.xyz.stockwebv2.infrastructure.event;

public interface EventPublisher {
    void publish(DomainEvent event);
}
