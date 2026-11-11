package dowob.xyz.stockwebv2.trading.api;

import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.FieldValidationException;
import dowob.xyz.stockwebv2.infrastructure.asset.AssetFacade;
import dowob.xyz.stockwebv2.trading.domain.ActivityKind;
import dowob.xyz.stockwebv2.trading.domain.ActivitySortKey;
import dowob.xyz.stockwebv2.trading.domain.ActivityTypeFilter;
import dowob.xyz.stockwebv2.trading.domain.SortDirection;
import dowob.xyz.stockwebv2.trading.repository.ActivityRepository;
import dowob.xyz.stockwebv2.trading.repository.ActivityRepository.ActivityQuery;
import dowob.xyz.stockwebv2.trading.repository.ActivityRepository.ActivityRow;
import dowob.xyz.stockwebv2.trading.service.ActivityQueryService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link PortfolioActivityController} 的 WebMvc 切片測試（PORT-13）：參數綁定、白名單錯誤與回應形狀。
 *
 * <p>切片內放<strong>真的</strong> {@link ActivityQueryService}（只 mock repository 與 AssetFacade），
 * 參數解析因此真的被執行：白名單外的 type / sort、非數字的 page / size 會以 {@link FieldValidationException}
 * 擋在任何 I/O 之前，合法參數則以型別化的 {@link ActivityQuery} 抵達 repository。</p>
 *
 * <p><strong>能力界線</strong>（同 {@code ShareAdjustmentControllerWebMvcTest}）：{@code GlobalExceptionHandler}
 * 位於 {@code stock-start}，不在本切片內，例外會原樣拋出，因此這裡斷言例外的 {@code fields}；錯誤信封的
 * HTTP 400 / {@code error.fields} 由 {@code PortfolioActivityApiIT} 驗收。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@WebMvcTest(PortfolioActivityController.class)
@Import({ActivityQueryService.class, PortfolioActivityControllerWebMvcTest.OpenSecurityConfig.class})
@DisplayName("活動列表端點：參數綁定、白名單與回應形狀")
class PortfolioActivityControllerWebMvcTest {

    private static final String PATH = "/api/v1/portfolio/activities";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ActivityRepository activityRepository;

    @MockitoBean
    AssetFacade assetFacade;

    @ParameterizedTest
    @CsvSource({
        "type, FOO, type",
        "type, CASH_DIVIDEND, type",
        "sort, price, sort",
        "direction, sideways, direction",
        "page, abc, page",
        "size, abc, size"
    })
    @DisplayName("白名單外或非數字的參數 → VALIDATION_FAILED 且 fields 指名該參數，repository 無互動")
    void invalidParameterIsRejectedWithFieldError(String name, String value, String field) {
        assertThatThrownBy(() -> mvc.perform(get(PATH).with(user("42")).param(name, value)))
            .hasRootCauseInstanceOf(FieldValidationException.class)
            .rootCause()
            .satisfies(error -> assertThat(((BusinessException) error).fields()).containsOnlyKeys(field));

        verifyNoInteractions(activityRepository, assetFacade);
    }

    @Test
    @DisplayName("page=-1 → 夾限為第 0 頁（契約：夾限同 GET /trades），200")
    void negativePageIsClamped() throws Exception {
        when(activityRepository.list(eq(42L), any())).thenReturn(List.of());

        mvc.perform(get(PATH).with(user("42")).param("page", "-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.page").value(0));

        ArgumentCaptor<ActivityQuery> query = ArgumentCaptor.forClass(ActivityQuery.class);
        verify(activityRepository).list(eq(42L), query.capture());
        assertThat(query.getValue().page()).isZero();
    }

