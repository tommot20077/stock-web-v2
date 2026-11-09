package dowob.xyz.stockwebv2.marketdata.support;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * market-data 模組整合測試（{@code *IT}）共用的 Testcontainers 單例容器（D-04 方案 B）。
 *
 * <p><strong>為何不用 {@code @Testcontainers} / {@code @Container}</strong>:JUnit extension 會在每個
 * 測試類別結束時停掉容器,下一個類別又要重新啟動（Kafka 一次約十幾秒）。本類改用
 * initialization-on-demand holder:{@link KafkaHolder} / {@link PostgresHolder} / {@link RedisHolder}
 * 各自在第一次被呼叫 {@link #kafka()} / {@link #postgres()} / {@link #redis()} 時才載入並啟動容器,
 * 所以只用 Redis 的類別不會連帶啟動 Kafka。容器不手動停止,由 Testcontainers 的 Ryuk sidecar 在 JVM
 * 結束時回收。這個做法與 {@code stock-start} 的 {@code ContainerIT} 一致。</p>
 *
 * <p><strong>單例生效的前提</strong>:failsafe 預設 {@code forkCount=1, reuseForks=true},同一模組的
 * 所有 IT 跑在同一個 JVM;若日後改成每個類別 fork 一次,本類退化為「每類別一組容器」,行為仍正確,只是不省時間。</p>
 *
 * <p><strong>共用後的隔離策略</strong>(todo {@code 2026-09-27-it-shared-containers.md} 的風險,T-05-14):</p>
 * <ul>
 *   <li><strong>Postgres</strong>:每個 IT 以 {@link #freshDatabase(String)} 取得自己的 database
 *       ({@code md_<name>}),再自行跑 Flyway;{@code DELETE FROM market_prices} 只影響自己。</li>
 *   <li><strong>Redis</strong>:每個 IT 使用不同的 database index({@code RedisStandaloneConfiguration#setDatabase}),
 *       清理一律用 {@code flushDb()},<strong>禁止</strong> {@code flushAll()}(會清掉其他 IT 的 index)。</li>
 *   <li><strong>Kafka</strong>:topic 名稱以 {@link #uniqueName(String)} 產生,建立 topic 一律經
 *       {@link #ensureTopics(AdminClient, NewTopic...)};consumer group 也應每類別唯一(D-29)。</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0
 */
public final class MarketDataContainers {

    /** admin database 名稱;僅用來執行 {@code CREATE DATABASE},IT 不應直接在這裡建表。 */
    private static final String ADMIN_DATABASE = "md_admin";

    /** 測試 database 名稱前綴,避免與 admin / postgres / template DB 撞名。 */
    private static final String DATABASE_PREFIX = "md_";

    /**
     * {@link #freshDatabase(String)} 允許的名稱字元。
     *
     * <p>名稱會直接拼進 DDL({@code CREATE DATABASE} 不支援 bind parameter),所以只允許小寫英數與底線,
     * 杜絕 SQL 注入與需要引號的識別字。</p>
     */
    private static final Pattern DATABASE_NAME = Pattern.compile("[a-z0-9_]+");

    /** {@link #ensureTopics} 等待 broker 回應的秒數;與既有 IT 的 {@code get(10, SECONDS)} 一致。 */
    private static final long TOPIC_TIMEOUT_SECONDS = 10;

    private MarketDataContainers() {
    }

    /**
     * 取得共用 Kafka 容器;第一次呼叫時才啟動。
     *
     * @return 已啟動的 Kafka 容器
     */
    public static ConfluentKafkaContainer kafka() {
        return KafkaHolder.CONTAINER;
    }

    /**
     * 取得共用 TimescaleDB 容器;第一次呼叫時才啟動。
     *
     * <p>預設 database 是 admin 用途;IT 請改用 {@link #freshDatabase(String)} 回傳的 URL。</p>
     *
     * @return 已啟動的 Postgres(TimescaleDB)容器
     */
    public static PostgreSQLContainer<?> postgres() {
        return PostgresHolder.CONTAINER;
    }

    /**
     * 取得共用 Redis 容器;第一次呼叫時才啟動。
     *
     * @return 已啟動的 Redis 容器(port 6379 已 expose)
     */
    public static GenericContainer<?> redis() {
        return RedisHolder.CONTAINER;
    }

    /**
     * 在共用 Postgres 容器內建立一個全新的 database {@code md_<name>},回傳其 JDBC URL。
     *
     * <p>若同名 database 已存在(例如同一 JVM 重跑同一類別),先 {@code DROP DATABASE ... WITH (FORCE)}
     * 再重建,保證呼叫端拿到的是空 database。呼叫端需自行跑 Flyway;帳密沿用
     * {@link #postgres()} 的 {@code getUsername()} / {@code getPassword()}。</p>
     *
     * @param name database 名稱後綴,僅允許 {@code [a-z0-9_]+}
     * @return 新 database 的 JDBC URL
     * @throws IllegalArgumentException name 為 null 或含不允許的字元
     * @throws IllegalStateException    建立 database 失敗
     */
    public static synchronized String freshDatabase(String name) {
        if (name == null || !DATABASE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("database name must match [a-z0-9_]+");
        }
        String database = DATABASE_PREFIX + name;
        PostgreSQLContainer<?> pg = postgres();
        // CREATE / DROP DATABASE 不能在 transaction 內執行;DriverManager 連線預設 autocommit=true
        try (Connection conn = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            stmt.execute("CREATE DATABASE " + database);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to create test database " + database, e);
        }
        return replaceDatabase(pg.getJdbcUrl(), database);
    }

    /**
     * 建立 topic;同名 topic 已存在時視為成功(共用 broker 下其他 IT 可能已建立)。
     *
     * <p><strong>同名 topic 的 partition 數必須一致</strong>:已存在的 topic 不會被修改,若呼叫端要求的
     * partition 數與既有不同,本方法不會報錯,斷言會在後續才失敗。優先搭配 {@link #uniqueName(String)}
     * 使用,讓每個 IT 擁有自己的 topic。</p>
     *
     * @param admin  Kafka AdminClient(呼叫端負責關閉)
     * @param topics 要建立的 topic
     * @throws ExecutionException   建立失敗且原因不是 {@link TopicExistsException}
     * @throws InterruptedException 等待中被中斷
     * @throws TimeoutException     broker 未在時限內回應
     */
    public static void ensureTopics(AdminClient admin, NewTopic... topics)
            throws ExecutionException, InterruptedException, TimeoutException {
        try {
            admin.createTopics(List.of(topics)).all().get(TOPIC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof TopicExistsException)) {
                throw e;
            }
        }
    }

    /**
     * 產生每次唯一的名稱(topic、consumer group 等),格式 {@code <prefix>.<8 位 UUID>}。
     *
     * @param prefix 名稱前綴,通常是正式 topic 名稱,方便在 broker log 中辨識
     * @return 唯一名稱
     */
    public static String uniqueName(String prefix) {
        return prefix + "." + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 把 JDBC URL 的 database 段({@code /<db>},位於 query string 之前)換成指定名稱。
     *
     * @param jdbcUrl  原 URL,例如 {@code jdbc:postgresql://localhost:32768/md_admin?loggerLevel=OFF}
     * @param database 新 database 名稱
     * @return 替換後的 URL
     */
    private static String replaceDatabase(String jdbcUrl, String database) {
        int queryStart = jdbcUrl.indexOf('?');
        String base = queryStart >= 0 ? jdbcUrl.substring(0, queryStart) : jdbcUrl;
        String query = queryStart >= 0 ? jdbcUrl.substring(queryStart) : "";
        int slash = base.lastIndexOf('/');
        return base.substring(0, slash + 1) + database + query;
    }

    /** Kafka 容器 holder;類別首次被存取時才啟動容器(JVM 類別載入保證執行緒安全)。 */
    private static final class KafkaHolder {

        static final ConfluentKafkaContainer CONTAINER = new ConfluentKafkaContainer(
                DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

        static {
            CONTAINER.start();
        }
    }

    /** TimescaleDB 容器 holder;預設 database 為 {@link #ADMIN_DATABASE}。 */
    private static final class PostgresHolder {

        static final PostgreSQLContainer<?> CONTAINER = new PostgreSQLContainer<>(
                DockerImageName.parse("timescale/timescaledb:2.17.2-pg16")
                        .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName(ADMIN_DATABASE)
                .withUsername("test")
                .withPassword("test");

        static {
            CONTAINER.start();
        }
    }

    /** Redis 容器 holder;各 IT 以不同 database index 隔離。 */
    private static final class RedisHolder {

        static final GenericContainer<?> CONTAINER = new GenericContainer<>(
                DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

        static {
            CONTAINER.start();
        }
    }
}
