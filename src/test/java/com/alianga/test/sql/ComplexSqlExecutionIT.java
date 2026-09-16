package com.alianga.test.sql;

import com.alianga.jkit.sql.SQL;
import com.alianga.jkit.sql.SqlDialect;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * L0 / L4：本机已初始化的复杂 SQL 语料真库执行。
 * 库不可达则 skip。默认只跑 001/022/211；{@code -Dcomplex.sql.ids=all} 跑 300 条。
 *
 * @author 郑明亮
 */
public class ComplexSqlExecutionIT {

    private static final Pattern MARKER = Pattern.compile(
            "^-- \\[(\\d+)\\]\\s*(.+?)\\s*$", Pattern.MULTILINE);
    private static final String[] DEFAULT_IDS = {"001", "022", "211"};
    /** 结果集行内列分隔符，用不可见字符避免与数据里的逗号冲突。 */
    private static final char SEP = '\u0001';
    private static final Path CORPUS = Paths.get(
            "/opt/workspace/zml/jkit/jkit-sql/src/test/resources/sqls/complex-sql");

    private static Connection mysql;
    private static Connection oracle;
    private static Connection sqlserver;
    private static File reportDir;

    /**
     * 连接本机数据源；不可达则对应用例 skip。
     */
    @BeforeClass
    public static void connect() {
        reportDir = new File("target/complex-sql-reports");
        reportDir.mkdirs();
        mysql = tryConnect("jdbc:mysql://127.0.0.1:3308/test_db"
                        + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "root", "mingliang", "com.mysql.cj.jdbc.Driver");
        oracle = tryConnect("jdbc:oracle:thin:@192.168.1.197:2521/M_PDB",
                "ZML", "mingliang", "oracle.jdbc.OracleDriver");
        sqlserver = tryConnect(
                "jdbc:sqlserver://127.0.0.1:1433;databaseName=jkit_ss_test;"
                        + "encrypt=false;trustServerCertificate=true",
                "sa", "Ireport@2025", "com.microsoft.sqlserver.jdbc.SQLServerDriver");
        if (oracle != null) {
            try (Statement st = oracle.createStatement()) {
                st.execute("ALTER SESSION SET NLS_DATE_FORMAT='YYYY-MM-DD'");
            } catch (Exception ignored) {
                // 会话日期格式失败不阻断
            }
        }
    }

    /**
     * 关闭连接。
     */
    @AfterClass
    public static void close() {
        closeQuietly(mysql);
        closeQuietly(oracle);
        closeQuietly(sqlserver);
    }

    /**
     * L0：库能连且 orders 表有数据。
     */
    @Test
    public void l0Probe() throws Exception {
        Assume.assumeTrue("no database reachable", mysql != null || oracle != null || sqlserver != null);
        if (mysql != null) {
            assertTrue("mysql orders empty", probeCount(mysql, "SELECT COUNT(*) FROM orders") > 0);
        }
        if (oracle != null) {
            assertTrue("oracle orders empty", probeCount(oracle, "SELECT COUNT(*) FROM orders") > 0);
        }
        if (sqlserver != null) {
            assertTrue("sqlserver orders empty", probeCount(sqlserver, "SELECT COUNT(*) FROM orders") > 0);
        }
    }

    /**
     * 原文 SQL 在源库可执行。
     */
    @Test
    public void originalSqlExecutes() throws Exception {
        Assume.assumeTrue(mysql != null || oracle != null || sqlserver != null);
        List<String> ids = selectedIds();
        StringBuilder summary = new StringBuilder();
        int fail = 0;
        fail += runOriginal(mysql, SqlDialect.MYSQL, "mysql_complex_300.sql", ids, summary);
        fail += runOriginal(oracle, SqlDialect.ORACLE12, "oracle_complex_300.sql", ids, summary);
        fail += runOriginal(sqlserver, SqlDialect.SQLSERVER, "sqlserver_complex_300.sql", ids, summary);
        write("l4-original.txt", summary.toString());
        System.out.print(summary);
        assertTrue("original exec failures " + fail + "\n" + summary, fail == 0);
    }

