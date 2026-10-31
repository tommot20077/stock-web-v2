package dowob.xyz.stockwebv2.trading.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * trading 模組不得新增對 {@code assets} 表的 SQL JOIN 的守門測試（D-21；PORT-12）。
 *
 * <p>跨模組取得資產屬性（幣別、類型、產業）只能走 {@code AssetFacade}。目前唯一的例外是
 * {@link JdbcTradingRepository} 內三處既有的 {@code join assets a on a.id = ...}，只為取
 * uuid / symbol / name；本測試把它們列為白名單並鎖定數量，任何新增的 JOIN 都會讓測試失敗。
 * 只掃描 SQL 形式（{@code join assets <alias> on}），註解中的文字不計。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("trading SQL 邊界（D-21）")
class TradingSqlBoundaryTest {

    private static final Path MAIN_SOURCE = Path.of("src", "main", "java");

    private static final Pattern JOIN_ASSETS = Pattern.compile("(?i)\\bjoin\\s+assets\\s+\\w+\\s+on\\b");

    /** 既有白名單：檔名 → 允許的 JOIN 數量。新增請先與 Yuan 討論（architecture.md 的 Facade 規則）。 */
    private static final String ALLOWED_FILE = "JdbcTradingRepository.java";

    private static final int ALLOWED_COUNT = 3;

    @Test
    @DisplayName("trading 主程式碼對 assets 的 SQL JOIN 只存在於白名單的三處")
    void tradingDoesNotAddSqlJoinsOnAssets() throws IOException {
        assertThat(MAIN_SOURCE).as("測試需以模組目錄為工作目錄").isDirectory();

        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN_SOURCE)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                long count = JOIN_ASSETS.matcher(Files.readString(file)).results().count();
                for (long i = 0; i < count; i++) {
                    hits.add(file.getFileName().toString());
                }
            }
        }

        assertThat(hits)
            .as("D-21：資產屬性一律經 AssetFacade 取得，不得新增 join assets（現有白名單 %d 處）", ALLOWED_COUNT)
            .hasSize(ALLOWED_COUNT)
            .containsOnly(ALLOWED_FILE);
    }
}
