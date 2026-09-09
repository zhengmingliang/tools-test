package com.alianga.test.sql;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertTrue;

/**
 * jkit-sql vs Druid vs JSqlParser：解析成功率 + 吞吐对比。
 *
 * <p>依赖放在 tools-test，不进 jkit-sql 本身（保持零第三方依赖）。</p>
 */
public class SqlParserCompareTest {

    private static final Object[][] CORPUS = {
            {"mysql", "SELECT 1"},
            {"mysql", "SELECT * FROM t"},
            {"mysql", "SELECT t.* FROM t"},
            {"mysql", "SELECT a AS x, b y FROM t WHERE id = ?"},
            {"mysql", "SELECT COUNT(*), COUNT(DISTINCT id) FROM t"},
            {"mysql", "SELECT * FROM a LEFT JOIN b ON a.id = b.aid WHERE a.age > 18"},
            {"mysql", "SELECT * FROM (SELECT id FROM t) x"},
            {"mysql", "SELECT * FROM t WHERE id IN (SELECT user_id FROM vip)"},
            {"mysql", "SELECT * FROM t WHERE (ta_code, manager_code) IN ((?, ?), (?, ?))"},
            {"mysql", "SELECT CASE WHEN a > 0 THEN 1 ELSE 0 END FROM t"},
            {"mysql", "SELECT * FROM t GROUP BY a WITH ROLLUP HAVING COUNT(*) > 1"},
            {"mysql", "SELECT * FROM t ORDER BY a DESC LIMIT 10 OFFSET 20"},
            {"mysql", "SELECT * FROM t FOR UPDATE"},
            {"mysql", "SELECT * FROM t LOCK IN SHARE MODE"},
            {"mysql", "SELECT 1 UNION ALL SELECT 2"},
            {"mysql", "INSERT INTO t (id, name) VALUES (1, 'a') ON DUPLICATE KEY UPDATE name = VALUES(name)"},
            {"mysql", "INSERT INTO dest (id) SELECT id FROM src"},
            {"mysql", "UPDATE t JOIN s ON t.id = s.id SET t.a = s.a WHERE s.flag = 1"},
            {"mysql", "DELETE t FROM t JOIN s ON t.id = s.id WHERE s.flag = 1"},
            {"mysql", "WITH c AS (SELECT id FROM t) SELECT * FROM c"},
            {"mysql", "SELECT ROW_NUMBER() OVER (PARTITION BY a ORDER BY b) FROM t"},
            {"mysql", "SELECT EXTRACT(YEAR FROM dt), TRIM(BOTH FROM name) FROM t"},
            {"mysql", "SHOW CREATE TABLE abc"},
            {"mysql", "SHOW COLUMNS FROM t"},
            {"mysql", "CREATE TABLE IF NOT EXISTS t (id INT PRIMARY KEY, name VARCHAR(32))"},
            {"mysql", "ALTER TABLE t ADD COLUMN age INT DEFAULT 0"},
            {"mysql", "SELECT * FROM information_schema.CLUSTER_TABLE_SEGMENTS "
                    + "WHERE table_schema = 'eoai' AND table_name = 'fct_agt_savinf'"},
            {"postgres", "SELECT DISTINCT ON (id) * FROM t ORDER BY id, ts DESC"},
            {"postgres", "INSERT INTO t (id) VALUES (1) ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name"},
            {"postgres", "UPDATE t SET n = n + 1 WHERE id = 1 RETURNING n"},
            {"postgres", "SELECT id::int FROM t"},
            {"postgres", "SELECT COUNT(*) FILTER (WHERE active) FROM t"},
            {"oracle", "SELECT * FROM t WHERE ROWNUM <= 10"},
            {"oracle", "SELECT sysdate FROM dual"},
            {"oracle", "SELECT * FROM t START WITH pid IS NULL CONNECT BY PRIOR id = pid"},
            {"sqlserver", "SELECT TOP 10 * FROM t ORDER BY id"},
            {"sqlserver", "SELECT * FROM [dbo].[user] WHERE [id] = 1"},
            {"mysql", "SELECT id, SUM(x) OVER w FROM t WINDOW w AS (PARTITION BY a ORDER BY b)"},
            {"mysql", "SELECT SUM(x) OVER w1, AVG(x) OVER w2 FROM t "
                    + "WINDOW w1 AS (PARTITION BY a), w2 AS (ORDER BY b)"},
            {"postgres", "SELECT * FROM t, LATERAL (SELECT id FROM s WHERE s.tid = t.id) x"},
            {"postgres", "SELECT * FROM t LEFT JOIN LATERAL (SELECT 1 AS n) x ON true"},
            {"sqlserver", "SELECT * FROM a CROSS APPLY (SELECT TOP 1 id FROM b WHERE b.aid = a.id) x"},
            {"sqlserver", "SELECT * FROM a OUTER APPLY (SELECT id FROM b WHERE b.aid = a.id) x"},
            {"mysql", "SELECT SUM(x) OVER w2 FROM t WINDOW w AS (PARTITION BY a), w2 AS (w ORDER BY b)"},
            {"postgres", "SELECT RANK() OVER w2 FROM t WINDOW w AS (ORDER BY a), w2 AS (w)"},
            {"postgres", "SELECT * FROM UNNEST(arr) AS u(x)"},
            {"oracle", "SELECT * FROM TABLE(fn(1, 2)) t"},
            {"postgres", "SELECT * FROM (VALUES (1), (2)) AS v(id)"},
            {"postgres", "SELECT * FROM (VALUES (1, 'a'), (2, 'b')) AS v(id, name)"},
            {"oracle", "INSERT ALL INTO t1 (id) VALUES (id) INTO t2 (id) VALUES (id) SELECT id FROM src"},
            {"postgres", "INSERT INTO t (id) SELECT id FROM s ON CONFLICT (id) DO NOTHING"},
            {"postgres", "UPDATE t SET a = s.a FROM s WHERE t.id = s.id"},
            {"postgres", "DELETE FROM t USING s WHERE t.id = s.id"},
            {"ansi", "MERGE INTO t USING s ON t.id = s.id WHEN MATCHED AND t.flag = 1 THEN UPDATE SET t.a = s.a WHEN NOT MATCHED THEN INSERT (id, a) VALUES (s.id, s.a)"},
            {"sqlserver", "INSERT INTO t (id) OUTPUT INSERTED.id VALUES (1)"},
            {"mysql", "CREATE OR REPLACE VIEW v_user AS SELECT id, name FROM t"},
            {"mysql", "CREATE PROCEDURE sp_add(IN a INT) BEGIN SELECT a; END"},
            {"mysql", "CALL sp_add(1, 'x')"},
            {"mysql", "ANALYZE TABLE t"},
            {"postgres", "COMMENT ON TABLE t IS 'users'"},
            {"sqlserver", "SELECT 1 GO SELECT 2"},
            {"mysql", "/*!40101 SET NAMES utf8 */"},
            {"mysql", "SELECT /*+ INDEX(t idx_id) */ id FROM t"},
            {"mysql", "SELECT id FROM t /*+ INDEX(t idx_name) */ WHERE id = 1"},
    };

