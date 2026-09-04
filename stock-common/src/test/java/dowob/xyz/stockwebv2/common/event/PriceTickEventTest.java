package dowob.xyz.stockwebv2.common.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PriceTickEvent} record 的單元測試。
 *
 * @author Yuan
 * @version 1.0.0
 */
class PriceTickEventTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        // 確保 Instant 序列化為 ISO-8601 字串，而非 Unix 時間戳陣列
        mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    // ── of() factory tests ─────────────────────────────────────────────────

    @Test
    void factory_generatesNonNullEventId() {
        PriceTickEvent event = PriceTickEvent.of(1L, "AAPL", new BigDecimal("150.00"), new BigDecimal("1000"), "mock");
        assertThat(event.eventId()).isNotNull().isNotEmpty();
    }

    @Test
    void factory_eventIdIsUuidFormat() {
        PriceTickEvent event = PriceTickEvent.of(1L, "AAPL", new BigDecimal("150.00"), new BigDecimal("1000"), "mock");
        // UUID format: 8-4-4-4-12 hex chars
        assertThat(event.eventId()).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void factory_occurredAtIsRecent() {
        Instant before = Instant.now().minusSeconds(1);
        PriceTickEvent event = PriceTickEvent.of(1L, "AAPL", new BigDecimal("150.00"), new BigDecimal("1000"), "mock");
        Instant after = Instant.now().plusSeconds(1);

        assertThat(event.occurredAt()).isAfter(before).isBefore(after);
    }

    @Test
    void factory_volumeNullIsAllowed() {
        PriceTickEvent event = PriceTickEvent.of(1L, "AAPL", new BigDecimal("150.00"), null, "mock");
        assertThat(event.volume()).isNull();
    }

    @Test
    void factory_fieldsPropagatedCorrectly() {
        BigDecimal price = new BigDecimal("150.25");
        BigDecimal volume = new BigDecimal("500.0");
        PriceTickEvent event = PriceTickEvent.of(2L, "NVDA", price, volume, "backfill");

        assertThat(event.assetId()).isEqualTo(2L);
        assertThat(event.symbol()).isEqualTo("NVDA");
        assertThat(event.price()).isEqualByComparingTo(price);
        assertThat(event.volume()).isEqualByComparingTo(volume);
        assertThat(event.source()).isEqualTo("backfill");
    }

    // ── compact constructor null-checks ────────────────────────────────────

    @Test
    void constructor_nullEventId_throwsException() {
        assertThatThrownBy(() -> new PriceTickEvent(null, Instant.now(), 1L, "AAPL",
                new BigDecimal("100"), new BigDecimal("10"), "mock"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_nullOccurredAt_throwsException() {
        assertThatThrownBy(() -> new PriceTickEvent("some-id", null, 1L, "AAPL",
                new BigDecimal("100"), new BigDecimal("10"), "mock"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_nullAssetId_throwsException() {
        assertThatThrownBy(() -> new PriceTickEvent("some-id", Instant.now(), null, "AAPL",
                new BigDecimal("100"), new BigDecimal("10"), "mock"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_nullSymbol_throwsException() {
        assertThatThrownBy(() -> new PriceTickEvent("some-id", Instant.now(), 1L, null,
                new BigDecimal("100"), new BigDecimal("10"), "mock"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_nullPrice_throwsException() {
        assertThatThrownBy(() -> new PriceTickEvent("some-id", Instant.now(), 1L, "AAPL",
                null, new BigDecimal("10"), "mock"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_nullSource_throwsException() {
        assertThatThrownBy(() -> new PriceTickEvent("some-id", Instant.now(), 1L, "AAPL",
                new BigDecimal("100"), new BigDecimal("10"), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_nullVolume_isAllowed() {
        PriceTickEvent event = new PriceTickEvent("some-id", Instant.now(), 1L, "AAPL",
                new BigDecimal("100"), null, "mock");
        assertThat(event.volume()).isNull();
    }

    // ── JSON serialization tests ───────────────────────────────────────────

    @Test
    void serialize_priceIsJsonString_notNumber() throws Exception {
        PriceTickEvent event = PriceTickEvent.of(1L, "AAPL", new BigDecimal("150.999999999"), new BigDecimal("1000"), "mock");
        String json = mapper.writeValueAsString(event);
        JsonNode root = mapper.readTree(json);

        assertThat(root.get("price").isTextual())
                .as("price should be serialized as JSON string to preserve BigDecimal precision")
                .isTrue();
    }

    @Test
    void serialize_volumeIsJsonString_notNumber() throws Exception {
        PriceTickEvent event = PriceTickEvent.of(1L, "AAPL", new BigDecimal("150.00"), new BigDecimal("999999.999"), "mock");
        String json = mapper.writeValueAsString(event);
        JsonNode root = mapper.readTree(json);

        assertThat(root.get("volume").isTextual())
                .as("volume should be serialized as JSON string to preserve BigDecimal precision")
                .isTrue();
    }

    @Test
    void serialize_occurredAtIsIso8601String() throws Exception {
        PriceTickEvent event = PriceTickEvent.of(1L, "AAPL", new BigDecimal("150.00"), null, "mock");
        String json = mapper.writeValueAsString(event);
        JsonNode root = mapper.readTree(json);

        JsonNode occurredAt = root.get("occurredAt");
        assertThat(occurredAt.isTextual())
                .as("occurredAt should be serialized as ISO-8601 string")
                .isTrue();
        // ISO-8601 instant format contains 'T' and 'Z'
        assertThat(occurredAt.asText()).contains("T");
    }

    @Test
    void serialize_volumeNullRoundTrip_remainsNull() throws Exception {
        PriceTickEvent event = PriceTickEvent.of(1L, "AAPL", new BigDecimal("150.00"), null, "mock");
        String json = mapper.writeValueAsString(event);
        JsonNode root = mapper.readTree(json);

        assertThat(root.get("volume").isNull()).isTrue();
    }

    @Test
    void roundTrip_deserializedEventEqualsOriginal() throws Exception {
        BigDecimal price = new BigDecimal("150.25");
        BigDecimal volume = new BigDecimal("1234.5");
        PriceTickEvent original = PriceTickEvent.of(42L, "TSLA", price, volume, "mock");

        String json = mapper.writeValueAsString(original);
        PriceTickEvent restored = mapper.readValue(json, PriceTickEvent.class);

        assertThat(restored.eventId()).isEqualTo(original.eventId());
        assertThat(restored.assetId()).isEqualTo(original.assetId());
        assertThat(restored.symbol()).isEqualTo(original.symbol());
        assertThat(restored.price()).isEqualByComparingTo(original.price());
        assertThat(restored.volume()).isEqualByComparingTo(original.volume());
        assertThat(restored.source()).isEqualTo(original.source());
        assertThat(restored.occurredAt()).isEqualTo(original.occurredAt());
    }
}
