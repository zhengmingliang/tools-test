package com.alianga.test.sql;

import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.auto.SqlAuto;
import com.alianga.jkit.sql.auto.SqlAutoChange;
import com.alianga.jkit.sql.auto.SqlAutoMode;
import com.alianga.jkit.sql.auto.SqlAutoOptions;
import com.alianga.jkit.sql.auto.SqlAutoPlan;
import com.alianga.jkit.sql.auto.boot2.SqlAutoAutoConfiguration;
import com.alianga.jkit.sql.schema.convert.SqlSchemaConvertOptions;
import com.alianga.jkit.sql.auto.boot2.SqlAutoProperties;
import com.alianga.jkit.sql.entity.SqlColumn;
import com.alianga.jkit.sql.entity.SqlGenerated;
import com.alianga.jkit.sql.entity.SqlId;
import com.alianga.jkit.sql.entity.SqlTable;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.sql.DataSource;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 用本机 {@code src/test/resources/datasource} 验证 jkit-sql-auto：
 * 类型 / 自增 / 注释 / 索引 / 外键、加列、删表。某库连不上则 skip。
 *
 * @author 郑明亮
 */
public class LocalDatasourceSqlAutoTest {
    private static final String ORG = "jkit_sql_auto_org";
    private static final String USER = "jkit_sql_auto_user";

    private static Map<String, Ds> sources = new LinkedHashMap<String, Ds>();

    /**
     * 读 datasource 文件。
     *
     * @throws Exception 读失败
     */
    @BeforeClass
    public static void loadDatasource() throws Exception {
        File file = new File("src/test/resources/datasource");
        Assume.assumeTrue("local datasource file not present", file.isFile());
        sources = parse(file);
        loadOptionalDrivers();
    }

    @Test
    public void mysqlFullCoverage() throws Exception {
        runFull("mysql", SqlDialect.MYSQL, null);
    }

    @Test
    public void postgresqlFullCoverage() throws Exception {
        runFull("postgresql", SqlDialect.POSTGRES, null);
    }

    @Test
    public void oracleFullCoverage() throws Exception {
        runFull("oracle", SqlDialect.ORACLE, null);
    }

    @Test
    public void damengFullCoverage() throws Exception {
        runFull("达梦", SqlDialect.DAMENG, null);
    }

    @Test
    public void gbase8aFullCoverage() throws Exception {
        runFull("gbase8a", SqlDialect.MYSQL, null);
    }

    @Test
    public void opengaussFullCoverage() throws Exception {
        runFull("opengauss", SqlDialect.POSTGRES, "org.opengauss.Driver");
    }

    @Test
    public void h2FileFullCoverage() throws Exception {
        runFull("h2", SqlDialect.H2, null);
    }

    @Test
    public void sqliteFullCoverage() throws Exception {
        runFull("sqlite", SqlDialect.SQLITE, null);
    }

    @Test
    public void sqlserverFullCoverage() throws Exception {
        runFull("sqlserver", SqlDialect.SQLSERVER, "com.microsoft.sqlserver.jdbc.SQLServerDriver");
    }

    @Test
    public void duckdbFullCoverage() throws Exception {
        runFull("duckdb", SqlDialect.SQLITE, "org.duckdb.DuckDBDriver");
    }

    @Test
    public void springBoot2StarterOnMysql() throws Exception {
        Ds ds = require("mysql");
        DataSource dataSource = DataSourceBuilder.create()
                .url(ds.url)
                .username(ds.user)
                .password(ds.password)
                .build();
        dropQuiet(ds);
        SqlAutoProperties props = new SqlAutoProperties();
        props.setEntities(Arrays.asList(Org.class.getName(), User.class.getName()));
        props.setMode("update");
        props.setShowSql(false);
        props.setCreateIndex(true);
        SqlAutoAutoConfiguration.SqlAutoStartupListener listener =
                new SqlAutoAutoConfiguration.SqlAutoStartupListener(props, dataSource);
        listener.runNow();
        Connection c = dataSource.getConnection();
        try {
            assertTrue(tableExists(c, ORG));
            assertTrue(tableExists(c, USER));
        } finally {
            c.close();
        }
        SqlAuto.drop(SqlAutoOptions.defaults()
                .url(ds.url).username(ds.user).password(ds.password)
                .entities(User.class, Org.class)
                .showSql(false));
        assertFalse(tableExists(ds, USER));
        assertFalse(tableExists(ds, ORG));
    }

