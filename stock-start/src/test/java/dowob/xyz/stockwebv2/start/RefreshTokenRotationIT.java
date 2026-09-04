package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.model.Role;
import dowob.xyz.stockwebv2.common.model.UserStatus;
import dowob.xyz.stockwebv2.start.support.ContainerIT;
import dowob.xyz.stockwebv2.user.domain.User;
import dowob.xyz.stockwebv2.user.service.RefreshTokenService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Refresh token 輪替的重放偵測與原子性整合測試（security.md §5a）。
 *
 * <p>驗證兩件事：
 * <ul>
 *   <li>步驟 5：偵測到已使用過的 token 被重放時，必須撤銷該使用者的<b>全部</b> refresh token</li>
 *   <li>輪替必須為原子操作：同一 token 並發輪替時只有一個成功</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("Refresh token 輪替重放偵測與原子性")
class RefreshTokenRotationIT extends ContainerIT {

    @Autowired
    RefreshTokenService refreshTokenService;

    @Test
    @DisplayName("重放已使用的 refresh token 會撤銷該使用者全部 refresh token")
    void replayRevokesAllRefreshTokensForUser() {
        User user = activeUser(9001L);
        String first = refreshTokenService.issue(user, "device-a");
        String second = refreshTokenService.issue(user, "device-b");

        refreshTokenService.consumeForRotation(first);

        assertThatThrownBy(() -> refreshTokenService.consumeForRotation(first))
            .as("重放已消費的 token 應被拒絕")
            .isInstanceOf(BusinessException.class);

        assertThatThrownBy(() -> refreshTokenService.consumeForRotation(second))
            .as("偵測到重放後，同一使用者的其他 refresh token 也必須失效")
            .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("同一 refresh token 並發輪替只有一個成功")
    void concurrentRotationAllowsOnlyOneWinner() throws Exception {
        User user = activeUser(9002L);
        String token = refreshTokenService.issue(user, "device");
        int threads = 4;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        refreshTokenService.consumeForRotation(token);
                        succeeded.incrementAndGet();
                    } catch (RuntimeException ignored) {
                        // 預期：敗者被拒絕
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(succeeded.get()).isEqualTo(1);
    }

    /**
     * 建立一個 ACTIVE 狀態的使用者物件（僅用於 Redis 層測試，不寫入 DB）。
     *
     * @param id 使用者 id
     * @return 使用者物件
     */
    private User activeUser(long id) {
        return new User(
            id,
            UUID.randomUUID(),
            "refresh-rotation-" + id + "@example.com",
            "refreshrotation" + id,
            "hash",
            Role.USER,
            UserStatus.ACTIVE,
            1,
            OffsetDateTime.now(),
            OffsetDateTime.now()
        );
    }
}