    @Test
    public void parseSuccessRateAndThroughput() {
        int jkitOk = 0;
        int druidOk = 0;
        int jsqlOk = 0;
        List<String> jkitFail = new ArrayList<String>();
        List<String> onlyJkit = new ArrayList<String>();
        List<String> jkitMiss = new ArrayList<String>();

        System.out.println("=== SQL 解析成功率（jkit-sql vs Druid 1.2.23 vs JSqlParser 4.9）===");
        System.out.printf("%-8s  %-6s %-6s %-6s  %s%n", "方言", "jkit", "druid", "jsql", "SQL");
        for (Object[] row : CORPUS) {
            String dialect = (String) row[0];
            String sql = (String) row[1];
            boolean jk = parseJkit(sql, dialect);
            boolean dr = parseDruid(sql, dialect);
            boolean js = parseJsql(sql);
            if (jk) {
                jkitOk++;
            } else {
                jkitFail.add(dialect + " | " + sql);
            }
            if (dr) {
                druidOk++;
            }
            if (js) {
                jsqlOk++;
            }
            if (jk && !dr && !js) {
                onlyJkit.add(sql);
            }
            if (!jk && (dr || js)) {
                jkitMiss.add(sql);
            }
            System.out.printf("%-8s  %-6s %-6s %-6s  %s%n",
                    dialect, yn(jk), yn(dr), yn(js), trim(sql, 72));
        }
        int n = CORPUS.length;
        System.out.printf("%n合计 %d 条：jkit %d (%.0f%%)  druid %d (%.0f%%)  jsql %d (%.0f%%)%n",
                n, jkitOk, pct(jkitOk, n), druidOk, pct(druidOk, n), jsqlOk, pct(jsqlOk, n));
        if (!jkitFail.isEmpty()) {
            System.out.println("jkit 失败：");
            for (String s : jkitFail) {
                System.out.println("  - " + s);
            }
        }
        if (!jkitMiss.isEmpty()) {
            System.out.println("别人能解析、jkit 不能：");
            for (String s : jkitMiss) {
                System.out.println("  - " + s);
            }
        }

        bench("simple", "SELECT id, name, age FROM user WHERE id = ?", "mysql");
        bench("join",
                "SELECT a.id, b.name FROM user a LEFT JOIN order_t b ON a.id = b.uid WHERE a.age > 18",
                "mysql");
        bench("window",
                "SELECT id, ROW_NUMBER() OVER (PARTITION BY dept ORDER BY score) FROM emp",
                "mysql");

        assertTrue("jkit 应能解析 corpus 的大多数: " + jkitOk + "/" + n, jkitOk * 100 >= n * 85);
        assertTrue("jkit 失败条数不应明显多于 Druid: " + jkitFail, jkitOk + 3 >= druidOk);
    }