    private static void runFull(String key, SqlDialect dialect, String driver) throws Exception {
        Ds ds = require(key);
        dropQuiet(ds);
        SqlAutoOptions base = SqlAutoOptions.defaults()
                .url(ds.url)
                .username(ds.user)
                .password(ds.password)
                .driver(driver)
                .dialect(dialect)
                .showSql(true)
                .createIndex(true)
                .failFast(true);
        if ("opengauss".equals(key)) {
            base.postgresIdentityStyle(SqlSchemaConvertOptions.PostgresIdentityStyle.SERIAL);
        }
        if ("gbase8a".equals(key)) {
            base.foreignKeys(false).createIndex(false);
        }
        if ("duckdb".equals(key)) {
            base.autoIncrement(false).foreignKeys(false).createIndex(false);
        }

        SqlAutoPlan created = SqlAuto.run(base.entities(User.class, Org.class).mode(SqlAutoMode.UPDATE));
        assertFalse(created.ofKind(SqlAutoChange.Kind.CREATE_TABLE).isEmpty());
        assertTrue(tableExists(ds, ORG));
        assertTrue(tableExists(ds, USER));

        assertColumn(ds, USER, "id");
        assertColumn(ds, USER, "user_name");
        assertColumn(ds, USER, "email");
        assertColumn(ds, USER, "age");
        assertColumn(ds, USER, "amount");
        assertColumn(ds, USER, "active");
        assertColumn(ds, USER, "born_on");
        assertColumn(ds, USER, "updated_at");
        assertColumn(ds, USER, "org_id");
        assertFalse(nullable(ds, USER, "user_name"));
        assertTrue(nullable(ds, USER, "email") || dialect == SqlDialect.ORACLE
                || dialect == SqlDialect.DAMENG);

        if ("duckdb".equals(key) || dialect == SqlDialect.ORACLE) {
            assertTrue(isPrimaryKey(ds, USER, "id"));
        } else {
            assertTrue("auto-increment/identity missing on " + key,
                    isAutoIncrement(ds, USER, "id") || isPrimaryKey(ds, USER, "id"));
        }

        if (supportsComment(key, dialect)) {
            String remarks = columnRemarks(ds, USER, "user_name");
            assertTrue("missing comment on " + key + ": " + remarks,
                    remarks != null && (remarks.contains("用户名") || remarks.trim().length() > 0));
        }
        if (dialect == SqlDialect.ORACLE) {
            assertTrue("sequence missing on " + key, sequenceExists(ds, USER + "_id_seq"));
        }

        if (supportsIndex(dialect, key)) {
            assertTrue("index idx_auto_user_name missing on " + key,
                    indexExists(ds, USER, "idx_auto_user_name") || indexOnColumn(ds, USER, "user_name"));
        }
        if (supportsForeignKey(dialect, key)) {
            assertTrue("fk org_id missing on " + key, foreignKeyExists(ds, USER, ORG));
        }

        SqlAutoPlan again = SqlAuto.run(base.entities(User.class, Org.class).mode(SqlAutoMode.UPDATE));
        assertTrue(again.ofKind(SqlAutoChange.Kind.CREATE_TABLE).isEmpty());
        assertTrue(again.ofKind(SqlAutoChange.Kind.ADD_COLUMN).isEmpty());

        SqlAutoPlan added = SqlAuto.run(base.entities(UserV2.class, Org.class).mode(SqlAutoMode.UPDATE));
        assertFalse(added.toString(), added.ofKind(SqlAutoChange.Kind.ADD_COLUMN).isEmpty());
        assertColumn(ds, USER, "nickname");

        SqlAutoPlan dropped = SqlAuto.drop(base.entities(UserV2.class, Org.class));
        assertFalse(dropped.ofKind(SqlAutoChange.Kind.DROP_TABLE).isEmpty());
        assertFalse(tableExists(ds, USER));
        assertFalse(tableExists(ds, ORG));
    }

