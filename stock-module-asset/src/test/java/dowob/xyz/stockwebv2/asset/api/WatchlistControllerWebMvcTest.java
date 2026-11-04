package dowob.xyz.stockwebv2.asset.api;

import dowob.xyz.stockwebv2.asset.service.WatchlistService;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.model.AssetType;
import dowob.xyz.stockwebv2.common.model.CurrencyCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link WatchlistController} 的 WebMvc 切片測試：請求綁定與 bean validation 的 400、非 UUID 路徑的 400、
 * service 例外的傳遞，以及成功回應的信封形狀。
 *
 * <p><strong>能力界線</strong>（與 trading 模組 {@code CashLedgerControllerWebMvcTest} 相同）：
 * {@code GlobalExceptionHandler} 位於 {@code stock-start}，asset 模組不能依賴它，不在本切片內。因此驗證失敗時
 * 這裡只能斷言 Spring 預設解析器給的 400 與「service 無互動」，不能斷言錯誤信封的 {@code error.code} /
 * {@code error.fields}；欄位歸屬由 {@code WatchlistRequestValidationTest} 鎖住 property path（handler 以
 * {@code FieldError#getField()} 為鍵），信封的權威驗收在 {@code stock-start} 的 {@code WatchlistApiIT}。
 * service 拋出的 {@link BusinessException} 在切片內不會被轉成回應，會以 root cause 傳出——這裡斷言它原樣傳遞、
 * 訊息不含請求的 UUID。</p>
 *
 * <p>安全設定以 {@link OpenSecurityConfig} 替換：本切片的目的不是驗證授權（{@code @PreAuthorize} 由
 * {@code EndpointAuthorizationRulesTest} 與 IT 覆蓋），預設鏈會先以 401 擋掉所有請求。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@WebMvcTest(WatchlistController.class)
@Import(WatchlistControllerWebMvcTest.OpenSecurityConfig.class)
@DisplayName("觀察清單端點：請求驗證、路徑綁定與例外傳遞")
class WatchlistControllerWebMvcTest {

    private static final UUID LIST_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    WatchlistService watchlistService;

    @Test
    @DisplayName("POST /watchlists {name:\"\"} 與缺 name → 400（@NotBlank），service 無互動")
    void blankNameIsRejectedAtBinding() throws Exception {
        for (String body : List.of("{\"name\":\"\"}", "{\"name\":\"   \"}", "{}")) {
            mvc.perform(post("/api/v1/watchlists")
                    .with(user("42"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(watchlistService);
    }

    @Test
    @DisplayName("POST /watchlists {name: 31 字} → 原樣交給 service（長度以 strip 後 code point 計，由 service 判 400 fields.name）")
    void overlongNameIsDelegatedToServiceLengthRule() throws Exception {
        String name = "a".repeat(31);
        when(watchlistService.create(42L, name)).thenThrow(new BusinessException(
            ErrorCode.VALIDATION_FAILED, "Watchlist name is invalid", Map.of("name", "must be 1 to 30 characters")));

        assertThatThrownBy(() -> mvc.perform(post("/api/v1/watchlists")
                .with(user("42"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\"}")))
            .rootCause()
            .isInstanceOfSatisfying(BusinessException.class, exception -> {
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                assertThat(exception.fields()).containsOnlyKeys("name");
            });
        verify(watchlistService).create(42L, name);
    }

    @Test
    @DisplayName("POST items {symbol:\"\"}、缺 symbol、33 字 → 400，service 無互動")
    void invalidSymbolIsRejectedAtBinding() throws Exception {
        for (String body : List.of("{\"symbol\":\"\"}", "{}", "{\"symbol\":\"" + "S".repeat(33) + "\"}")) {
            mvc.perform(post("/api/v1/watchlists/" + LIST_ID + "/items")
                    .with(user("42"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(watchlistService);
    }

    @Test
    @DisplayName("POST items 成功 → 200 信封 success=true，data 為 WatchlistDto（symbol 經 body 傳入，FX 的「/」不進路徑）")
    void addItemReturnsWatchlistEnvelope() throws Exception {
        AssetDto usdTwd = new AssetDto(UUID.randomUUID(), "USD/TWD", "美元/新台幣", AssetType.FX, "FX", CurrencyCode.TWD, null, false,
            null, null, null, null, null, null);
        when(watchlistService.addItem(42L, LIST_ID, "USD/TWD"))
            .thenReturn(new WatchlistDto(LIST_ID, "我的自選", List.of(usdTwd)));

        mvc.perform(post("/api/v1/watchlists/" + LIST_ID + "/items")
                .with(user("42"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"symbol\":\"USD/TWD\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", equalTo(true)))
            .andExpect(jsonPath("$.data.id", equalTo(LIST_ID.toString())))
            .andExpect(jsonPath("$.data.items[0].symbol", equalTo("USD/TWD")))
            .andExpect(jsonPath("$.data.items[0].tradeable", equalTo(false)));
    }

    @Test
    @DisplayName("PUT items/order：assetIds 為 null / 缺漏 / 101 個 / 含非 UUID 字串 → 400，service 無互動")
    void invalidReorderBodyIsRejectedAtBinding() throws Exception {
        List<String> bodies = List.of(
            "{\"assetIds\":null}",
            "{}",
            "{\"assetIds\":" + uuidArray(101) + "}",
            "{\"assetIds\":[\"not-a-uuid\"]}"
        );
        for (String body : bodies) {
            mvc.perform(put("/api/v1/watchlists/" + LIST_ID + "/items/order")
                    .with(user("42"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(watchlistService);
    }

    @Test
    @DisplayName("PUT items/order：100 個 UUID → 抵達 service（上限邊界）")
    void hundredAssetIdsReachService() throws Exception {
        when(watchlistService.reorder(eq(42L), eq(LIST_ID), anyList()))
            .thenReturn(new WatchlistDto(LIST_ID, "我的自選", List.of()));

        mvc.perform(put("/api/v1/watchlists/" + LIST_ID + "/items/order")
                .with(user("42"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"assetIds\":" + uuidArray(100) + "}"))
            .andExpect(status().isOk());

        verify(watchlistService).reorder(eq(42L), eq(LIST_ID), anyList());
    }

    @Test
    @DisplayName("非 UUID 路徑（PATCH /watchlists/not-a-uuid、POST /not-a-uuid/items、DELETE items/not-a-uuid）→ 400，不是 500")
    void nonUuidPathIsBadRequest() throws Exception {
        mvc.perform(patch("/api/v1/watchlists/not-a-uuid")
                .with(user("42"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"任意\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/watchlists/not-a-uuid/items")
                .with(user("42"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"symbol\":\"AAPL\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/watchlists/" + LIST_ID + "/items/not-a-uuid")
                .with(user("42")))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(watchlistService);
    }

    @Test
    @DisplayName("service 拋 WATCHLIST_NOT_FOUND → 原樣傳出（交由 GlobalExceptionHandler 轉 404），訊息不含請求的 UUID")
    void watchlistNotFoundPropagatesWithoutEchoingUuid() {
        UUID requested = UUID.fromString("22222222-2222-2222-2222-222222222222");
        when(watchlistService.addItem(42L, requested, "AAPL")).thenThrow(new BusinessException(
            ErrorCode.WATCHLIST_NOT_FOUND, ErrorCode.WATCHLIST_NOT_FOUND.defaultMessage()));

        assertThatThrownBy(() -> mvc.perform(post("/api/v1/watchlists/" + requested + "/items")
                .with(user("42"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"symbol\":\"AAPL\"}")))
            .rootCause()
            .isInstanceOfSatisfying(BusinessException.class, exception -> {
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.WATCHLIST_NOT_FOUND);
                assertThat(exception.errorCode().httpStatus()).isEqualTo(404);
                assertThat(exception.getMessage()).doesNotContain(requested.toString());
            });
    }

    private static String uuidArray(int count) {
        List<String> ids = new ArrayList<>();
        IntStream.range(0, count).forEach(index -> ids.add("\"" + UUID.randomUUID() + "\""));
        return ids.stream().collect(Collectors.joining(",", "[", "]"));
    }

    /**
     * 對所有請求放行的測試用安全設定，取代切片預設的「一律要求驗證」鏈。
     * CSRF 一併關閉：本切片測的是請求綁定，CSRF 的真實行為由 {@code WatchlistApiIT} 覆蓋。
     */
    @TestConfiguration
    static class OpenSecurityConfig {
        @Bean
        SecurityFilterChain openFilterChain(HttpSecurity http) throws Exception {
            return http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
        }
    }
}