    /**
     * Load {@code sql-corpus.txt} and report jkit/druid/jsql success rates.
     * Soft-assert: only require jkit ≥ 85%. Competitor gaps do not fail the build.
     * Failures written to {@code target/sql-compare-fail.txt}.
     */
    @Test
    public void corpusFileSuccessRates() throws Exception {
        List<Object[]> rows = loadCorpusFile();
        assertTrue("sql-corpus.txt should have entries", !rows.isEmpty());

        int jkitOk = 0;
        int druidOk = 0;
        int jsqlOk = 0;
        List<String> failLines = new ArrayList<String>();

        for (Object[] row : rows) {
            String dialect = (String) row[0];
            String sql = (String) row[1];
            boolean jk = parseJkit(sql, dialect);
            boolean dr = parseDruid(sql, dialect);
            boolean js = parseJsql(sql);
            if (jk) {
                jkitOk++;
            }
            if (dr) {
                druidOk++;
            }
            if (js) {
                jsqlOk++;
            }
            if (!jk || !dr || !js) {
                failLines.add(String.format("%s | jkit=%s druid=%s jsql=%s | %s",
                        dialect, yn(jk), yn(dr), yn(js), sql));
            }
        }

        int n = rows.size();
        System.out.printf("corpus-file %d 条：jkit %d (%.1f%%)  druid %d (%.1f%%)  jsql %d (%.1f%%)%n",
                n, jkitOk, pct(jkitOk, n), druidOk, pct(druidOk, n), jsqlOk, pct(jsqlOk, n));

        Path out = Paths.get("target", "sql-compare-fail.txt");
        Files.createDirectories(out.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            w.write("# parser gaps / failures from sql-corpus.txt\n");
            w.write(String.format("# total=%d jkit=%d(%.1f%%) druid=%d(%.1f%%) jsql=%d(%.1f%%)%n",
                    n, jkitOk, pct(jkitOk, n), druidOk, pct(druidOk, n), jsqlOk, pct(jsqlOk, n)));
            for (String line : failLines) {
                w.write(line);
                w.write('\n');
            }
        }
        System.out.println("wrote " + out.toAbsolutePath() + " (" + failLines.size() + " gap lines)");

        assertTrue("jkit should parse most of file corpus: " + jkitOk + "/" + n,
                jkitOk * 100 >= n * 85);
    }