    private static boolean supportsComment(String key, SqlDialect dialect) {
        if ("sqlite".equals(key) || "duckdb".equals(key)) {
            return false;
        }
        return true;
    }

    private static boolean sequenceExists(Ds ds, String seq) throws SQLException {
        Connection c = connect(ds);
        try {
            ResultSet rs = c.getMetaData().getTables(c.getCatalog(), schema(c.getMetaData()),
                    lookup(c.getMetaData(), seq), new String[] {"SEQUENCE"});
            try {
                if (rs.next()) {
                    return true;
                }
            } finally {
                rs.close();
            }
            Statement st = c.createStatement();
            try {
                ResultSet rs2 = st.executeQuery(
                        "SELECT sequence_name FROM user_sequences WHERE LOWER(sequence_name) = LOWER('"
                                + seq + "')");
                try {
                    return rs2.next();
                } finally {
                    rs2.close();
                }
            } catch (SQLException ignored) {
                return false;
            } finally {
                st.close();
            }
        } finally {
            c.close();
        }
    }

    private static boolean supportsIndex(SqlDialect dialect, String key) {
        if ("duckdb".equals(key) || "gbase8a".equals(key)) {
            return false;
        }
        return dialect != SqlDialect.HIVE;
    }

    private static boolean supportsForeignKey(SqlDialect dialect, String key) {
        if ("sqlite".equals(key) || "duckdb".equals(key) || "h2".equals(key) || "gbase8a".equals(key)) {
            return false;
        }
        return dialect != SqlDialect.HIVE;
    }

    private static Ds require(String key) {
        Ds ds = sources.get(key);
        Assume.assumeTrue("datasource section missing: " + key, ds != null);
        String forced = forcedDriver(key);
        Assume.assumeTrue("cannot connect " + key + " " + ds.url, ping(ds, forced));
        return ds;
    }

    private static String forcedDriver(String key) {
        if ("opengauss".equals(key)) {
            return "org.opengauss.Driver";
        }
        if ("duckdb".equals(key)) {
            return "org.duckdb.DuckDBDriver";
        }
        if ("sqlserver".equals(key)) {
            return "com.microsoft.sqlserver.jdbc.SQLServerDriver";
        }
        return null;
    }

    private static boolean ping(Ds ds, String forcedDriver) {
        try {
            if (forcedDriver != null) {
                Class.forName(forcedDriver);
            } else {
                loadDriver(ds.url);
            }
            Connection c = DriverManager.getConnection(ds.url, ds.user, ds.password);
            c.close();
            return true;
        } catch (Exception e) {
            System.out.println("[skip] " + ds.url + " -> " + e.getMessage());
            return false;
        }
    }

    private static void loadDriver(String url) {
        String driver = com.alianga.jkit.sql.auto.SqlAutoDialects.driverForUrl(url);
        if (driver == null) {
            return;
        }
        try {
            Class.forName(driver);
        } catch (ClassNotFoundException ignored) {
            // DriverManager 可能已能解析
        }
    }

    private static void assertColumn(Ds ds, String table, String column) throws SQLException {
        assertTrue(table + "." + column, columnExists(ds, table, column));
    }

    private static boolean tableExists(Ds ds, String table) throws SQLException {
        Connection c = connect(ds);
        try {
            return tableExists(c, table);
        } finally {
            c.close();
        }
    }

    private static boolean tableExists(Connection c, String table) throws SQLException {
        DatabaseMetaData meta = c.getMetaData();
        String lookup = lookup(meta, table);
        ResultSet rs = meta.getTables(c.getCatalog(), schema(meta), lookup, new String[] {"TABLE", "BASE TABLE"});
        try {
            if (rs.next()) {
                return true;
            }
        } finally {
            rs.close();
        }
        if (!lookup.equals(table)) {
            ResultSet rs2 = meta.getTables(c.getCatalog(), schema(meta), table, new String[] {"TABLE", "BASE TABLE"});
            try {
                return rs2.next();
            } finally {
                rs2.close();
            }
        }
        return false;
    }

