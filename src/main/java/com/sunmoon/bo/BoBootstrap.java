package com.sunmoon.bo;

import com.sunmoon.bo.auth.InMemorySessionStore;
import com.sunmoon.bo.auth.PasswordHasher;
import com.sunmoon.bo.auth.SessionStore;
import com.sunmoon.bo.domain.commoncode.CommonCodeRepository;
import com.sunmoon.bo.domain.device.DeviceRepository;
import com.sunmoon.bo.domain.operator.Action;
import com.sunmoon.bo.domain.operator.OperatorRepository;
import com.sunmoon.bo.domain.operator.Screen;
import com.sunmoon.bo.persistence.CommonCodeMapper;
import com.sunmoon.bo.persistence.DeviceMapper;
import com.sunmoon.bo.persistence.InMemoryCommonCodeRepository;
import com.sunmoon.bo.persistence.InMemoryDeviceRepository;
import com.sunmoon.bo.persistence.InMemoryOperatorRepository;
import com.sunmoon.bo.persistence.MyBatisCommonCodeRepository;
import com.sunmoon.bo.persistence.MyBatisDeviceRepository;
import com.sunmoon.bo.persistence.MyBatisOperatorRepository;
import com.sunmoon.bo.persistence.OperatorMapper;
import com.sunmoon.bo.web.AuthorizedEndpoint;
import com.sunmoon.bo.web.LoginEndpoint;
import com.sunmoon.bo.web.LogoutEndpoint;
import com.sunmoon.bo.web.MeEndpoint;
import com.sunmoon.bo.web.commoncode.CreateCommonCodeEndpoint;
import com.sunmoon.bo.web.commoncode.DeleteCommonCodeEndpoint;
import com.sunmoon.bo.web.commoncode.ListCommonCodeEndpoint;
import com.sunmoon.bo.web.commoncode.SaveCommonCodeEndpoint;
import com.sunmoon.bo.web.device.CreateDeviceEndpoint;
import com.sunmoon.bo.web.device.DeleteDeviceEndpoint;
import com.sunmoon.bo.web.device.ListDeviceEndpoint;
import com.sunmoon.bo.web.device.SaveDeviceEndpoint;
import com.sunmoon.platform.core.CoreRuntime;
import com.sunmoon.platform.core.ListenerSpec;
import com.sunmoon.platform.infrastructure.persistence.FlywayMigrator;
import com.sunmoon.platform.infrastructure.persistence.MyBatisConfig;
import com.sunmoon.platform.infrastructure.persistence.PostgresConnectionSettings;
import com.sunmoon.platform.transport.http.HealthCheckEndpoint;
import com.sunmoon.platform.transport.http.HttpServerInitializer;
import com.sunmoon.platform.transport.http.MetricsEndpoint;
import com.sunmoon.platform.transport.http.RestEndpoint;
import com.sunmoon.platform.transport.http.RouteKey;
import io.netty.handler.codec.http.HttpMethod;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * BO's composition root. No DI container (docs/adr/0002): every wire-up
 * happens here, by hand.
 *
 * <p>Note what this file imports: {@code com.sunmoon.bo.*} for everything
 * BO owns, and {@code com.sunmoon.platform.*} only for the kernel it runs
 * on. Nothing from the device-facing runtime — that is a sibling, not a
 * dependency (docs/adr/0014).
 */
public final class BoBootstrap {

    private static final Logger log = LoggerFactory.getLogger(BoBootstrap.class);

    public static void main(String[] args) throws Exception {
        BoConfig config = BoConfig.fromEnv();

        Repositories repositories = buildRepositories();
        SessionStore sessionStore = new InMemorySessionStore();

        seedSuperAdminIfNeeded(repositories.operators());

        // The composition root owns the worker pool endpoints run on
        // (docs/adr/0010), so the kernel doesn't have to know it exists.
        ExecutorService blockingWorkExecutor = Executors.newFixedThreadPool(
                config.workerThreads(), Thread.ofPlatform().name("bo-worker-", 0).daemon(true).factory());
        log.info("{} worker threads for blocking endpoint work", config.workerThreads());

        // REST only — the null says "no WebSocket on this listener".
        new CoreRuntime(List.of(new ListenerSpec("BO", config.port(), new HttpServerInitializer(
                routes(repositories, sessionStore, config), null, blockingWorkExecutor)))).start();
    }

    private record Repositories(
            OperatorRepository operators,
            CommonCodeRepository commonCodes,
            DeviceRepository devices) {
    }

    /**
     * The one place fakes and real adapters are chosen between. Presence of
     * {@code POSTGRES_JDBC_URL} is the switch — no separate mode flag to
     * keep in sync, and a machine with only a JDK still runs all of BO on
     * in-memory fakes (docs/adr/0003, docs/adr/0005).
     *
     * <p>When Postgres is configured, migrations run before any repository
     * is handed out, so the schema is present before the super-admin seed
     * below touches it.
     */
    private static Repositories buildRepositories() {
        if (System.getenv("POSTGRES_JDBC_URL") == null) {
            log.info("POSTGRES_JDBC_URL not set — using in-memory fake repositories");
            return new Repositories(
                    new InMemoryOperatorRepository(),
                    new InMemoryCommonCodeRepository(),
                    new InMemoryDeviceRepository());
        }

        PostgresConnectionSettings settings = PostgresConnectionSettings.fromEnv();
        DataSource dataSource = MyBatisConfig.buildDataSource(settings);
        FlywayMigrator.migrate(dataSource);
        // BO's own mappers, passed in — the kernel lists no domain (docs/adr/0014).
        SqlSessionFactory sqlSessionFactory = MyBatisConfig.buildSqlSessionFactory(dataSource,
                OperatorMapper.class, CommonCodeMapper.class, DeviceMapper.class);
        log.info("POSTGRES_JDBC_URL set — using PostgreSQL repositories ({})", settings.jdbcUrl());
        return new Repositories(
                new MyBatisOperatorRepository(sqlSessionFactory),
                new MyBatisCommonCodeRepository(sqlSessionFactory),
                new MyBatisDeviceRepository(sqlSessionFactory));
    }

