package dowob.xyz.stockwebv2.asset.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 公開搜尋的 ILIKE 樣式:使用者輸入的萬用字元必須被跳脫、長度有上限(2026-09-02 安全審查 L-7)。
 *
 * @author Yuan
 * @version 1.0.0
 */
class AssetRepositoryLikePatternTest {

    @Test
    @DisplayName("% 與 _ 被當成字面字元,反斜線先跳脫")
    void wildcardsAreEscaped() {
        assertThat(AssetRepository.likePattern("50%_off\\")).isEqualTo("%50\\%\\_off\\\\%");
    }

    @Test
    @DisplayName("前後空白去除;空字串得到 %%(全部)")
    void blankQueryMatchesAll() {
        assertThat(AssetRepository.likePattern("  ")).isEqualTo("%%");
        assertThat(AssetRepository.likePattern(null)).isEqualTo("%%");
    }

    @Test
    @DisplayName("查詢字串截到 64 字元")
    void queryIsTruncatedTo64Chars() {
        assertThat(AssetRepository.likePattern("a".repeat(100))).isEqualTo("%" + "a".repeat(64) + "%");
    }
}
