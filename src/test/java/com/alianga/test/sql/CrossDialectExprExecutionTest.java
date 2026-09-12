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
    private static Connection sqlServerConn;
    private static Connection oracleConn;

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
        // 本机已在运行的 SQL Server 2017 / Oracle 11g 实例，直接连；连不上则相关用例 skip
        sqlServerConn = tryConnect(
                "jdbc:sqlserver://127.0.0.1:1433;databaseName=master;encrypt=false;trustServerCertificate=true",
                "sa", "Ireport@2025", "com.microsoft.sqlserver.jdbc.SQLServerDriver");
        oracleConn = tryConnect(
                "jdbc:oracle:thin:@localhost:1521:ORCL", "ZML", "mingliang",
                "oracle.jdbc.OracleDriver");
        if (oracleConn != null) {
            try (Statement st = oracleConn.createStatement()) {
                st.execute("ALTER SESSION SET NLS_DATE_FORMAT='YYYY-MM-DD'");
            } catch (Exception ignored) {
                // 部分驱动/实例不支持，后续依赖字符串日期解析的 Oracle 用例会 skip
            }
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
        if (sqlServerConn != null) {
            try {
                sqlServerConn.close();
            } catch (Exception ignored) {
            }
        }
        if (oracleConn != null) {
            try {
                oracleConn.close();
            } catch (Exception ignored) {
            }
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

    /**
     * PG 的 {@code a || b} 在 SQL Server 上必须改写成 CONCAT(a, b)（SQL Server 不支持 || 拼接），
     * 丢真 SQL Server 执行验证拼接结果正确。
     */
    @Test
    public void pgToSqlServerConcatOperatorExecutes() throws Exception {
        Assume.assumeNotNull(sqlServerConn);
        ConversionResult r = SqlSchemaConverter.convert(
                "SELECT 'x' || 'y'", SqlDialect.POSTGRES, SqlDialect.SQLSERVER);
        String sql = r.sql();
        assertTrue("SQL Server 应改写成 CONCAT: " + sql, sql.toUpperCase().contains("CONCAT"));
        try (Statement st = sqlServerConn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            assertEquals("xy", rs.getString(1));
        }
    }

    /**
     * MySQL 的 DATE_ADD(a, INTERVAL 3 DAY) 在 SQL Server 上应变成 {@code DATEADD(day, 3, a)}，
     * 丢真 SQL Server 执行验证日期加 3 天正确。
     */
    @Test
    public void mysqlToSqlServerDateAddExecutes() throws Exception {
        Assume.assumeNotNull(sqlServerConn);
        ConversionResult r = SqlSchemaConverter.convert(
                "SELECT DATE_ADD('2024-01-01', INTERVAL 3 DAY)",
                SqlDialect.MYSQL, SqlDialect.SQLSERVER);
        String sql = r.sql();
        assertTrue("SQL Server 应产出 DATEADD: " + sql, sql.toUpperCase().contains("DATEADD"));
        try (Statement st = sqlServerConn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            assertEquals(java.sql.Date.valueOf("2024-01-04"), rs.getDate(1));
        }
    }

    /**
     * MySQL 的 DATE_ADD(a, INTERVAL 3 DAY) 在 Oracle 上应变成 {@code CAST(a AS DATE) + INTERVAL '3' DAY}，
     * 丢真 Oracle 执行验证日期加 3 天正确（连接时已设 NLS_DATE_FORMAT=YYYY-MM-DD）。
     */
    @Test
    public void mysqlToOracleDateAddExecutes() throws Exception {
        Assume.assumeNotNull(oracleConn);
        ConversionResult r = SqlSchemaConverter.convert(
                "SELECT DATE_ADD('2024-01-01', INTERVAL 3 DAY)",
                SqlDialect.MYSQL, SqlDialect.ORACLE);
        String sql = r.sql();
        assertTrue("Oracle 应产出 INTERVAL 字面量: " + sql,
                sql.toUpperCase().contains("INTERVAL") && sql.toUpperCase().contains("CAST"));
        try (Statement st = oracleConn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            assertEquals(java.sql.Date.valueOf("2024-01-04"), rs.getDate(1));
        }
    }

    /**
     * MySQL 的 DATEDIFF(d1, d2) 在 Oracle 上应变成 {@code TRUNC(d1) - TRUNC(d2)}，
     * 丢真 Oracle 执行验证返回整数天差。
     */
    @Test
    public void mysqlToOracleDatediffExecutes() throws Exception {
        Assume.assumeNotNull(oracleConn);
        ConversionResult r = SqlSchemaConverter.convert(
                "SELECT DATEDIFF('2024-01-04', '2024-01-01')",
                SqlDialect.MYSQL, SqlDialect.ORACLE);
        String sql = r.sql();
        assertTrue("Oracle 应产出 TRUNC 相减: " + sql, sql.toUpperCase().contains("TRUNC"));
        try (Statement st = oracleConn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            assertEquals(3, rs.getInt(1));
        }
    }

    /**
     * MySQL 的 {@code DATEDIFF(d1, d2)}（2 参数）在 SQL Server 目标上必须展开为
     * {@code DATEDIFF(day, d2, d1)}——SQL Server 的 DATEDIFF 必填 datepart，且参数顺序
     * 也相反（{@code DATEDIFF(datepart, startdate, enddate) = end - start}）。
     * 丢真 SQL Server 执行验证返回 3。
     */
    @Test
    public void mysqlToSqlServerDatediffRewrittenAndExecutes() throws Exception {
        // 形态断言：始终跑，不依赖真库在线
        ConversionResult r = SqlSchemaConverter.convert(
                "SELECT DATEDIFF('2024-01-04', '2024-01-01')",
                SqlDialect.MYSQL, SqlDialect.SQLSERVER);
        String sql = r.sql();
        assertTrue("SQL Server 2参 DATEDIFF 应展开为 DATEDIFF(day, '2024-01-01', '2024-01-04'): " + sql,
                sql.toUpperCase().contains("DATEDIFF(DAY, '2024-01-01', '2024-01-04')"));
        // 真库执行：仅当 sqlServer 在线
        Assume.assumeNotNull(sqlServerConn);
        try (Statement st = sqlServerConn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            assertEquals(3, rs.getInt(1));
        }
    }

    private static Connection tryConnect(String url, String user, String pass, String driverClass) {
        try {
            if (driverClass != null) {
                Class.forName(driverClass);
            }
            return DriverManager.getConnection(url, user, pass);
        } catch (Exception e) {
            System.out.println("[skip] " + url + " -> " + e.getMessage());
            return null;
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
