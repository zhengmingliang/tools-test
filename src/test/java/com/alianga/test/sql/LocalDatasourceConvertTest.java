package com.alianga.test.sql;

import com.alianga.jkit.sql.SQL;
import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.schema.convert.ConversionResult;
import com.alianga.jkit.sql.schema.convert.SqlSchemaConverter;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 使用本机 {@code src/test/resources/datasource} 里的 MySQL / PostgreSQL / Oracle
 * 真库验证转换后的 DDL 能建表。文件不存在或某库连不上则 skip，不把 CI 打红。
 *
 * @author 郑明亮
 */
public class LocalDatasourceConvertTest {
    private static final String TABLE = "jkit_sql_conv_t";
    private static final String MYSQL_DDL = "CREATE TABLE " + TABLE + " ("
            + "id INT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
            + "name VARCHAR(32) NOT NULL, "
            + "flag TINYINT(1) DEFAULT 0, "
            + "amt DECIMAL(10,2), "
            + "ts DATETIME"
            + ")";

    private static Map<String, Ds> sources = new LinkedHashMap<String, Ds>();

    /**
     * 读取 datasource 文件。
     *
     * @throws Exception 读文件失败
     */
    @BeforeClass
    public static void loadDatasource() throws Exception {
        File file = new File("src/test/resources/datasource");
        Assume.assumeTrue("local datasource file not present", file.isFile());
        sources = parse(file);
    }

    @Test
    public void mysqlSourceDdlCreates() throws Exception {
        Ds ds = require("mysql");
        dropQuiet(ds, "DROP TABLE IF EXISTS " + TABLE);
        exec(ds, MYSQL_DDL);
        dropQuiet(ds, "DROP TABLE IF EXISTS " + TABLE);
    }

    @Test
    public void mysqlToPostgresCreates() throws Exception {
        Ds ds = require("postgresql");
        ConversionResult r = SqlSchemaConverter.convert(
                MYSQL_DDL, SqlDialect.MYSQL, SqlDialect.POSTGRES);
        String ddl = r.sql();
        assertFalse(ddl, ddl.toUpperCase(Locale.ROOT).contains("AUTO_INCREMENT"));
        assertTrue(ddl, ddl.toUpperCase(Locale.ROOT).contains("IDENTITY")
                || ddl.toUpperCase(Locale.ROOT).contains("SERIAL"));
        dropQuiet(ds, "DROP TABLE IF EXISTS " + TABLE);
        exec(ds, ddl);
        dropQuiet(ds, "DROP TABLE IF EXISTS " + TABLE);
    }

    @Test
    public void mysqlToOracle11gCreates() throws Exception {
        Ds ds = require("oracle");
        ConversionResult r = SqlSchemaConverter.convert(
                MYSQL_DDL, SqlDialect.MYSQL, SqlDialect.ORACLE);
        String ddl = r.sql();
        assertFalse("11g must not emit IDENTITY: " + ddl,
                ddl.toUpperCase(Locale.ROOT).contains("IDENTITY"));
        assertTrue(r.report().hasBlockingIssues());
        dropQuiet(ds, "DROP TABLE " + TABLE);
        exec(ds, ddl);
        dropQuiet(ds, "DROP TABLE " + TABLE);
    }

    @Test
    public void mysqlToH2MemCreates() throws Exception {
        Ds ds = new Ds("jdbc:h2:mem:jkit_sql_conv;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        ConversionResult r = SqlSchemaConverter.convert(
                MYSQL_DDL, SqlDialect.MYSQL, SqlDialect.H2);
        dropQuiet(ds, "DROP TABLE IF EXISTS " + TABLE);
        exec(ds, r.sql());
        dropQuiet(ds, "DROP TABLE IF EXISTS " + TABLE);
    }

    private static Ds require(String key) {
        Ds ds = sources.get(key);
        Assume.assumeTrue("datasource section missing: " + key, ds != null);
        Assume.assumeTrue("cannot connect " + key + " " + ds.url, ping(ds));
        return ds;
    }

    private static boolean ping(Ds ds) {
        try (Connection c = DriverManager.getConnection(ds.url, ds.user, ds.password)) {
            return c != null;
        } catch (SQLException e) {
            return false;
        }
    }

    private static void exec(Ds ds, String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(ds.url, ds.user, ds.password);
             Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static void dropQuiet(Ds ds, String sql) {
        try {
            exec(ds, sql);
        } catch (SQLException ignored) {
            // 表可能不存在
        }
    }

    private static Map<String, Ds> parse(File file) throws Exception {
        Map<String, Ds> map = new LinkedHashMap<String, Ds>();
        String section = null;
        String url = null;
        String user = null;
        String password = null;
        String oracleHost = null;
        String oracleService = null;
        BufferedReader in = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = in.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty()) {
                    continue;
                }
                if (t.startsWith("###")) {
                    store(map, section, url, user, password, oracleHost, oracleService);
                    section = t.substring(3).trim().toLowerCase(Locale.ROOT);
                    url = null;
                    user = null;
                    password = null;
                    oracleHost = null;
                    oracleService = null;
                    continue;
                }
                if (section == null) {
                    continue;
                }
                if ("oracle".equals(section)) {
                    if (oracleHost == null && t.contains(":")) {
                        oracleHost = t;
                    } else if (t.contains("服务名") || t.toUpperCase(Locale.ROOT).startsWith("SID")
                            || t.toUpperCase(Locale.ROOT).startsWith("ORCL")) {
                        int colon = t.indexOf('：');
                        if (colon < 0) {
                            colon = t.indexOf(':');
                        }
                        oracleService = colon >= 0 ? t.substring(colon + 1).trim() : t.trim();
                    } else if (user == null) {
                        user = t;
                    } else if (password == null) {
                        password = t;
                    }
                } else if (t.startsWith("jdbc:")) {
                    url = t;
                } else if (user == null) {
                    user = t;
                } else if (password == null) {
                    password = t;
                }
            }
            store(map, section, url, user, password, oracleHost, oracleService);
        } finally {
            in.close();
        }
        return map;
    }

    private static void store(Map<String, Ds> map, String section, String url, String user,
                              String password, String oracleHost, String oracleService) {
        if (section == null) {
            return;
        }
        if ("oracle".equals(section) && oracleHost != null) {
            String svc = oracleService == null || oracleService.isEmpty() ? "ORCL" : oracleService;
            url = "jdbc:oracle:thin:@" + oracleHost + ":" + svc;
        }
        if (url == null) {
            return;
        }
        map.put(section, new Ds(url, user == null ? "" : user, password == null ? "" : password));
    }

    private static final class Ds {
        final String url;
        final String user;
        final String password;

        Ds(String url, String user, String password) {
            this.url = url;
            this.user = user;
            this.password = password;
        }
    }
}
