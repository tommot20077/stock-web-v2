package dowob.xyz.stockwebv2.asset.api;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 觀察清單請求的 bean validation 欄位歸屬（04.2-12）。
 *
 * <p>{@code WatchlistControllerWebMvcTest} 的切片不含 stock-start 的 {@code GlobalExceptionHandler}，只能斷言 400；
 * 錯誤落在哪個 {@code fields} 鍵取決於違規的 property path（{@code handleValidation} 以 {@code FieldError#getField()}
 * 為鍵）。這裡直接鎖住 property path，確保契約的 {@code fields.name} / {@code fields.symbol} / {@code fields.assetIds}。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("觀察清單請求：bean validation 欄位歸屬")
class WatchlistRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("WatchlistNameRequest：空字串、全空白、null → 違規指名 name；31 字不在 bean validation 擋（service 以 code point 判）")
    void nameRequestNamesName() {
        assertThat(violatedFields(new WatchlistNameRequest(""))).containsExactly("name");
        assertThat(violatedFields(new WatchlistNameRequest("   "))).containsExactly("name");
        assertThat(violatedFields(new WatchlistNameRequest(null))).containsExactly("name");
        assertThat(violatedFields(new WatchlistNameRequest("a".repeat(31)))).isEmpty();
    }

    @Test
    @DisplayName("AddWatchlistItemRequest：空白、null、33 字 → 違規指名 symbol；USD/TWD 與 32 字合法")
    void addItemRequestNamesSymbol() {
        assertThat(violatedFields(new AddWatchlistItemRequest(""))).containsExactly("symbol");
        assertThat(violatedFields(new AddWatchlistItemRequest(" "))).containsExactly("symbol");
        assertThat(violatedFields(new AddWatchlistItemRequest(null))).containsExactly("symbol");
        assertThat(violatedFields(new AddWatchlistItemRequest("S".repeat(33)))).containsExactly("symbol");
        assertThat(violatedFields(new AddWatchlistItemRequest("USD/TWD"))).isEmpty();
        assertThat(violatedFields(new AddWatchlistItemRequest("S".repeat(32)))).isEmpty();
    }

    @Test
    @DisplayName("ReorderWatchlistItemsRequest：null、101 個 → 違規指名 assetIds；空陣列與 100 個合法")
    void reorderRequestNamesAssetIds() {
        assertThat(violatedFields(new ReorderWatchlistItemsRequest(null))).containsExactly("assetIds");
        assertThat(violatedFields(new ReorderWatchlistItemsRequest(uuids(101)))).containsExactly("assetIds");
        assertThat(violatedFields(new ReorderWatchlistItemsRequest(List.of()))).isEmpty();
        assertThat(violatedFields(new ReorderWatchlistItemsRequest(uuids(100)))).isEmpty();
    }

    private static List<UUID> uuids(int count) {
        List<UUID> ids = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            ids.add(UUID.randomUUID());
        }
        return ids;
    }

    private <T> Set<String> violatedFields(T request) {
        return validator.validate(request).stream()
            .map(ConstraintViolation::getPropertyPath)
            .map(Object::toString)
            .collect(Collectors.toSet());
    }
}
