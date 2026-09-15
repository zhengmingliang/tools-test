package com.alianga.test.sql;

import com.alianga.jkit.sql.SQL;
import com.alianga.jkit.sql.SqlDialect;

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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 真库执行 {@link SQL#bind} 填充后的 SQL：注入 payload 不能拆语句。
 * datasource 文件缺失或某库连不上则 skip。
 *
 * @author 郑明亮
 */
public class LocalDatasourceBindTest {
    private static final String TABLE = "jkit_sql_bind_t";
    private static final String PAYLOAD = "'; DROP TABLE " + TABLE + "; --";

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
    public void mysqlBindInjectionStaysLiteral() throws Exception {
        run("mysql", SqlDialect.MYSQL);
    }

    @Test
    public void postgresBindInjectionStaysLiteral() throws Exception {
        run("postgresql", SqlDialect.POSTGRES);
    }

    @Test
    public void damengBindInjectionStaysLiteral() throws Exception {
        run("达梦", SqlDialect.DAMENG);
    }

    @Test
    public void sqlserverBindInjectionStaysLiteral() throws Exception {
        run("sqlserver", SqlDialect.SQLSERVER);
    }

    @Test
    public void opengaussBindInjectionStaysLiteral() throws Exception {
        run("opengauss", SqlDialect.POSTGRES);
    }

    @Test
    public void h2FileBindInjectionStaysLiteral() throws Exception {
        Ds ds = sources.get("h2");
        if (ds == null) {
            ds = new Ds("jdbc:h2:mem:jkit_sql_bind;DB_CLOSE_DELAY=-1", "sa", "");
        }
        Assume.assumeTrue("cannot connect h2 " + ds.url, ping(ds));
        executeBind(ds, SqlDialect.H2, true);
    }

    private static void run(String key, SqlDialect dialect) throws Exception {
        Ds ds = sources.get(key);
        Assume.assumeTrue("datasource section missing: " + key, ds != null);
        Assume.assumeTrue("cannot connect " + key + " " + ds.url, ping(ds));
        boolean ifExists = dialect != SqlDialect.ORACLE && dialect != SqlDialect.ORACLE12;
        executeBind(ds, dialect, ifExists);
    }

    private static void executeBind(Ds ds, SqlDialect dialect, boolean ifExists) throws Exception {
        String drop = ifExists ? "DROP TABLE IF EXISTS " + TABLE : "DROP TABLE " + TABLE;
        dropQuiet(ds, drop);
        String create = "CREATE TABLE " + TABLE + " (name VARCHAR(200))";
        if (dialect == SqlDialect.SQLSERVER) {
            create = "CREATE TABLE " + TABLE + " (name NVARCHAR(200))";
        }
        exec(ds, create);
        String insert = SQL.bind("INSERT INTO " + TABLE + " (name) VALUES (?)", dialect, "ok");
        exec(ds, insert);
        String select = SQL.bind("SELECT name FROM " + TABLE + " WHERE name = ?", dialect, PAYLOAD);
        try (Connection c = DriverManager.getConnection(ds.url, ds.user, ds.password);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(select)) {
            assertTrue("payload must not match stored 'ok'", !rs.next());
        }
        try (Connection c = DriverManager.getConnection(ds.url, ds.user, ds.password);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM " + TABLE)) {
            assertTrue(rs.next());
            assertEquals("ok", rs.getString(1));
        }
        dropQuiet(ds, drop);
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
