package com.opsagent.auth;

import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.opsagent.common.security.JwtProperties;
import com.opsagent.common.security.JwtService;
import com.opsagent.isolation.LocalMvcServer;
import java.net.URI;
import java.util.List;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Real AuthService, MyBatis UserMapper and signed login JWTs backed by the isolated SQL database. */
public final class IsolatedSqlAuthHost implements AutoCloseable {
    private final JdbcTemplate jdbc;
    private final JwtService jwt;
    private final LocalMvcServer http;
    private final AuthService auth;

    public IsolatedSqlAuthHost(DataSource dataSource, String internalSecret, String applicationCredential,
            String loginSecret) throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE IF NOT EXISTS sys_user(id BIGINT PRIMARY KEY,username VARCHAR(128),password VARCHAR(255),"
                + "display_name VARCHAR(128),status VARCHAR(16),deleted INT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS sys_role(id BIGINT PRIMARY KEY,code VARCHAR(64),status VARCHAR(16),deleted INT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS sys_user_role(user_id BIGINT,role_id BIGINT)");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class) == 0) {
            jdbc.update("INSERT INTO sys_user VALUES(10,'synthetic-user-10','unused','Synthetic 10','enable',0)");
            jdbc.update("INSERT INTO sys_user VALUES(20,'synthetic-user-20','unused','Synthetic 20','enable',0)");
            jdbc.update("INSERT INTO sys_role VALUES(1,'USER','enable',0),(2,'ADMIN','enable',0),(3,'AUDITOR','enable',0)");
            jdbc.update("INSERT INTO sys_user_role VALUES(10,1),(20,1)");
        }
        new ResourceDatabasePopulator(new ClassPathResource("harness-identity-schema.sql")).execute(dataSource);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM ops_harness_application", Integer.class) == 0)
            jdbc.update("INSERT INTO ops_harness_application VALUES(?,?,1)", "opsagent-pilot",
                    HarnessIdentityService.sha256(applicationCredential));
        if (jdbc.queryForObject("SELECT COUNT(*) FROM ops_harness_grant", Integer.class) == 0)
            for (long user : List.of(10L, 20L)) jdbc.update("INSERT INTO ops_harness_grant VALUES(?,?,?,'USER',?,1)",
                "opsagent-pilot", "ops-dev", user,
                "[\"runs:create\",\"runs:read\",\"runs:list\",\"runs:events:read\",\"runs:control\","
                        + "\"approvals:read\",\"approvals:decide\",\"runs:reconcile:read\",\"runs:reconcile\","
                        + "\"runs:output:read\",\"approvals:review\",\"tool:opsagent/rag-search\","
                        + "\"opsagent:rag:search\",\"model:invoke\"]");
        MybatisConfiguration config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(UserMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(config);
        UserMapper users = new SqlSessionTemplate(factory.getObject()).getMapper(UserMapper.class);
        JwtProperties properties = new JwtProperties();
        properties.setSecret(loginSecret);
        properties.setAccessTokenTtl(java.time.Duration.ofHours(2));
        jwt = new JwtService(properties);
        // Refresh/session/captcha are not exercised; existing JWT verification and actor SQL are real.
        auth = new AuthService(users, mock(RefreshTokenMapper.class), new BCryptPasswordEncoder(), jwt,
                mock(CaptchaService.class));
        var identity = new HarnessIdentityService(jdbc, auth, jwt, internalSecret);
        http = new LocalMvcServer(new HarnessIdentityController(identity), new InternalActorController(auth, internalSecret));
    }

    public URI origin() { return http.origin(); }
    public String login(long user) { return jwt.issue(user, "synthetic-user-" + user, List.of("USER")).token(); }
    public void active(long user, boolean enabled) {
        jdbc.update("UPDATE sys_user SET status=? WHERE id=?", enabled ? "enable" : "disable", user);
    }
    public void grantEnabled(long user, boolean enabled) {
        jdbc.update("UPDATE ops_harness_grant SET enabled=? WHERE user_id=?", enabled ? 1 : 0, user);
    }
    public void addAdmin(long user) { jdbc.update("INSERT INTO sys_user_role VALUES(?,2)", user); }
    public JdbcTemplate jdbc() { return jdbc; }
    @Override public void close() { http.close(); }
}
