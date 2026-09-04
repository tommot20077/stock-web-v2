package dowob.xyz.stockwebv2.marketdata.config;

import dowob.xyz.stockwebv2.marketdata.consumer.PriceWriterConsumer;
import dowob.xyz.stockwebv2.marketdata.consumer.WsBroadcastConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.support.Acknowledgment;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Kafka listener 的 offset commit 保證。
 *
 * <p>本檔存在的理由是一個實際發生過的缺陷：{@code WsBroadcastConsumer} 沒有指定
 * container factory，因而繼承 {@code application.yaml} 的全域
 * {@code spring.kafka.listener.ack-mode: manual}；而它的方法簽章沒有
 * {@link Acknowledgment} 參數，也就永遠不會 commit offset。搭配
 * {@code auto-offset-reset: earliest}，consumer group {@code market-data.ws-broadcast}
 * 在**每次應用重啟**都會從最舊的 offset 重播整個 tick topic —— Redis latest cache 被舊價
 * 逐筆覆寫，WS 客戶端收到歷史 tick 洪流。
 *
 * <p>因此這裡鎖的不變量是：**每個 {@code @KafkaListener} 都必須有辦法 commit offset** ——
 * 要嘛容器的 ack mode 會自動 commit，要嘛方法自己收 {@link Acknowledgment} 並負責 ack。
 * 兩者皆無就是「永不 commit」。
 *
 * @author Yuan
 * @version 1.0.0
 */
@DisplayName("Kafka listener 的 offset commit 保證")
class KafkaAckModeTest {

    private final KafkaConfig config = new KafkaConfig();

    /** 不會自動 commit offset 的 ack mode：只能靠 listener 自己呼叫 Acknowledgment。 */
    private static final List<AckMode> MANUAL_MODES = List.of(AckMode.MANUAL, AckMode.MANUAL_IMMEDIATE);

    @Test
    @DisplayName("WS 廣播用的 container factory 以 RECORD 模式自動 commit offset，重啟不重播")
    void wsBroadcastFactoryCommitsOffsetsPerRecord() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                config.wsBroadcastKafkaListenerContainerFactory(mock(), mock());

        AckMode ackMode = factory.getContainerProperties().getAckMode();

        assertThat(ackMode)
                .as("WS 廣播 listener 不收 Acknowledgment，容器必須自己 commit")
                .isEqualTo(AckMode.RECORD);
        assertThat(factory.isBatchListener())
                .as("WS 廣播是單筆消費，不是批次")
                .isNotEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("批次寫入用的 container factory 維持 BATCH 模式，整批成功後 commit")
    void batchFactoryCommitsOffsetsPerBatch() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                config.batchKafkaListenerContainerFactory(mock(), mock());

        assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(AckMode.BATCH);
        assertThat(factory.isBatchListener()).isTrue();
    }

    @Test
    @DisplayName("每個 @KafkaListener 都指定了會自動 commit 的 factory，或自己收 Acknowledgment")
    void everyListenerCanCommitItsOffset() {
        for (Class<?> consumer : List.of(WsBroadcastConsumer.class, PriceWriterConsumer.class)) {
            for (Method method : consumer.getDeclaredMethods()) {
                KafkaListener annotation = method.getAnnotation(KafkaListener.class);
                if (annotation == null) {
                    continue;
                }

                boolean takesAcknowledgment = Arrays.asList(method.getParameterTypes()).contains(Acknowledgment.class);
                String factoryName = annotation.containerFactory();

                assertThat(takesAcknowledgment || !factoryName.isBlank())
                        .as("%s.%s：既沒指定 container factory（會繼承全域 ack-mode），"
                                        + "又沒收 Acknowledgment —— 這個 listener 永遠不會 commit offset",
                                consumer.getSimpleName(), method.getName())
                        .isTrue();

                if (!takesAcknowledgment) {
                    AckMode ackMode = ackModeOf(factoryName);
                    assertThat(ackMode)
                            .as("%s.%s 指定的 factory %s 是手動 ack 模式，但方法沒收 Acknowledgment",
                                    consumer.getSimpleName(), method.getName(), factoryName)
                            .isNotIn(MANUAL_MODES);
                }
            }
        }
    }

    /**
     * 依 factory bean 名稱取出它實際設定的 ack mode。
     *
     * @param factoryName {@code @KafkaListener(containerFactory = ...)} 宣告的 bean 名稱
     * @return 該 factory 的 ack mode
     */
    private AckMode ackModeOf(String factoryName) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = switch (factoryName) {
            case "wsBroadcastKafkaListenerContainerFactory" ->
                    config.wsBroadcastKafkaListenerContainerFactory(mock(), mock());
            case "batchKafkaListenerContainerFactory" ->
                    config.batchKafkaListenerContainerFactory(mock(), mock());
            default -> throw new AssertionError(
                    "未知的 container factory：" + factoryName + "。新增 factory 時請一併補進本測試。");
        };
        return factory.getContainerProperties().getAckMode();
    }
}
