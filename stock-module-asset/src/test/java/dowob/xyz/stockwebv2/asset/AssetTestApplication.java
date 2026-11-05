package dowob.xyz.stockwebv2.asset;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 測試用 Spring Boot Application — 僅供 {@code @WebMvcTest} 等 Spring Test 切片尋找
 * {@code @SpringBootConfiguration} 的錨點。
 *
 * <p>不在生產路徑，<strong>不是</strong>可執行的應用程式：本模組是 library，真正的啟動類別在 {@code stock-start}。
 * 掃描範圍只有 {@code dowob.xyz.stockwebv2.asset}；切片測試另以 TypeExcludeFilter 只載入 Web 元件。
 * 本模組的 {@code AssetFacadeImplIT} / {@code FxRateFacadeImplIT} 不啟動 Spring context（直接以 Testcontainers +
 * {@code JdbcClient} 組裝），不受這個 configuration 影響。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootApplication
public class AssetTestApplication {
}