    private static List<Object[]> loadCorpusFile() throws Exception {
        List<Object[]> rows = new ArrayList<Object[]>();
        InputStream in = SqlParserCompareTest.class.getClassLoader().getResourceAsStream("sql-corpus.txt");
        assertTrue("classpath sql-corpus.txt missing", in != null);
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int bar = line.indexOf('|');
                if (bar <= 0) {
                    rows.add(new Object[]{"mysql", line});
                    continue;
                }
                String dialect = line.substring(0, bar).trim();
                String sql = line.substring(bar + 1).trim();
                if (!sql.isEmpty()) {
                    rows.add(new Object[]{dialect, sql});
                }
            }
        }
        return rows;
    }

    /**
     * Corpus-level table-name set compare (ignore catalog/schema prefix + case).
     * Reports only-jkit / only-druid. Soft assertions: competitor gaps never fail CI;
     * only require that jkit extracted something on a sample of successful parses.
     */
    @Test
    public void corpusFileTableSetDiff() throws Exception {
        List<Object[]> rows = loadCorpusFile();
        assertTrue("sql-corpus.txt should have entries", !rows.isEmpty());

        Set<String> onlyJkit = new TreeSet<String>();
        Set<String> onlyDruid = new TreeSet<String>();
        int compared = 0;
        int jkitEmpty = 0;

        for (Object[] row : rows) {
            String dialect = (String) row[0];
            String sql = (String) row[1];
            Set<String> jk = normalizeTableSet(jkitTables(sql, dialect));
            Set<String> dr = normalizeTableSet(druidTables(sql, toDbType(dialect)));
            if (jk.isEmpty() && dr.isEmpty()) {
                continue;
            }
            compared++;
            if (jk.isEmpty()) {
                jkitEmpty++;
            }
            for (String t : jk) {
                if (!dr.contains(t)) {
                    onlyJkit.add(dialect + " | " + t + " | " + trim(sql, 60));
                }
            }
            for (String t : dr) {
                if (!jk.contains(t)) {
                    onlyDruid.add(dialect + " | " + t + " | " + trim(sql, 60));
                }
            }
        }

        System.out.printf("table-set diff: compared %d rows; only-jkit %d; only-druid %d; jkitEmpty %d%n",
                compared, onlyJkit.size(), onlyDruid.size(), jkitEmpty);
        if (!onlyJkit.isEmpty()) {
            System.out.println("only-jkit (sample up to 20):");
            int n = 0;
            for (String s : onlyJkit) {
                System.out.println("  + " + s);
                if (++n >= 20) {
                    break;
                }
            }
        }
        if (!onlyDruid.isEmpty()) {
            System.out.println("only-druid (sample up to 20):");
            int n = 0;
            for (String s : onlyDruid) {
                System.out.println("  + " + s);
                if (++n >= 20) {
                    break;
                }
            }
        }

        Path out = Paths.get("target", "sql-table-set-diff.txt");
        Files.createDirectories(out.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            w.write("# only-jkit / only-druid table names from sql-corpus.txt\n");
            w.write("# compared=" + compared + " only-jkit=" + onlyJkit.size()
                    + " only-druid=" + onlyDruid.size() + "\n");
            w.write("## only-jkit\n");
            for (String s : onlyJkit) {
                w.write(s);
                w.write('\n');
            }
            w.write("## only-druid\n");
            for (String s : onlyDruid) {
                w.write(s);
                w.write('\n');
            }
        }

        // Soft: do not fail on competitor gaps; only sanity-check we compared something.
        assertTrue("should compare some rows with tables: " + compared, compared > 0);
        // Soft: jkit should not be empty on almost all compared rows
        assertTrue("jkit table extract empty too often: " + jkitEmpty + "/" + compared,
                jkitEmpty * 2 < compared);
    }

    private static List<String> jkitTables(String sql, String dialect) {
        try {
            return com.alianga.jkit.sql.SQL.tables(
                    com.alianga.jkit.sql.SQL.parse(sql, com.alianga.jkit.sql.SqlDialect.fromName(dialect)));
        } catch (Throwable e) {
            return new ArrayList<String>();
        }
    }

    /** Lowercase simple name: strip catalog/schema prefix and []/`/" quotes. */
    private static Set<String> normalizeTableSet(List<String> tables) {
        Set<String> out = new LinkedHashSet<String>();
        if (tables == null) {
            return out;
        }
        for (String raw : tables) {
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            String s = raw.trim();
            s = s.replace("[", "").replace("]", "").replace("`", "").replace("\"", "");
            int dot = s.lastIndexOf('.');
            if (dot >= 0 && dot < s.length() - 1) {
                s = s.substring(dot + 1);
            }
            s = s.toLowerCase(Locale.ROOT);
            if (!s.isEmpty() && !"*".equals(s)) {
                out.add(s);
            }
        }
        return out;
    }

    @Test
    public void tablesAgreeOnSimpleJoin() {
        String sql = "SELECT a.id, b.name FROM user a LEFT JOIN order_t b ON a.id = b.uid";
        List<String> jkit = com.alianga.jkit.sql.SQL.tables(sql);
        List<String> druid = druidTables(sql, DbType.mysql);
        System.out.println("jkit tables  = " + jkit);
        System.out.println("druid tables = " + druid);
        assertTrue("jkit 应包含 user: " + jkit, containsIgnoreCase(jkit, "user"));
        assertTrue("jkit 应包含 order_t: " + jkit, containsIgnoreCase(jkit, "order_t"));
        assertTrue("druid 应包含 user: " + druid, containsIgnoreCase(druid, "user"));
    }

    private static void bench(String name, String sql, String dialect) {
        int warmup = 2000;
        int n = 20000;
        for (int i = 0; i < warmup; i++) {
            parseJkit(sql, dialect);
            parseDruid(sql, dialect);
            parseJsql(sql);
        }
        long jk = timeNs(n, new Runnable() {
            @Override
            public void run() {
                parseJkit(sql, dialect);
            }
        });
        long dr = timeNs(n, new Runnable() {
            @Override
            public void run() {
                parseDruid(sql, dialect);
            }
        });
        long js = timeNs(n, new Runnable() {
            @Override
            public void run() {
                parseJsql(sql);
            }
        });
        System.out.printf("吞吐 %-8s  jkit %6d ns/op  druid %6d ns/op  jsql %6d ns/op  (jkit/druid=%.2f)%n",
                name, jk / n, dr / n, js / n, dr == 0 ? 0 : (double) jk / dr);
    }

    private static long timeNs(int n, Runnable r) {
        long t = System.nanoTime();
        for (int i = 0; i < n; i++) {
            r.run();
        }
        return System.nanoTime() - t;
    }

    private static boolean parseJkit(String sql, String dialect) {
        try {
            com.alianga.jkit.sql.SQL.parse(sql, com.alianga.jkit.sql.SqlDialect.fromName(dialect));
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static boolean parseDruid(String sql, String dialect) {
        try {
            List<?> stmts = SQLUtils.parseStatements(sql, toDbType(dialect));
            return stmts != null && !stmts.isEmpty();
        } catch (Throwable e) {
            return false;
        }
    }

    private static boolean parseJsql(String sql) {
        try {
            return CCJSqlParserUtil.parse(sql) != null;
        } catch (Throwable e) {
            return false;
        }
    }

    private static List<String> druidTables(String sql, DbType dbType) {
        List<String> tables = new ArrayList<String>();
        try {
            List<com.alibaba.druid.sql.ast.SQLStatement> stmts = SQLUtils.parseStatements(sql, dbType);
            com.alibaba.druid.sql.visitor.SchemaStatVisitor v = SQLUtils.createSchemaStatVisitor(dbType);
            stmts.get(0).accept(v);
            for (com.alibaba.druid.stat.TableStat.Name name : v.getTables().keySet()) {
                tables.add(name.getName());
            }
        } catch (Throwable ignored) {
            // 对比测试：解析失败则返回空列表
        }
        return tables;
    }

    private static DbType toDbType(String dialect) {
        if ("postgres".equals(dialect)) {
            return DbType.postgresql;
        }
        if ("oracle".equals(dialect)) {
            return DbType.oracle;
        }
        if ("sqlserver".equals(dialect)) {
            return DbType.sqlserver;
        }
        return DbType.mysql;
    }

    private static boolean containsIgnoreCase(List<String> list, String want) {
        for (String s : list) {
            if (s != null && s.equalsIgnoreCase(want)) {
                return true;
            }
            if (s != null && s.toLowerCase().endsWith("." + want.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static String yn(boolean ok) {
        return ok ? "OK" : "FAIL";
    }

    private static double pct(int ok, int n) {
        return n == 0 ? 0 : ok * 100.0 / n;
    }

    private static String trim(String sql, int max) {
        String one = sql.replace('\n', ' ');
        return one.length() <= max ? one : one.substring(0, max - 1) + "…";
    }
}