    /**
     * 回写对照：{@code parse → toSqlString} 后的 SQL 与原文在同一库、同一批数据上执行，
     * 列数、行数与规范化后的每行数据必须完全一致。
     *
     * <p>这是唯一能抓到「回写改了语义但仍能执行」这类 bug 的层：(a - b) / c 被写成
     * a - b / c 后照样跑得通，只有结果集对不上。</p>
     */
    @Test
    public void rewrittenMatchesOriginal() throws Exception {
        Assume.assumeTrue(mysql != null || oracle != null || sqlserver != null);
        List<String> ids = selectedIds();
        StringBuilder summary = new StringBuilder();
        int fail = 0;
        fail += runRewriteCompare(mysql, SqlDialect.MYSQL, "mysql_complex_300.sql", ids, summary);
        fail += runRewriteCompare(oracle, SqlDialect.ORACLE12, "oracle_complex_300.sql", ids, summary);
        fail += runRewriteCompare(sqlserver, SqlDialect.SQLSERVER, "sqlserver_complex_300.sql", ids, summary);
        write("l4-rewrite-compare.txt", summary.toString());
        System.out.print(summary);
        assertTrue("rewrite mismatch " + fail + "\n" + summary, fail == 0);
    }

    /**
     * MySQL 原文经 convert 后在 Oracle12 / SQL Server 可执行。
     */
    @Test
    public void convertedMysqlExecutes() throws Exception {
        Assume.assumeTrue(oracle != null || sqlserver != null);
        List<String> ids = selectedIds();
        Map<String, String> mysqlSqls = loadSqls("mysql_complex_300.sql");
        StringBuilder summary = new StringBuilder();
        int fail = 0;
        if (oracle != null) {
            fail += runConverted(oracle, SqlDialect.ORACLE12, mysqlSqls, ids, summary);
        }
        if (sqlserver != null) {
            fail += runConverted(sqlserver, SqlDialect.SQLSERVER, mysqlSqls, ids, summary);
        }
        write("l4-converted.txt", summary.toString());
        System.out.print(summary);
        assertTrue("converted exec failures " + fail + "\n" + summary, fail == 0);
    }

