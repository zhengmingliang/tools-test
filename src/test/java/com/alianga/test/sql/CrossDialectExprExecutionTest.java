package com.alianga.test.sql;

import com.alianga.jkit.sql.SQL;
import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.schema.convert.ConversionResult;
import com.alianga.jkit.sql.schema.convert.SqlSchemaConverter;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 把 jkit 的转换产物丢进真实数据库执行，补 {@link CrossDialectDdlExecutionTest} 只覆盖
 * DDL 的缺口——这里覆盖表达式 / 函数改写这类「jkit 自己的 parser 比真库宽松、复解析抓不到」
 * 的坑位。需要本机 Docker 且已有 {@code postgres:16-alpine} / {@code mysql:8.0.36} 本地镜像，
 * 没有则 skip。SQLite 走内存库，不依赖 Docker。
 *
 * @author 郑明亮
 */
public class CrossDialectExprExecutionTest {
    private static PostgreSQLContainer<?> postgres;
    private static MySQLContainer<?> mysql;

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
        try {
            mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0.36"))
                    .withImagePullPolicy(imageName -> false);
            mysql.start();
        } catch (RuntimeException e) {
            Assume.assumeNoException("Could not start mysql:8.0.36 from local images", e);
        }
    }

    @AfterClass
    public static void stopContainers() {
        if (postgres != null) {
            postgres.stop();
        }
        if (mysql != null) {
            mysql.stop();
        }
    }

    /**
     * MySQL→PG 的 DECIMAL(10,2) 不能被截断成正则 bug 那种 {@code NUMERIC(10,}，
     * 且转换产物真能在 PG 建表、保留小数位精度。
     */
    @Test
    public void mysqlToPostgresNumericNotTruncated() throws Exception {
        Assume.assumeNotNull(postgres);
        ConversionResult r = SqlSchemaConverter.convert(
                "CREATE TABLE money (id INT PRIMARY KEY, amt DECIMAL(10,2))",
                SqlDialect.MYSQL, SqlDialect.POSTGRES);
        String ddl = r.sql();
        assertTrue("DECIMAL/NUMERIC 应保留 (10,2) 精度: " + ddl,
                ddl.toUpperCase().contains("NUMERIC(10, 2)") || ddl.toUpperCase().contains("NUMERIC(10,2)"));
        dropQuiet(postgres, "DROP TABLE IF EXISTS money");
        exec(postgres, ddl);
        exec(postgres, "INSERT INTO money VALUES (1, 12.34)");
        try (Connection c = conn(postgres);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT amt FROM money WHERE id = 1")) {
            assertTrue(rs.next());
            assertEquals(12.34, rs.getDouble(1), 0.0001);
        }
        dropQuiet(postgres, "DROP TABLE IF EXISTS money");
    }

    /**
     * MySQL 的 DATE_ADD(a, INTERVAL 3 DAY) 在 PG 上应变成 {@code a + INTERVAL '3 day'}，
     * 丢真 PG 执行验证结果正确（jkit 自己的复解析抓不到 INTERVAL 引号的语法问题）。
     */
    @Test
    public void dateAddToPostgresIntervalExecutes() throws Exception {
        Assume.assumeNotNull(postgres);
        ConversionResult r = SqlSchemaConverter.convert(
                "SELECT DATE_ADD('2024-01-01', INTERVAL 3 DAY)",
                SqlDialect.MYSQL, SqlDialect.POSTGRES);
        String sql = r.sql();
        assertTrue("PG 应产出 INTERVAL 字面量: " + sql, sql.toUpperCase().contains("INTERVAL"));
        try (Connection c = conn(postgres);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            assertEquals(java.sql.Date.valueOf("2024-01-04"), rs.getDate(1));
        }
    }

    /**
     * MySQL 的 DATEDIFF(d1, d2) 在 PG 上应变成 {@code CAST(d1 AS DATE) - CAST(d2 AS DATE)}，
     * 丢真 PG 执行验证返回整数天差（语义等价，而非静默保留原文）。
     */
    @Test
    public void datediffToPostgresCastSubtractionExecutes() throws Exception {
        Assume.assumeNotNull(postgres);
        ConversionResult r = SqlSchemaConverter.convert(
                "SELECT DATEDIFF('2024-01-04', '2024-01-01')",
                SqlDialect.MYSQL, SqlDialect.POSTGRES);
        String sql = r.sql();
        assertTrue("PG 应产出 CAST AS DATE 减法: " + sql,
                sql.toUpperCase().contains("CAST") && sql.toUpperCase().contains("DATE"));
        try (Connection c = conn(postgres);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            assertEquals(3, rs.getInt(1));
        }
    }

    /**
     * PG 的 {@code a || b} 在 MySQL 上必须改写成 CONCAT(a, b)（MySQL 默认 || 是逻辑或），
     * 丢真 MySQL 执行验证拼接结果正确。
     */
    @Test
    public void concatOperatorToMysqlConcatExecutes() throws Exception {
        Assume.assumeNotNull(mysql);
        ConversionResult r = SqlSchemaConverter.convert(
                "SELECT 'x' || 'y'",
                SqlDialect.POSTGRES, SqlDialect.MYSQL);
        String sql = r.sql();
        assertTrue("MySQL 应改写成 CONCAT: " + sql, sql.toUpperCase().contains("CONCAT"));
        try (Connection c = conn(mysql);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            assertEquals("xy", rs.getString(1));
        }
    }

    /**
     * MySQL→SQLite 的 AUTO_INCREMENT 必须变成 {INTEGER PRIMARY KEY AUTOINCREMENT}，
     * AUTOINCREMENT 紧跟 PRIMARY KEY 且类型必须是 INTEGER——丢真 SQLite 执行验证建表与自增可用。
     */
    @Test
    public void mysqlToSqliteAutoincrementExecutes() throws Exception {
        Class.forName("org.sqlite.JDBC");
        String url = "jdbc:sqlite::memory:";
        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement()) {
            ConversionResult r = SqlSchemaConverter.convert(
                    "CREATE TABLE u (id INT NOT NULL AUTO_INCREMENT PRIMARY KEY, n VARCHAR(10))",
                    SqlDialect.MYSQL, SqlDialect.SQLITE);
            String ddl = r.sql();
            assertTrue("SQLite 必须含 AUTOINCREMENT: " + ddl, ddl.toUpperCase().contains("AUTOINCREMENT"));
            st.execute(ddl);
            st.execute("INSERT INTO u(n) VALUES ('a')");
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM u")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    private static Connection conn(PostgreSQLContainer<?> c) throws Exception {
        return DriverManager.getConnection(c.getJdbcUrl(), c.getUsername(), c.getPassword());
    }

    private static Connection conn(MySQLContainer<?> c) throws Exception {
        return DriverManager.getConnection(c.getJdbcUrl(), c.getUsername(), c.getPassword());
    }

    private static void exec(PostgreSQLContainer<?> c, String sql) throws Exception {
        try (Connection con = conn(c); Statement st = con.createStatement()) {
            st.execute(sql);
        }
    }

    private static void dropQuiet(PostgreSQLContainer<?> c, String sql) {
        try {
            exec(c, sql);
        } catch (Exception ignored) {
            // 表可能不存在
        }
    }
}
