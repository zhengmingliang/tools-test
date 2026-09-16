package com.alianga.test.sql;

import com.alianga.jkit.sql.SQL;
import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.SqlParseException;
import com.alianga.jkit.sql.ast.SqlStatement;
import com.alianga.jkit.sql.ast.SqlStatementType;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * 开发助手2 切片：MySQL 001–150 的 L4 本库原生执行（MYSQL 语料 + 本机 MySQL）。
 * 转换后再执行归 L3，本类不做跨方言。
 *
 * <p>报告写到 {@code ../jkit/jkit-sql/target/complex-sql-reports/}。</p>
 *
 * @author 开发助手2
 */
public class ComplexSqlMysqlNativeExecuteTest {
    private static final int FROM_ID = 1;
    private static final int TO_ID = 150;
    private static final int QUERY_TIMEOUT_SEC = 60;
    private static final Pattern MARKER = Pattern.compile(
            "^-- \\[(\\d+)\\]\\s*(.+?)\\s*$", Pattern.MULTILINE);

    private static Connection conn;
    private static File reportDir;
    private static List<Case> slice = Collections.emptyList();

    @BeforeClass
    public static void setUp() throws Exception {
        reportDir = resolveReportDir();
        if (!reportDir.exists() && !reportDir.mkdirs()) {
            throw new IllegalStateException("cannot create " + reportDir.getAbsolutePath());
        }
        File corpus = resolveCorpusFile();
        Assume.assumeTrue("mysql corpus missing: " + corpus.getAbsolutePath(), corpus.isFile());
        slice = loadSlice(corpus, FROM_ID, TO_ID);
        Assume.assumeTrue("expected 150 mysql cases, got " + slice.size(), slice.size() == 150);

        conn = tryConnectMysql();
        Assume.assumeNotNull("mysql unreachable at 127.0.0.1:3308/test_db", conn);
    }

