package dowob.xyz.stockwebv2.start;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 每個 HTTP 端點都必須在方法層宣告授權。
 *
 * <p>原本 Backtest / Market / WsTicket / Backfill 四個 controller 零 {@code @PreAuthorize}，只靠
 * {@code SecurityConfig} 的 URL 規則擋（2026-09-02 安全審查 M-4）。今天不會越權，但 URL 規則與方法
 * 是兩處分開維護的東西：哪天有人調了 URL 規則的順序或萬用字元，這些端點就會無聲地變成公開。
 * 方法層宣告讓「這個端點要什麼權限」跟著程式碼走。
 *
 * <p>例外只允許下方白名單，而且每一項都要寫理由；白名單裡的方法若已不存在，測試也會失敗，
 * 免得白名單變成沒人清的垃圾場。
 *
 * @author Yuan
 * @version 1.0.0
 */
@DisplayName("端點方法層授權")
class EndpointAuthorizationRulesTest {

    private static final List<Class<? extends Annotation>> MAPPINGS = List.of(
        GetMapping.class, PostMapping.class, PutMapping.class, DeleteMapping.class, PatchMapping.class,
        RequestMapping.class);

    /** 刻意不加 {@code @PreAuthorize} 的端點與理由。鍵為 {@code 類別簡名#方法名}。 */
    private static final Map<String, String> ALLOWED_WITHOUT_PRE_AUTHORIZE = Map.ofEntries(
        Map.entry("AuthController#register", "註冊本身就是未登入的流程；URL 層 permitAll"),
        Map.entry("AuthController#login", "登入本身就是未登入的流程；URL 層 permitAll"),
        Map.entry("AuthController#token", "bearer 取 token 的登入流程；URL 層 permitAll"),
        Map.entry("AuthController#refresh", "以 refresh cookie 換發，access token 可能已過期；URL 層 permitAll"),
        Map.entry("AuthController#logout", "登出須在 access token 過期時仍可呼叫；URL 層 permitAll"),
        Map.entry("AssetController#search", "標的搜尋為公開資料；GET /api/v1/assets 在 URL 層 permitAll"),
        Map.entry("CsrfController#csrf", "登入前就要取得 CSRF token；URL 層 permitAll"),
        Map.entry("TestOnlyController#business", "@Profile(\"test\")，正式環境不存在"),
        Map.entry("TestOnlyController#status", "@Profile(\"test\")，正式環境不存在"),
        Map.entry("BackfillController#trigger",
            "方法內 requireAdmin：被拒絕的嘗試也要寫稽核，@PreAuthorize 會在進入方法前就拒絕而留不下紀錄"),
        Map.entry("BackfillController#status", "方法內 requireAdmin，與 trigger 一致"));

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dowob.xyz.stockwebv2");
    }

    @Test
    @DisplayName("所有端點方法都有 @PreAuthorize，或列在附理由的白名單中")
    void everyEndpointDeclaresAuthorization() {
        List<String> missing = new ArrayList<>();
        for (String endpoint : endpoints(false)) {
            if (!ALLOWED_WITHOUT_PRE_AUTHORIZE.containsKey(endpoint)) {
                missing.add(endpoint);
            }
        }
        assertThat(missing)
            .as("以下端點既沒有 @PreAuthorize，也不在白名單內；請補上授權宣告，或在白名單寫明為何可以不用")
            .isEmpty();
    }

    @Test
    @DisplayName("白名單中的每一項都仍是存在且未加 @PreAuthorize 的端點")
    void allowListHasNoStaleEntries() {
        Set<String> unprotected = endpoints(false);
        assertThat(unprotected)
            .as("白名單有過期項目：方法已刪除、改名，或已加上 @PreAuthorize，請從白名單移除")
            .containsAll(ALLOWED_WITHOUT_PRE_AUTHORIZE.keySet());
    }

    /**
     * @param withPreAuthorize true 取有宣告的端點，false 取沒宣告的端點
     * @return {@code 類別簡名#方法名} 集合
     */
    private Set<String> endpoints(boolean withPreAuthorize) {
        Set<String> result = new TreeSet<>();
        for (JavaClass controller : classes) {
            if (!controller.isAnnotatedWith(RestController.class)) {
                continue;
            }
            boolean classLevel = controller.isAnnotatedWith(PreAuthorize.class);
            for (JavaMethod method : controller.getMethods()) {
                boolean isEndpoint = MAPPINGS.stream().anyMatch(method::isAnnotatedWith);
                if (!isEndpoint) {
                    continue;
                }
                boolean declared = classLevel || method.isAnnotatedWith(PreAuthorize.class);
                if (declared == withPreAuthorize) {
                    result.add(controller.getSimpleName() + "#" + method.getName());
                }
            }
        }
        return result;
    }
}
