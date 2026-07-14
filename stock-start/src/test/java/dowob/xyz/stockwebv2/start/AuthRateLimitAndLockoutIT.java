package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.start.support.ContainerIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認證端點限流與帳號鎖定的整合測試（security.md §15）。
 *
 * <p>本測試獨立設定容器屬性並開啟限流（{@code stock.security.rate-limit.enabled=true}）並套用
 * 低門檻，以在不影響共用 IT 套件（{@link ContainerIT} 預設關閉限流）的前提下驗證行為。各測試以
 * 不同來源 IP 隔離 per-IP 計數桶。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("認證端點限流與帳號鎖定")
class AuthRateLimitAndLockoutIT {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    PasswordEncoder passwordEncoder;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", ContainerIT.postgres::getJdbcUrl);
        registry.add("spring.datasource.username", ContainerIT.postgres::getUsername);
        registry.add("spring.datasource.password", ContainerIT.postgres::getPassword);
        registry.add("spring.data.redis.host", ContainerIT.redis::getHost);
        registry.add("spring.data.redis.port", () -> ContainerIT.redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", () -> 0);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.flyway.mixed", () -> true);
        registry.add("management.server.port", () -> 11180);
        registry.add("spring.kafka.bootstrap-servers", ContainerIT.kafka::getBootstrapServers);

        registry.add("stock.security.rate-limit.enabled", () -> true);
        registry.add("stock.security.rate-limit.login.limit", () -> 6);
        registry.add("stock.security.rate-limit.login.window", () -> "PT1M");
        registry.add("stock.security.rate-limit.register.limit", () -> 100);
        registry.add("stock.security.rate-limit.register.window", () -> "PT1H");
        registry.add("stock.security.rate-limit.lockout.threshold", () -> 3);
        registry.add("stock.security.rate-limit.lockout.duration", () -> "PT15M");
    }

    @Test
    @DisplayName("登入超過每 IP 門檻後回 429 並帶 Retry-After")
    void loginRateLimitReturns429AfterThreshold() throws Exception {
        register("rl-login@example.com", "rllogin", "Password1");
        RequestPostProcessor ip = fromIp("10.20.0.1");

        for (int i = 0; i < 6; i++) {
            mockMvc.perform(post("/api/v1/auth/login").with(ip)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"email":"rl-login@example.com","password":"Password1"}
                        """))
                .andExpect(status().isOk());
        }

        mockMvc.perform(post("/api/v1/auth/login").with(ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"rl-login@example.com","password":"Password1"}
                    """))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string("Retry-After", notNullValue()))
            .andExpect(jsonPath("$.error.code", equalTo("AUTH_RATE_LIMITED")));
    }

    @Test
    @DisplayName("連續登入失敗達門檻後帳號鎖定，即使密碼正確也回 429")
    void accountLocksAfterFailedLoginThreshold() throws Exception {
        register("lockout@example.com", "lockoutuser", "Password1");
        RequestPostProcessor ip = fromIp("10.20.0.2");

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/auth/login").with(ip)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"email":"lockout@example.com","password":"WrongPass1"}
                        """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code", equalTo("AUTH_INVALID_CREDENTIALS")));
        }

        mockMvc.perform(post("/api/v1/auth/login").with(ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"lockout@example.com","password":"Password1"}
                    """))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.error.code", equalTo("AUTH_ACCOUNT_LOCKED")));
    }

    @Test
    @DisplayName("ADMIN 解鎖被鎖帳號後可再次登入")
    void adminUnlockResetsLockout() throws Exception {
        register("unlock-target@example.com", "unlocktarget", "Password1");
        String uuid = uuidOf("unlock-target@example.com");
        RequestPostProcessor lockIp = fromIp("10.20.0.3");

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/auth/login").with(lockIp)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"email":"unlock-target@example.com","password":"WrongPass1"}
                        """))
                .andExpect(status().isUnauthorized());
        }

        seedAdmin("admin-unlock@example.com", "adminunlock", "AdminPass1");
        String adminToken = tokenFor("admin-unlock@example.com", "AdminPass1");

        mockMvc.perform(post("/api/admin/users/{uuid}/unlock", uuid)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/login").with(fromIp("10.20.0.4"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"unlock-target@example.com","password":"Password1"}
                    """))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("非 ADMIN 呼叫解鎖端點回 403")
    void adminUnlockRequiresAdminRole() throws Exception {
        register("normal-user@example.com", "normaluser", "Password1");
        String uuid = uuidOf("normal-user@example.com");
        String userToken = tokenFor("normal-user@example.com", "Password1");

        mockMvc.perform(post("/api/admin/users/{uuid}/unlock", uuid)
                .header("Authorization", "Bearer " + userToken))
            .andExpect(status().isForbidden());
    }

    private static RequestPostProcessor fromIp(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private void register(String email, String username, String password) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","username":"%s","password":"%s"}
                    """.formatted(email, username, password)))
            .andExpect(status().isOk());
    }

    private String tokenFor(String email, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"%s"}
                    """.formatted(email, password)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        JsonNode data = objectMapper.readTree(body).get("data");
        return data.get("accessToken").asText();
    }

    private void seedAdmin(String email, String username, String password) {
        jdbcClient.sql("""
                insert into users(email, username, password_hash, role, status, token_version)
                values (:email, :username, :passwordHash, 'ADMIN', 'ACTIVE', 1)
                """)
            .param("email", email)
            .param("username", username)
            .param("passwordHash", passwordEncoder.encode(password))
            .update();
    }

    private String uuidOf(String email) {
        return jdbcClient.sql("select uuid from users where email = :email")
            .param("email", email)
            .query(String.class)
            .single();
    }
}