    @Test
    @DisplayName("合法參數 → 以型別化的查詢抵達 repository（type / sort / direction / 日期 / 分頁）")
    void validParametersReachRepository() throws Exception {
        when(activityRepository.list(eq(42L), any())).thenReturn(List.of());

        mvc.perform(get(PATH).with(user("42"))
                .param("type", "SHARE_ADJUSTMENT")
                .param("sort", "quantity")
                .param("direction", "asc")
                .param("dateFrom", "2026-10-01T00:00:00Z")
                .param("dateTo", "2026-11-01T00:00:00Z")
                .param("page", "2")
                .param("size", "5"))
            .andExpect(status().isOk());

        ArgumentCaptor<ActivityQuery> query = ArgumentCaptor.forClass(ActivityQuery.class);
        verify(activityRepository).list(eq(42L), query.capture());
        assertThat(query.getValue().type()).contains(ActivityTypeFilter.SHARE_ADJUSTMENT);
        assertThat(query.getValue().sort()).isEqualTo(ActivitySortKey.QUANTITY);
        assertThat(query.getValue().direction()).isEqualTo(SortDirection.ASC);
        assertThat(query.getValue().from()).isEqualTo(OffsetDateTime.parse("2026-10-01T00:00:00Z"));
        assertThat(query.getValue().to()).isEqualTo(OffsetDateTime.parse("2026-11-01T00:00:00Z"));
        assertThat(query.getValue().page()).isEqualTo(2);
        assertThat(query.getValue().size()).isEqualTo(5);
    }

    @Test
    @DisplayName("回應形狀：PageResponse 包 PortfolioActivityDto，不適用的欄位以 null 出現（不省略）")
    void responseCarriesAllContractFieldsIncludingNulls() throws Exception {
        when(activityRepository.count(eq(42L), any())).thenReturn(1L);
        when(activityRepository.list(eq(42L), any())).thenReturn(List.of(new ActivityRow(
            UUID.fromString("44444444-4444-4444-4444-444444444444"), ActivityKind.CASH_DIVIDEND, 1L,
            OffsetDateTime.parse("2026-10-29T01:00:00Z"), null, OffsetDateTime.parse("2026-10-29T01:00:01Z"),
            null, null, null, new BigDecimal("120"), new BigDecimal("30"), null, null, null,
            null, null, null, false, true)));
        when(assetFacade.findProfiles(anyCollection())).thenReturn(Map.of());

        String body = mvc.perform(get(PATH).with(user("42")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.items[0].id").value("44444444-4444-4444-4444-444444444444"))
            .andExpect(jsonPath("$.data.items[0].kind").value("CASH_DIVIDEND"))
            .andExpect(jsonPath("$.data.items[0].amount").value("120"))
            .andExpect(jsonPath("$.data.items[0].withholdingTax").value("30"))
            .andExpect(jsonPath("$.data.items[0].reversible").value(true))
            .andExpect(jsonPath("$.data.items[0].reversed").value(false))
            .andReturn()
            .getResponse()
            .getContentAsString();

        /* MockMvc 的 jsonPath 無法區分「null」與「不存在」，直接比對字面：前端型別宣告這些欄位為 `T | null`、必定存在。 */
        for (String field : List.of("symbol", "currency", "effectiveDate", "quantity", "price", "fee", "ratioNew",
            "ratioOld", "note", "reversalOfId", "reversalOfOccurredAt", "reversalOfEffectiveDate")) {
            assertThat(body).contains("\"" + field + "\":null");
        }
        assertThat(body).doesNotContain("idempotencyKey", "assetId");
        /* jsonPath 的 value() 會把實際值轉成期望值的型別再比對，無法證明 wire 型別；以字面確認金額是 JSON 字串（Phase 5 D-05）。 */
        assertThat(body).contains("\"amount\":\"120\"", "\"withholdingTax\":\"30\"");
    }

    /**
     * 放行所有請求的安全設定：本切片的目的不是驗證授權（{@code @PreAuthorize} 由 ArchUnit 守門與 IT 驗收）。
     */
    @TestConfiguration
    static class OpenSecurityConfig {
        @Bean
        SecurityFilterChain openFilterChain(HttpSecurity http) throws Exception {
            return http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
        }
    }
}