    @AfterClass
    public static void tearDown() {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException ignored) {
            }
        }
    }

    @Test
    public void mysql001To150NativeExecute() throws Exception {
        Assume.assumeNotNull(conn);
        Assume.assumeTrue(slice.size() == 150);

        int parseFail = 0;
        int execOk = 0;
        int execEmpty = 0;
        int execFail = 0;
        int timeout = 0;
        List<String> failClasses = new ArrayList<String>();
        StringBuilder fails = new StringBuilder();
        fails.append("id\tphase\terrorClass\tms\tmessage\n");
        StringBuilder rows = new StringBuilder();
        rows.append("id\tphase\tstatus\trowCount\tms\terrorClass\tmessage\n");

        for (int i = 0; i < slice.size(); i++) {
            Case item = slice.get(i);
            long t0 = System.currentTimeMillis();
            try {
                SqlStatement stmt = SQL.parse(item.sql, SqlDialect.MYSQL);
                if (stmt == null || stmt.type() != SqlStatementType.SELECT) {
                    parseFail++;
                    String msg = stmt == null ? "null" : "type=" + stmt.type();
                    failClasses.add("PARSE_NOT_SELECT");
                    fails.append(item.id).append("\tPARSE\tPARSE_NOT_SELECT\t0\t")
                            .append(tsv(msg)).append('\n');
                    rows.append(item.id).append("\tPARSE\tFAIL\t\t0\tPARSE_NOT_SELECT\t")
                            .append(tsv(msg)).append('\n');
                    continue;
                }
            } catch (SqlParseException e) {
                parseFail++;
                String cls = parseErrorClass(e.getMessage());
                failClasses.add(cls);
                fails.append(item.id).append("\tPARSE\t").append(cls).append("\t0\t")
                        .append(tsv(trunc(e.getMessage(), 300))).append('\n');
                rows.append(item.id).append("\tPARSE\tFAIL\t\t0\t").append(cls).append('\t')
                        .append(tsv(trunc(e.getMessage(), 300))).append('\n');
                continue;
            } catch (RuntimeException e) {
                parseFail++;
                failClasses.add("PARSE_RUNTIME");
                String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                fails.append(item.id).append("\tPARSE\tPARSE_RUNTIME\t0\t")
                        .append(tsv(trunc(msg, 300))).append('\n');
                rows.append(item.id).append("\tPARSE\tFAIL\t\t0\tPARSE_RUNTIME\t")
                        .append(tsv(trunc(msg, 300))).append('\n');
                continue;
            }

            try (Statement st = conn.createStatement()) {
                st.setQueryTimeout(QUERY_TIMEOUT_SEC);
                try (ResultSet rs = st.executeQuery(item.sql)) {
                    int n = 0;
                    while (rs.next()) {
                        n++;
                        if (n >= 10000) {
                            break;
                        }
                    }
                    long ms = System.currentTimeMillis() - t0;
                    if (n == 0) {
                        execEmpty++;
                        rows.append(item.id).append("\tEXEC\tEMPTY\t0\t").append(ms)
                                .append("\t\t\n");
                    } else {
                        execOk++;
                        rows.append(item.id).append("\tEXEC\tOK\t").append(n).append('\t')
                                .append(ms).append("\t\t\n");
                    }
                }
            } catch (SQLException e) {
                long ms = System.currentTimeMillis() - t0;
                String cls = sqlErrorClass(e);
                if ("EXEC_TIMEOUT".equals(cls)) {
                    timeout++;
                } else {
                    execFail++;
                }
                failClasses.add(cls);
                fails.append(item.id).append("\tEXEC\t").append(cls).append('\t')
                        .append(ms).append('\t')
                        .append(tsv(trunc(e.getMessage(), 300))).append('\n');
                rows.append(item.id).append("\tEXEC\tFAIL\t\t").append(ms).append('\t')
                        .append(cls).append('\t')
                        .append(tsv(trunc(e.getMessage(), 300))).append('\n');
            }
        }

        int total = slice.size();
        int execTried = total - parseFail;
        int execPass = execOk + execEmpty;
        StringBuilder summary = new StringBuilder();
        summary.append("L4 MySQL native slice ").append(pad(FROM_ID)).append('-')
                .append(pad(TO_ID)).append('\n')
                .append("total=").append(total)
                .append(" parseFail=").append(parseFail)
                .append(" execOk=").append(execOk)
                .append(" execEmpty=").append(execEmpty)
                .append(" execFail=").append(execFail)
                .append(" timeout=").append(timeout)
                .append(" execPassRate=")
                .append(String.format(Locale.ROOT, "%.2f",
                        execTried == 0 ? 0.0 : 100.0 * execPass / execTried))
                .append("%\n");
        summary.append("errorClass counts:\n");
        Map<String, Integer> counts = countClasses(failClasses);
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            summary.append("  ").append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }

        write("mysql-l4-001-150-rows.tsv", rows.toString());
        write("mysql-l4-001-150-fails.tsv", fails.toString());
        write("mysql-l4-001-150-summary.txt", summary.toString());
        System.out.print(summary);

        // 本库原生执行：允许空结果；失败先出报告，不硬卡 100%（语料/库差异用报告驱动修）
        double rate = execTried == 0 ? 0.0 : 100.0 * execPass / execTried;
        double minPass = Double.parseDouble(System.getProperty("complex.sql.mysql.l4.minPass", "80"));
        assertTrue("MySQL L4 execPassRate " + rate + "% < " + minPass
                        + "%; see " + reportDir.getAbsolutePath(),
                rate + 1e-9 >= minPass);
    }

    private static Connection tryConnectMysql() {
        String url = "jdbc:mysql://127.0.0.1:3308/test_db"
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        String user = "root";
        String password = "mingliang";
        String[] drivers = new String[]{
                "com.mysql.cj.jdbc.Driver",
                "com.mysql.jdbc.Driver",
        };
        Exception last = null;
        for (int i = 0; i < drivers.length; i++) {
            try {
                Class.forName(drivers[i]);
                Connection c = DriverManager.getConnection(url, user, password);
                System.out.println("mysql connected via " + url + " driver=" + drivers[i]);
                return c;
            } catch (Exception e) {
                last = e;
                System.out.println("mysql try fail driver=" + drivers[i] + " : " + e.getMessage());
            }
        }
        if (last != null) {
            System.out.println("mysql unreachable: " + last.getMessage());
        }
        return null;
    }

    private static File resolveCorpusFile() {
        String override = System.getProperty("complex.sql.mysql.file");
        if (override != null && !override.isEmpty()) {
            return new File(override);
        }
        File[] candidates = new File[]{
                new File("../jkit/jkit-sql/src/test/resources/sqls/complex-sql/mysql_complex_300.sql"),
                new File("/opt/workspace/zml/jkit/jkit-sql/src/test/resources/sqls/complex-sql/mysql_complex_300.sql"),
        };
        for (int i = 0; i < candidates.length; i++) {
            if (candidates[i].isFile()) {
                return candidates[i];
            }
        }
        return candidates[0];
    }

    private static File resolveReportDir() {
        String override = System.getProperty("complex.sql.report.dir");
        if (override != null && !override.isEmpty()) {
            return new File(override);
        }
        File[] candidates = new File[]{
                new File("../jkit/jkit-sql/target/complex-sql-reports"),
                new File("/opt/workspace/zml/jkit/jkit-sql/target/complex-sql-reports"),
                new File("target/complex-sql-reports"),
        };
        for (int i = 0; i < candidates.length; i++) {
            File parent = candidates[i].getParentFile();
            if (parent != null && parent.isDirectory()) {
                return candidates[i];
            }
        }
        return candidates[0];
    }

    private static List<Case> loadSlice(File file, int fromId, int toId) throws Exception {
        String text = readFile(file);
        Matcher matcher = MARKER.matcher(text);
        List<int[]> spans = new ArrayList<int[]>(300);
        List<String> ids = new ArrayList<String>(300);
        while (matcher.find()) {
            spans.add(new int[]{matcher.start(), matcher.end()});
            ids.add(pad(Integer.parseInt(matcher.group(1))));
        }
        List<Case> out = new ArrayList<Case>(toId - fromId + 1);
        for (int i = 0; i < spans.size(); i++) {
            int idNum = Integer.parseInt(ids.get(i));
            if (idNum < fromId || idNum > toId) {
                continue;
            }
            int bodyStart = spans.get(i)[1];
            int bodyEnd = i + 1 < spans.size() ? spans.get(i + 1)[0] : text.length();
            String sql = stripBody(text.substring(bodyStart, bodyEnd));
            out.add(new Case(ids.get(i), sql));
        }
        return out;
    }

    private static String stripBody(String body) {
        String[] rawLines = body.split("\n", -1);
        List<String> lines = new ArrayList<String>(rawLines.length);
        boolean started = false;
        for (int i = 0; i < rawLines.length; i++) {
            String line = rawLines[i].replaceAll("[ \\t\\r]+$", "");
            if (!started) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("--")) {
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

    private static String readFile(File file) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8));
        try {
            StringBuilder sb = new StringBuilder((int) Math.min(file.length(), Integer.MAX_VALUE));
            char[] buf = new char[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        } finally {
            in.close();
        }
    }

    private static void write(String name, String content) throws Exception {
        File file = new File(reportDir, name);
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8));
        try {
            writer.write(content == null ? "" : content);
        } finally {
            writer.close();
        }
    }

    private static Map<String, Integer> countClasses(List<String> classes) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < classes.size(); i++) {
            String key = classes.get(i);
            if (key == null || key.isEmpty()) {
                key = "UNKNOWN";
            }
            Integer n = counts.get(key);
            counts.put(key, n == null ? 1 : n + 1);
        }
        List<Map.Entry<String, Integer>> entries =
                new ArrayList<Map.Entry<String, Integer>>(counts.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                int byCount = b.getValue().compareTo(a.getValue());
                if (byCount != 0) {
                    return byCount;
                }
                return a.getKey().compareTo(b.getKey());
            }
        });
        Map<String, Integer> ordered = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < entries.size(); i++) {
            ordered.put(entries.get(i).getKey(), entries.get(i).getValue());
        }
        return ordered;
    }

    private static String parseErrorClass(String message) {
        if (message == null || message.isEmpty()) {
            return "UNKNOWN";
        }
        String m = message.toUpperCase(Locale.ROOT);
        if (m.contains("RECURSIVE") || m.contains("WITH ITEM")) {
            return "PARSE_RECURSIVE_CTE";
        }
        if (m.contains("FETCH") || m.contains("OFFSET") || m.contains("LIMIT") || m.contains("ROWNUM")) {
            return "PARSE_PAGINATION";
        }
        return "PARSE_OTHER";
    }

    private static String sqlErrorClass(SQLException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toUpperCase(Locale.ROOT);
        if (msg.contains("TIMEOUT") || msg.contains("CANCELLED") || msg.contains("CANCELED")
                || msg.contains("QUERY EXECUTION WAS INTERRUPTED")) {
            return "EXEC_TIMEOUT";
        }
        if (msg.contains("TABLE") && (msg.contains("DOESN'T EXIST") || msg.contains("DOES NOT EXIST"))) {
            return "EXEC_MISSING_OBJECT";
        }
        if (msg.contains("UNKNOWN TABLE") || msg.contains("UNKNOWN DATABASE")) {
            return "EXEC_MISSING_OBJECT";
        }
        if (msg.contains("UNKNOWN COLUMN")) {
            return "EXEC_UNKNOWN_COLUMN";
        }
        if (msg.contains("SYNTAX ERROR") || msg.contains("YOU HAVE AN ERROR IN YOUR SQL SYNTAX")) {
            return "EXEC_SYNTAX";
        }
        if (msg.contains("ACCESS DENIED")) {
            return "EXEC_ACCESS_DENIED";
        }
        if (msg.contains("INCORRECT") && msg.contains("DATATYPE")) {
            return "EXEC_DATATYPE";
        }
        if (msg.contains("TRUNCATED") || msg.contains("OUT OF RANGE")) {
            return "EXEC_DATATYPE";
        }
        if (msg.contains("GROUP BY") || msg.contains("ONLY_FULL_GROUP_BY")) {
            return "EXEC_GROUP_BY";
        }
        if (msg.contains("SUBQUERY RETURNS MORE THAN") || msg.contains("CARDINALITY")) {
            return "EXEC_SINGLE_ROW";
        }
        return "EXEC_OTHER";
    }

    private static String tsv(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    private static String trunc(String value, int max) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    private static String pad(int n) {
        if (n < 10) {
            return "00" + n;
        }
        if (n < 100) {
            return "0" + n;
        }
        return String.valueOf(n);
    }

    private static final class Case {
        final String id;
        final String sql;

        Case(String id, String sql) {
            this.id = id;
            this.sql = sql;
        }
    }
}