    /**
     * The route table is where the 3-tier permission model becomes real:
     * every business route is wrapped in {@link AuthorizedEndpoint} with the
     * screen it belongs to and the action it performs, so an unwrapped route
     * is visibly unwrapped here rather than quietly unprotected somewhere
     * else (docs/adr/0006).
     */
    private static Map<RouteKey, RestEndpoint> routes(
            Repositories repositories, SessionStore sessionStore, BoConfig config) {

        CommonCodeRepository commonCodeRepository = repositories.commonCodes();
        DeviceRepository deviceRepository = repositories.devices();

        return Map.ofEntries(
                Map.entry(new RouteKey(HttpMethod.GET, "/health"), new HealthCheckEndpoint()),
                Map.entry(new RouteKey(HttpMethod.GET, "/metrics"), new MetricsEndpoint()),

                Map.entry(new RouteKey(HttpMethod.POST, "/bo/auth/login"),
                        new LoginEndpoint(repositories.operators(), sessionStore, config.secureCookies())),
                Map.entry(new RouteKey(HttpMethod.POST, "/bo/auth/logout"), new LogoutEndpoint(sessionStore)),
                Map.entry(new RouteKey(HttpMethod.GET, "/bo/auth/me"), new MeEndpoint(sessionStore)),

                Map.entry(new RouteKey(HttpMethod.GET, "/bo/common-code"),
                        new AuthorizedEndpoint(Screen.COMMON_CODE, Action.VIEW, sessionStore,
                                new ListCommonCodeEndpoint(commonCodeRepository))),
                Map.entry(new RouteKey(HttpMethod.POST, "/bo/common-code"),
                        new AuthorizedEndpoint(Screen.COMMON_CODE, Action.CREATE, sessionStore,
                                new CreateCommonCodeEndpoint(commonCodeRepository))),
                Map.entry(new RouteKey(HttpMethod.PUT, "/bo/common-code"),
                        new AuthorizedEndpoint(Screen.COMMON_CODE, Action.SAVE, sessionStore,
                                new SaveCommonCodeEndpoint(commonCodeRepository))),
                Map.entry(new RouteKey(HttpMethod.DELETE, "/bo/common-code"),
                        new AuthorizedEndpoint(Screen.COMMON_CODE, Action.DELETE, sessionStore,
                                new DeleteCommonCodeEndpoint(commonCodeRepository))),

                Map.entry(new RouteKey(HttpMethod.GET, "/bo/devices"),
                        new AuthorizedEndpoint(Screen.DEVICE, Action.VIEW, sessionStore,
                                new ListDeviceEndpoint(deviceRepository))),
                Map.entry(new RouteKey(HttpMethod.POST, "/bo/devices"),
                        new AuthorizedEndpoint(Screen.DEVICE, Action.CREATE, sessionStore,
                                new CreateDeviceEndpoint(deviceRepository))),
                Map.entry(new RouteKey(HttpMethod.PUT, "/bo/devices"),
                        new AuthorizedEndpoint(Screen.DEVICE, Action.SAVE, sessionStore,
                                new SaveDeviceEndpoint(deviceRepository))),
                Map.entry(new RouteKey(HttpMethod.DELETE, "/bo/devices"),
                        new AuthorizedEndpoint(Screen.DEVICE, Action.DELETE, sessionStore,
                                new DeleteDeviceEndpoint(deviceRepository))));
    }

    /**
     * The bootstrap problem for a session-based, no-Spring BO: something has
     * to create the first 전체관리자 account, since BO itself requires being
     * logged in to manage operators (docs/adr/0004). Reads
     * {@code BO_ADMIN_USERNAME}/{@code BO_ADMIN_PASSWORD} once, only when
     * the operator store is empty — never seeds a guessable default.
     */
    private static void seedSuperAdminIfNeeded(OperatorRepository operatorRepository) {
        if (operatorRepository.existsAny()) {
            return;
        }
        String username = System.getenv("BO_ADMIN_USERNAME");
        String password = System.getenv("BO_ADMIN_PASSWORD");
        if (username == null || password == null || username.isBlank() || password.isBlank()) {
            log.warn("No operator accounts exist and BO_ADMIN_USERNAME/BO_ADMIN_PASSWORD are not set — "
                    + "BO login will be unusable until an operator is seeded.");
            return;
        }
        operatorRepository.create(username, PasswordHasher.hash(password), "Super Admin", true);
        log.info("Seeded initial BO super admin operator '{}'", username);
    }

    private BoBootstrap() {
    }
}