    private static int runOriginal(Connection c, SqlDialect dialect, String file,
                                   List<String> ids, StringBuilder summary) throws Exception {
        if (c == null) {
            summary.append("skip ").append(dialect).append('\n');
            return 0;
        }
        Map<String, String> sqls = loadSqls(file);
        int fail = 0;
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            String sql = sqls.get(id);
            Exec r = exec(c, dialect, decorate(dialect, id, sql));
            summary.append("ORIG ").append(dialect).append(' ').append(id)
                    .append(" ok=").append(r.ok)
                    .append(" rows=").append(r.rows)
                    .append(" cols=").append(r.cols)
                    .append(" ms=").append(r.ms);
            if (!r.ok) {
                fail++;
                summary.append(" err=").append(r.error);
            }
            summary.append('\n');
        }
        return fail;
    }

    private static int runRewriteCompare(Connection c, SqlDialect dialect, String file,
                                         List<String> ids, StringBuilder summary) throws Exception {
        if (c == null) {
            summary.append("skip ").append(dialect).append('\n');
            return 0;
        }
        Map<String, String> sqls = loadSqls(file);
        int fail = 0;
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            String sql = sqls.get(id);
            summary.append("RW ").append(dialect).append(' ').append(id);
            Result base = fetch(c, decorate(dialect, id, sql));
            if (!base.ok) {
                // 原文就跑不通，不是回写的锅，跳过对照
                fail++;
                summary.append(" BASE_FAIL err=").append(base.error).append('\n');
                continue;
            }
            String rewritten;
            try {
                rewritten = SQL.toSqlString(SQL.parse(sql, dialect), dialect);
            } catch (RuntimeException e) {
                fail++;
                summary.append(" REWRITE_FAIL err=").append(e.getClass().getSimpleName()).append('\n');
                continue;
            }
            Result after = fetch(c, decorate(dialect, id, rewritten));
            summary.append(" rows=").append(base.rows.size()).append("->").append(after.rows.size())
                    .append(" cols=").append(base.cols).append("->").append(after.cols);
            if (!after.ok) {
                fail++;
                summary.append(" REWRITTEN_FAIL err=").append(after.error).append('\n');
                continue;
            }
            String diff = diffOf(base, after);
            if (diff != null) {
                fail++;
                summary.append(" MISMATCH ").append(diff);
            } else {
                summary.append(" same");
            }
            summary.append('\n');
        }
        return fail;
    }

    /**
     * 取回结果集并规范化：数字去掉尾随零，行内用 SEP 分隔，行间排序以消除不稳定顺序。
     */
    private static Result fetch(Connection c, String sql) {
        Result out = new Result();
        out.rows = new ArrayList<String>();
        long t0 = System.currentTimeMillis();
        try (Statement st = c.createStatement()) {
            st.setMaxRows(50);
            st.setQueryTimeout(30);
            try (ResultSet rs = st.executeQuery(sql)) {
                out.cols = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 1; i <= out.cols; i++) {
                        if (i > 1) {
                            sb.append(SEP);
                        }
                        sb.append(normalize(rs.getObject(i)));
                    }
                    out.rows.add(sb.toString());
                }
                out.ok = true;
            }
        } catch (Exception e) {
            out.ok = false;
            String msg = e.getMessage();
            out.error = msg == null ? e.getClass().getSimpleName()
                    : msg.replace('\n', ' ').replace('\r', ' ');
            if (out.error.length() > 240) {
                out.error = out.error.substring(0, 240);
            }
        }
        out.ms = System.currentTimeMillis() - t0;
        Collections.sort(out.rows);
        return out;
    }

    private static String normalize(Object v) {
        if (v == null) {
            return "\u0000NULL";
        }
        if (v instanceof Number) {
            try {
                return new BigDecimal(v.toString()).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException ignored) {
                return v.toString();
            }
        }
        return v.toString().trim();
    }

    /**
     * 返回 null 表示一致，否则返回首个差异的可读描述。
     */
    private static String diffOf(Result a, Result b) {
        if (a.cols != b.cols) {
            return "cols " + a.cols + " vs " + b.cols;
        }
        if (a.rows.size() != b.rows.size()) {
            return "rows " + a.rows.size() + " vs " + b.rows.size();
        }
        for (int i = 0; i < a.rows.size(); i++) {
            String x = a.rows.get(i);
            String y = b.rows.get(i);
            if (!x.equals(y)) {
                return "row" + i + " [" + clip(x) + "] vs [" + clip(y) + "]";
            }
        }
        return null;
    }

    private static String clip(String s) {
        String t = s.replace(SEP, ',');
        return t.length() > 160 ? t.substring(0, 160) + "..." : t;
    }

    private static int runConverted(Connection c, SqlDialect target, Map<String, String> mysqlSqls,
                                    List<String> ids, StringBuilder summary) {
        int fail = 0;
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            String src = mysqlSqls.get(id);
            String converted = SQL.convert(src, SqlDialect.MYSQL, target);
            Exec r = exec(c, target, decorate(target, id, converted));
            summary.append("CONV MYSQL->").append(target).append(' ').append(id)
                    .append(" ok=").append(r.ok)
                    .append(" rows=").append(r.rows)
                    .append(" cols=").append(r.cols)
                    .append(" ms=").append(r.ms);
            if (!r.ok) {
                fail++;
                summary.append(" err=").append(r.error);
            }
            summary.append('\n');
        }
        return fail;
    }

    private static String decorate(SqlDialect dialect, String id, String sql) {
        if (dialect == SqlDialect.SQLSERVER && ("022".equals(id) || "211".equals(id))) {
            return sql + "\nOPTION (MAXRECURSION 0)";
        }
        return sql;
    }

    private static Exec exec(Connection c, SqlDialect dialect, String sql) {
        Exec out = new Exec();
        long t0 = System.currentTimeMillis();
        try (Statement st = c.createStatement()) {
            st.setMaxRows(50);
            st.setQueryTimeout(30);
            try (ResultSet rs = st.executeQuery(sql)) {
                ResultSetMetaData md = rs.getMetaData();
                out.cols = md.getColumnCount();
                int n = 0;
                while (rs.next()) {
                    n++;
                }
                out.rows = n;
                out.ok = true;
            }
        } catch (Exception e) {
            out.ok = false;
            String msg = e.getMessage();
            out.error = msg == null ? e.getClass().getSimpleName()
                    : msg.replace('\n', ' ').replace('\r', ' ');
            if (out.error.length() > 240) {
                out.error = out.error.substring(0, 240);
            }
        }
        out.ms = System.currentTimeMillis() - t0;
        return out;
    }

    private static int probeCount(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static List<String> selectedIds() {
        String raw = System.getProperty("complex.sql.ids", "001,022,211");
        List<String> ids = new ArrayList<String>();
        if ("all".equalsIgnoreCase(raw.trim())) {
            for (int i = 1; i <= 300; i++) {
                ids.add(padId(i));
            }
            return ids;
        }
        String[] parts = raw.split(",");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (!p.isEmpty()) {
                ids.add(padId(Integer.parseInt(p)));
            }
        }
        if (ids.isEmpty()) {
            for (int i = 0; i < DEFAULT_IDS.length; i++) {
                ids.add(DEFAULT_IDS[i]);
            }
        }
        return ids;
    }

    private static Map<String, String> loadSqls(String file) throws Exception {
        String text = new String(Files.readAllBytes(CORPUS.resolve(file)), StandardCharsets.UTF_8);
        Matcher matcher = MARKER.matcher(text);
        List<int[]> spans = new ArrayList<int[]>();
        List<String> ids = new ArrayList<String>();
        while (matcher.find()) {
            spans.add(new int[] {matcher.start(), matcher.end()});
            ids.add(padId(Integer.parseInt(matcher.group(1))));
        }
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (int i = 0; i < spans.size(); i++) {
            int bodyStart = spans.get(i)[1];
            int bodyEnd = i + 1 < spans.size() ? spans.get(i + 1)[0] : text.length();
            out.put(ids.get(i), stripBody(text.substring(bodyStart, bodyEnd)));
        }
        return out;
    }

    private static String stripBody(String body) {
        String[] raw = body.split("\n", -1);
        List<String> lines = new ArrayList<String>();
        boolean started = false;
        for (int i = 0; i < raw.length; i++) {
            String line = raw[i].replaceAll("[ \\t\\r]+$", "");
            if (!started) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("--")) {
                    continue;
                }
                started = true;
            }
            lines.add(line);
        }
        while (!lines.isEmpty()) {
            String last = lines.get(lines.size() - 1).trim();
            if (last.isEmpty() || "GO".equalsIgnoreCase(last) || last.startsWith("--")) {
                lines.remove(lines.size() - 1);
                continue;
            }
            break;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines.get(i));
        }
        String sql = sb.toString().trim();
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        return sql;
    }

    private static String padId(int n) {
        if (n < 10) {
            return "00" + n;
        }
        if (n < 100) {
            return "0" + n;
        }
        return String.valueOf(n);
    }

    private static Connection tryConnect(String url, String user, String pass, String driver) {
        try {
            Class.forName(driver);
            Connection c = DriverManager.getConnection(url, user, pass);
            c.setAutoCommit(true);
            System.out.println("[ok] " + url);
            return c;
        } catch (Exception e) {
            System.out.println("[skip] " + url + " -> " + e.getMessage());
            return null;
        }
    }

    private static void closeQuietly(Connection c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception ignored) {
            // ignore
        }
    }

    private static void write(String name, String content) throws Exception {
        Files.write(new File(reportDir, name).toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static final class Exec {
        private boolean ok;
        private int rows;
        private int cols;
        private long ms;
        private String error;
    }

    /** 带规范化数据的执行结果，用于回写对照。 */
    private static final class Result {
        private boolean ok;
        private int cols;
        private long ms;
        private String error;
        private List<String> rows;
    }
}