    private static boolean columnExists(Ds ds, String table, String column) throws SQLException {
        Connection c = connect(ds);
        try {
            return columnRs(c, table, column) != null;
        } finally {
            c.close();
        }
    }

    private static boolean nullable(Ds ds, String table, String column) throws SQLException {
        Connection c = connect(ds);
        try {
            ColInfo info = columnRs(c, table, column);
            return info != null && info.nullable != 0;
        } finally {
            c.close();
        }
    }

    private static String columnRemarks(Ds ds, String table, String column) throws SQLException {
        Connection c = connect(ds);
        try {
            ColInfo info = columnRs(c, table, column);
            if (info != null && info.remarks != null && info.remarks.trim().length() > 0) {
                return info.remarks;
            }
            String ss = sqlServerRemarks(c, table, column);
            if (ss != null && ss.trim().length() > 0) {
                return ss;
            }
            return oracleRemarks(c, table, column);
        } finally {
            c.close();
        }
    }

    private static String oracleRemarks(Connection c, String table, String column) {
        Statement st = null;
        ResultSet rs = null;
        try {
            st = c.createStatement();
            rs = st.executeQuery(
                    "SELECT comments FROM user_col_comments WHERE LOWER(table_name) = LOWER('"
                            + table + "') AND LOWER(column_name) = LOWER('" + column + "')");
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException ignored) {
            return null;
        } finally {
            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException ignored) {
                    // 忽略
                }
            }
            if (st != null) {
                try {
                    st.close();
                } catch (SQLException ignored) {
                    // 忽略
                }
            }
        }
    }

    private static String sqlServerRemarks(Connection c, String table, String column) {
        Statement st = null;
        ResultSet rs = null;
        try {
            st = c.createStatement();
            rs = st.executeQuery(
                    "SELECT CAST(ep.value AS nvarchar(4000)) FROM sys.extended_properties ep "
                            + "INNER JOIN sys.columns col ON ep.major_id = col.object_id AND ep.minor_id = col.column_id "
                            + "INNER JOIN sys.tables t ON t.object_id = col.object_id "
                            + "WHERE ep.name = 'MS_Description' AND t.name = '" + table
                            + "' AND col.name = '" + column + "'");
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException ignored) {
            return null;
        } finally {
            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException ignored) {
                    // 忽略
                }
            }
            if (st != null) {
                try {
                    st.close();
                } catch (SQLException ignored) {
                    // 忽略
                }
            }
        }
    }

    private static boolean isAutoIncrement(Ds ds, String table, String column) throws SQLException {
        Connection c = connect(ds);
        try {
            ColInfo info = columnRs(c, table, column);
            if (info != null && "YES".equalsIgnoreCase(info.autoIncrement)) {
                return true;
            }
            String type = info == null ? "" : String.valueOf(info.typeName).toUpperCase(Locale.ROOT);
            return type.contains("SERIAL") || type.contains("IDENTITY") || type.contains("AUTO_INCREMENT")
                    || type.contains("AUTOINCREMENT");
        } finally {
            c.close();
        }
    }

    private static boolean isPrimaryKey(Ds ds, String table, String column) throws SQLException {
        Connection c = connect(ds);
        try {
            DatabaseMetaData meta = c.getMetaData();
            String t = lookup(meta, table);
            ResultSet rs = meta.getPrimaryKeys(c.getCatalog(), schema(meta), t);
            try {
                while (rs.next()) {
                    if (column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) {
                        return true;
                    }
                }
            } finally {
                rs.close();
            }
            return false;
        } finally {
            c.close();
        }
    }

    private static boolean indexExists(Ds ds, String table, String indexName) throws SQLException {
        Connection c = connect(ds);
        try {
            DatabaseMetaData meta = c.getMetaData();
            ResultSet rs = meta.getIndexInfo(c.getCatalog(), schema(meta), lookup(meta, table), false, true);
            try {
                while (rs.next()) {
                    String n = rs.getString("INDEX_NAME");
                    if (n != null && n.equalsIgnoreCase(indexName)) {
                        return true;
                    }
                }
            } finally {
                rs.close();
            }
            return false;
        } finally {
            c.close();
        }
    }

    private static boolean indexOnColumn(Ds ds, String table, String column) throws SQLException {
        Connection c = connect(ds);
        try {
            DatabaseMetaData meta = c.getMetaData();
            ResultSet rs = meta.getIndexInfo(c.getCatalog(), schema(meta), lookup(meta, table), false, true);
            try {
                while (rs.next()) {
                    if (column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) {
                        return true;
                    }
                }
            } finally {
                rs.close();
            }
            return false;
        } finally {
            c.close();
        }
    }

    private static boolean foreignKeyExists(Ds ds, String table, String pkTable) throws SQLException {
        Connection c = connect(ds);
        try {
            DatabaseMetaData meta = c.getMetaData();
            ResultSet rs = meta.getImportedKeys(c.getCatalog(), schema(meta), lookup(meta, table));
            try {
                while (rs.next()) {
                    if (pkTable.equalsIgnoreCase(rs.getString("PKTABLE_NAME"))) {
                        return true;
                    }
                }
            } finally {
                rs.close();
            }
            return false;
        } finally {
            c.close();
        }
    }

    private static ColInfo columnRs(Connection c, String table, String column) throws SQLException {
        DatabaseMetaData meta = c.getMetaData();
        String t = lookup(meta, table);
        String col = lookup(meta, column);
        ResultSet rs = meta.getColumns(c.getCatalog(), schema(meta), t, col);
        try {
            if (rs.next()) {
                return ColInfo.from(rs);
            }
        } finally {
            rs.close();
        }
        ResultSet rs2 = meta.getColumns(c.getCatalog(), schema(meta), table, column);
        try {
            return rs2.next() ? ColInfo.from(rs2) : null;
        } finally {
            rs2.close();
        }
    }

    private static String lookup(DatabaseMetaData meta, String name) throws SQLException {
        if (meta.storesUpperCaseIdentifiers()) {
            return name.toUpperCase(Locale.ROOT);
        }
        if (meta.storesLowerCaseIdentifiers()) {
            return name.toLowerCase(Locale.ROOT);
        }
        return name;
    }

    private static String schema(DatabaseMetaData meta) throws SQLException {
        String product = meta.getDatabaseProductName();
        if (product == null) {
            return null;
        }
        String p = product.toLowerCase(Locale.ROOT);
        if (p.contains("oracle") || p.contains("dm dbms") || p.contains("dameng")) {
            String user = meta.getUserName();
            return user == null ? null : user.toUpperCase(Locale.ROOT);
        }
        if (p.contains("microsoft") || p.contains("sql server")) {
            return "dbo";
        }
        return null;
    }

    private static void dropQuiet(Ds ds) {
        List<String> sqls = new ArrayList<String>();
        sqls.add("DROP TABLE IF EXISTS " + USER);
        sqls.add("DROP TABLE IF EXISTS " + ORG);
        sqls.add("DROP TABLE " + USER);
        sqls.add("DROP TABLE " + ORG);
        sqls.add("DROP SEQUENCE " + USER + "_id_seq");
        sqls.add("DROP SEQUENCE " + ORG + "_id_seq");
        sqls.add("DROP SEQUENCE IF EXISTS " + USER + "_id_seq");
        sqls.add("DROP SEQUENCE IF EXISTS " + ORG + "_id_seq");
        for (int i = 0; i < sqls.size(); i++) {
            try {
                exec(ds, sqls.get(i));
            } catch (SQLException ignored) {
                // 表可能不存在或方言无 IF EXISTS
            }
        }
    }

    private static void exec(Ds ds, String sql) throws SQLException {
        Connection c = connect(ds);
        try {
            Statement st = c.createStatement();
            try {
                st.execute(sql);
            } finally {
                st.close();
            }
        } finally {
            c.close();
        }
    }

    private static Connection connect(Ds ds) throws SQLException {
        loadDriver(ds.url);
        if (ds.url.contains("opengauss") || ds.url.contains("gaussdb")) {
            try {
                Class.forName("org.opengauss.Driver");
            } catch (ClassNotFoundException ignored) {
                // 忽略
            }
        }
        return DriverManager.getConnection(ds.url, ds.user, ds.password);
    }

    private static void loadOptionalDrivers() {
        String[] extra = new String[] {
                "com.gbase.jdbc.Driver",
                "dm.jdbc.driver.DmDriver",
                "org.opengauss.Driver",
                "org.sqlite.JDBC",
                "org.duckdb.DuckDBDriver",
                "com.microsoft.sqlserver.jdbc.SQLServerDriver"
        };
        for (int i = 0; i < extra.length; i++) {
            try {
                Class.forName(extra[i]);
            } catch (ClassNotFoundException ignored) {
                // 没有对应驱动则该库 ping 失败后 skip
            }
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
        if (map.containsKey("h2") && map.get("h2").url.startsWith("jdbc:h2:")) {
            Ds h2 = map.get("h2");
            if (h2.user.isEmpty()) {
                map.put("h2", new Ds(h2.url, "sa", ""));
            }
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
        if (url == null && user != null && (user.startsWith("/") || user.contains(".db") || user.contains("duckdb"))) {
            if ("sqlite".equals(section)) {
                url = "jdbc:sqlite:" + user;
                user = "";
                password = "";
            } else if ("duckdb".equals(section)) {
                url = "jdbc:duckdb:" + user;
                user = "";
                password = "";
            }
        }
        if (url == null) {
            return;
        }
        if ("opengauss".equals(section) && url.startsWith("jdbc:gaussdb:")) {
            url = "jdbc:opengauss:" + url.substring("jdbc:gaussdb:".length());
        }
        map.put(section, new Ds(url, user == null ? "" : user, password == null ? "" : password));
    }

    /**
     * 被引用表。
     */
    @SqlTable(name = ORG, comment = "组织")
    public static class Org {
        @SqlId
        @SqlGenerated
        public Long id;

        @SqlColumn(name = "code", length = 16, nullable = false)
        public String code;
    }

    /**
     * 覆盖常见列类型 / 自增 / 注释 / 索引 / 外键。
     */
    @SqlTable(name = USER, comment = "用户", indexes = {"idx_auto_user_name:user_name"})
    public static class User {
        @SqlId
        @SqlGenerated
        public Long id;

        @SqlColumn(name = "user_name", length = 32, nullable = false, comment = "用户名")
        public String name;

        @SqlColumn(length = 64)
        public String email;

        public Integer age;

        @SqlColumn(precision = 10, scale = 2)
        public BigDecimal amount;

        public Boolean active;

        public java.sql.Date bornOn;

        public Date updatedAt;

        public Org org;
    }

    /**
     * 加 nickname，用于 UPDATE 加列。
     */
    @SqlTable(name = USER, indexes = {"idx_auto_user_name:user_name"})
    public static class UserV2 {
        @SqlId
        @SqlGenerated
        public Long id;

        @SqlColumn(name = "user_name", length = 32, nullable = false, comment = "用户名")
        public String name;

        @SqlColumn(length = 64)
        public String email;

        public Integer age;

        @SqlColumn(precision = 10, scale = 2)
        public BigDecimal amount;

        public Boolean active;

        public java.sql.Date bornOn;

        public Date updatedAt;

        public Org org;

        @SqlColumn(length = 32)
        public String nickname;
    }

    private static final class ColInfo {
        private final String typeName;
        private final int nullable;
        private final String remarks;
        private final String autoIncrement;

        private ColInfo(String typeName, int nullable, String remarks, String autoIncrement) {
            this.typeName = typeName;
            this.nullable = nullable;
            this.remarks = remarks;
            this.autoIncrement = autoIncrement;
        }

        private static ColInfo from(ResultSet rs) throws SQLException {
            String auto = null;
            try {
                auto = rs.getString("IS_AUTOINCREMENT");
            } catch (SQLException ignored) {
                // 老驱动没有该列
            }
            return new ColInfo(rs.getString("TYPE_NAME"), rs.getInt("NULLABLE"),
                    rs.getString("REMARKS"), auto);
        }
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
