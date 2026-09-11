package com.alianga.test.sql;

import com.alianga.jkit.sql.SQL;
import com.alianga.jkit.sql.SqlDialect;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 把 MySQL→PostgreSQL 转换后的 DDL 丢进真实 PG 容器执行。需要本机 Docker 且已有
 * {@code postgres:16-alpine} 镜像（默认不拉远程，避免 Docker Hub 超时把 CI 打红）。
 *
 * @author 郑明亮
 */
public class CrossDialectDdlExecutionTest {
    private static PostgreSQLContainer<?> postgres;

    private static final String MYSQL_DDL = "CREATE TABLE conv_user ("
            + "id INT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
            + "name VARCHAR(32) NOT NULL, "
            + "flag TINYINT(1) DEFAULT 0, "
            + "amt DECIMAL(10,2), "
            + "ts DATETIME"
            + ")";

    /**
     * 启动 PG 容器；无 Docker / 无本地镜像则 skip。
     */
    @BeforeClass
    public static void startContainers() {
        try {
            Assume.assumeTrue("Docker is required",
                    DockerClientFactory.instance().isDockerAvailable());
        } catch (RuntimeException e) {
            Assume.assumeNoException(e);
        }
        try {
            postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("conv")
                    .withUsername("test")
                    .withPassword("test")
                    .withImagePullPolicy(imageName -> false);
            postgres.start();
        } catch (RuntimeException e) {
            Assume.assumeNoException("Could not start postgres:16-alpine from local images", e);
        }
    }

    /**
     * 停止容器。
     */
    @AfterClass
    public static void stopContainers() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    public void convertedPostgresDdlCreatesTable() throws Exception {
        Assume.assumeNotNull(postgres);
        String pgDdl = SQL.convert(MYSQL_DDL, SqlDialect.MYSQL, SqlDialect.POSTGRES);
        assertFalse("converted DDL should not keep AUTO_INCREMENT: " + pgDdl,
                pgDdl.toUpperCase().contains("AUTO_INCREMENT"));
        assertTrue(pgDdl, pgDdl.toUpperCase().contains("IDENTITY")
                || pgDdl.toUpperCase().contains("SERIAL"));
        exec("DROP TABLE IF EXISTS conv_user");
        exec(pgDdl);
        exec("DROP TABLE IF EXISTS conv_user");
    }

    private static void exec(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }
}
