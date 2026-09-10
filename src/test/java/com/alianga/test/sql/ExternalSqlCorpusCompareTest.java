package com.alianga.test.sql;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alianga.jkit.sql.SQL;
import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.SqlParseException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertTrue;

/**
 * 外部语料：jkit-sql vs Druid vs JSqlParser 解析正确率 + 速度回归。
 *
 * <p>竞争对手失败只记入报告，不硬失败构建；jkit 可选 soft-assert 阈值
 * （与 {@link ExternalSqlCorpusTest} 对齐）。</p>
 *
 * <pre>mvn -Dtest=ExternalSqlCorpusCompareTest test</pre>
 */
public class ExternalSqlCorpusCompareTest {

    @BeforeClass
    public static void resetReportFiles() throws Exception {
        Path dir = reportDir();
        Files.deleteIfExists(dir.resolve("compare-summary.md"));
        Files.deleteIfExists(dir.resolve("compare-speed.txt"));
    }

    private static final String RES = "sql-corpora/";
    private static final SqlDialect[] FALLBACK = {
            SqlDialect.MYSQL, SqlDialect.POSTGRES, SqlDialect.ORACLE,
            SqlDialect.SQLSERVER, SqlDialect.ANSI
    };

    @Test
    public void compareComplex100() throws Exception {
        Corpus c = loadComplex100();
        assertTrue("complex100 empty", !c.sqls.isEmpty());
        CompareResult r = compareCorpus(c, /*speedIters*/ 5, /*warmupPasses*/ 1);
        printDetailedTable("complex100", r);
        writeReports(r);
        softAssertJkit("complex100", r, 95.0);
    }

    @Test
    public void compareBird() throws Exception {
        List<String> sqls = ExternalSqlCorpusTest.loadBirdJsonArray(RES + "bird-simple-sql.json");
        Corpus c = Corpus.of("bird", sqls, null);
        // bird ~7k: correctness once; speed = 1 timed pass after warmup
        CompareResult r = compareCorpus(c, /*speedIters*/ 1, /*warmupPasses*/ 1);
        printSummaryRow(r);
        writeReports(r);
        softAssertJkit("bird", r, 99.9);
    }

    @Test
    public void compareSpiderDdl() throws Exception {
        runSpiderLike("spider_ddl",
                ExternalSqlCorpusTest.loadJsonlField(RES + "spider_ddl.jsonl", "sql"),
                100.0, 2);
    }

    @Test
    public void compareSpiderDev() throws Exception {
        runSpiderLike("spider_dev",
                ExternalSqlCorpusTest.loadJsonlField(RES + "spider_dev_pairs.jsonl", "query"),
                100.0, 3);
    }

    @Test
    public void compareSpiderTest() throws Exception {
        runSpiderLike("spider_test",
                ExternalSqlCorpusTest.loadJsonlField(RES + "spider_test_pairs.jsonl", "query"),
                99.5, 2);
    }

    @Test
    public void compareSpiderTrainOthers() throws Exception {
        runSpiderLike("spider_train_others",
                ExternalSqlCorpusTest.loadJsonlField(RES + "spider_train_others_pairs.jsonl", "query"),
                100.0, 2);
    }

    @Test
    public void compareSpiderTrainSpider() throws Exception {
        // ~7k: correctness + 1 timed pass
        runSpiderLike("spider_train_spider",
                ExternalSqlCorpusTest.loadJsonlField(RES + "spider_train_spider_pairs.jsonl", "query"),
                100.0, 1);
    }

    private void runSpiderLike(String name, List<String> sqls, double jkitMinPct, int speedIters)
            throws Exception {
        Corpus c = Corpus.of(name, sqls, null);
        CompareResult r = compareCorpus(c, speedIters, 1);
        printSummaryRow(r);
        writeReports(r);
        softAssertJkit(name, r, jkitMinPct);
    }

    private static Corpus loadComplex100() throws Exception {
        List<ExternalSqlCorpusTest.Item> items =
                ExternalSqlCorpusTest.loadComplex100Markdown(
                        RES + "complex100-主流数据库复杂业务SQL100条.md");
        if (items.isEmpty()) {
            items = ExternalSqlCorpusTest.loadComplex100Jsonl(RES + "complex100.jsonl");
        }
        List<String> sqls = new ArrayList<String>();
        List<String> prefers = new ArrayList<String>();
        int skipped = 0;
        for (ExternalSqlCorpusTest.Item it : items) {
            if (!ExternalSqlCorpusTest.looksLikeSql(it.sql)) {
                skipped++;
                continue;
            }
            sqls.add(it.sql);
            prefers.add(it.prefer);
        }
        System.out.println("complex100 skipped_non_sql=" + skipped);
        return Corpus.of("complex100", sqls, prefers);
    }

    private static CompareResult compareCorpus(Corpus c, int speedIters, int warmupPasses)
            throws Exception {
        CompareResult r = new CompareResult();
        r.name = c.name;
        r.total = c.sqls.size();
        r.speedIters = speedIters;

        Path dir = reportDir();
        Path tsv = dir.resolve("compare-" + c.name + ".tsv");
        BufferedWriter diverge = Files.newBufferedWriter(tsv, StandardCharsets.UTF_8);
        diverge.write("idx\tjkit\tdruid\tjsql\tprefer\tsql\n");

        for (int i = 0; i < c.sqls.size(); i++) {
            String sql = c.sqls.get(i);
            String prefer = c.prefers != null && i < c.prefers.size() ? c.prefers.get(i) : null;
            boolean jk = parseJkitFallback(sql, prefer);
            boolean dr = parseDruidPrefer(sql, prefer);
            boolean js = parseJsql(sql);
            if (jk) {
                r.jkitPass++;
            }
            if (dr) {
                r.druidPass++;
            }
            if (js) {
                r.jsqlPass++;
            }
            if (!(jk && dr && js)) {
                r.diverge++;
                diverge.write(i + "\t" + yn(jk) + "\t" + yn(dr) + "\t" + yn(js) + "\t"
                        + (prefer == null ? "" : prefer) + "\t" + esc(trunc(sql, 400)) + "\n");
            }
        }
        diverge.close();
        r.tsvPath = tsv.toAbsolutePath().toString();

        // speed: warmup then timed loops over full corpus
        for (int w = 0; w < warmupPasses; w++) {
            timePass(c, Parser.JKIT);
            timePass(c, Parser.DRUID);
            timePass(c, Parser.JSQL);
        }
        r.jkitNs = timePasses(c, Parser.JKIT, speedIters);
        r.druidNs = timePasses(c, Parser.DRUID, speedIters);
        r.jsqlNs = timePasses(c, Parser.JSQL, speedIters);

        appendSpeedLine(r);
        return r;
    }

    private static long timePasses(Corpus c, Parser p, int iters) {
        long sum = 0L;
        for (int i = 0; i < iters; i++) {
            sum += timePass(c, p);
        }
        return sum;
    }

    private static long timePass(Corpus c, Parser p) {
        long t0 = System.nanoTime();
        for (int i = 0; i < c.sqls.size(); i++) {
            String sql = c.sqls.get(i);
            String prefer = c.prefers != null && i < c.prefers.size() ? c.prefers.get(i) : null;
            switch (p) {
                case JKIT:
                    parseJkitFallback(sql, prefer);
                    break;
                case DRUID:
                    parseDruidPrefer(sql, prefer);
                    break;
                case JSQL:
                    parseJsql(sql);
                    break;
                default:
                    break;
            }
        }
        return System.nanoTime() - t0;
    }

    private static boolean parseJkitFallback(String sql, String preferName) {
        SqlDialect prefer = toSqlDialect(preferName);
        if (prefer != null) {
            try {
                SQL.parse(sql, prefer);
                return true;
            } catch (Throwable ignored) {
                // fallback
            }
        }
        for (SqlDialect d : FALLBACK) {
            if (prefer != null && d == prefer) {
                continue;
            }
            try {
                SQL.parse(sql, d);
                return true;
            } catch (SqlParseException ignored) {
                // next
            } catch (Throwable ignored) {
                // next
            }
        }
        return false;
    }

    private static boolean parseDruidPrefer(String sql, String preferName) {
        DbType type = toDbType(preferName);
        try {
            List<?> stmts = SQLUtils.parseStatements(sql, type);
            return stmts != null && !stmts.isEmpty();
        } catch (Throwable e) {
            // try mysql fallback if prefer was something else
            if (type != DbType.mysql) {
                try {
                    List<?> stmts = SQLUtils.parseStatements(sql, DbType.mysql);
                    return stmts != null && !stmts.isEmpty();
                } catch (Throwable ignored) {
                    return false;
                }
            }
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

    private static SqlDialect toSqlDialect(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        try {
            return SqlDialect.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return null;
        }
    }

    private static DbType toDbType(String prefer) {
        if (prefer == null) {
            return DbType.mysql;
        }
        String u = prefer.trim().toUpperCase(Locale.ROOT);
        if ("POSTGRES".equals(u) || "POSTGRESQL".equals(u)) {
            return DbType.postgresql;
        }
        if ("ORACLE".equals(u)) {
            return DbType.oracle;
        }
        if ("SQLSERVER".equals(u) || "MSSQL".equals(u)) {
            return DbType.sqlserver;
        }
        if ("ANSI".equals(u)) {
            return DbType.other;
        }
        return DbType.mysql;
    }

    private static void softAssertJkit(String name, CompareResult r, double minPct) {
        double rate = pct(r.jkitPass, r.total);
        System.out.printf(Locale.ROOT,
                "SOFT-ASSERT jkit %s: %.2f%% (min %.1f%%) pass=%d/%d%n",
                name, rate, minPct, r.jkitPass, r.total);
        assertTrue("jkit " + name + " rate " + rate + "% < " + minPct
                        + " pass=" + r.jkitPass + "/" + r.total,
                rate + 1e-9 >= minPct);
    }

    private static void printDetailedTable(String title, CompareResult r) {
        System.out.println();
        System.out.println("======== " + title + " COMPARE (detailed) ========");
        System.out.printf(Locale.ROOT,
                "CORRECTNESS  total=%d%n  jkit  %d (%.2f%%)%n  druid %d (%.2f%%)%n  jsql  %d (%.2f%%)%n  diverge_rows=%d%n",
                r.total,
                r.jkitPass, pct(r.jkitPass, r.total),
                r.druidPass, pct(r.druidPass, r.total),
                r.jsqlPass, pct(r.jsqlPass, r.total),
                r.diverge);
        printSpeed(r);
        System.out.println("tsv: " + r.tsvPath);
        System.out.println("=================================================");
    }

    private static void printSummaryRow(CompareResult r) {
        System.out.printf(Locale.ROOT,
                "COMPARE %-22s total=%5d | jkit %5d (%6.2f%%) | druid %5d (%6.2f%%) | jsql %5d (%6.2f%%) | diverge=%d%n",
                r.name, r.total,
                r.jkitPass, pct(r.jkitPass, r.total),
                r.druidPass, pct(r.druidPass, r.total),
                r.jsqlPass, pct(r.jsqlPass, r.total),
                r.diverge);
        printSpeed(r);
    }

    private static void printSpeed(CompareResult r) {
        long stmts = (long) r.total * Math.max(1, r.speedIters);
        System.out.printf(Locale.ROOT,
                "SPEED    %-22s iters=%d | jkit %8.1f ms (%8.0f ns/stmt, %8.0f stmt/s) | "
                        + "druid %8.1f ms (%8.0f ns/stmt) | jsql %8.1f ms (%8.0f ns/stmt)%n",
                r.name, r.speedIters,
                r.jkitNs / 1_000_000.0, nsPerStmt(r.jkitNs, stmts), stmtsPerSec(r.jkitNs, stmts),
                r.druidNs / 1_000_000.0, nsPerStmt(r.druidNs, stmts),
                r.jsqlNs / 1_000_000.0, nsPerStmt(r.jsqlNs, stmts));
    }

    private static void writeReports(CompareResult r) throws Exception {
        // per-corpus already written as tsv; also append/update summary md + speed txt
        Path dir = reportDir();
        Path summary = dir.resolve("compare-summary.md");
        String line = String.format(Locale.ROOT,
                "| %s | %d | %d (%.2f%%) | %d (%.2f%%) | %d (%.2f%%) | %d |%n",
                r.name, r.total,
                r.jkitPass, pct(r.jkitPass, r.total),
                r.druidPass, pct(r.druidPass, r.total),
                r.jsqlPass, pct(r.jsqlPass, r.total),
                r.diverge);
        if (!Files.exists(summary) || Files.size(summary) == 0) {
            String header = "# SQL corpus compare (jkit vs Druid vs JSqlParser)\n\n"
                    + "| corpus | total | jkit | druid | jsql | diverge_rows |\n"
                    + "|--------|------:|-----:|------:|-----:|-------------:|\n";
            Files.write(summary, header.getBytes(StandardCharsets.UTF_8));
        }
        Files.write(summary, line.getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);

        // ensure speed file has header
        Path speed = dir.resolve("compare-speed.txt");
        if (!Files.exists(speed) || Files.size(speed) == 0) {
            String sh = String.format(Locale.ROOT,
                    "%-22s %6s %5s  %12s %12s %12s  %12s %12s %12s%n",
                    "corpus", "total", "iters",
                    "jkit_ms", "druid_ms", "jsql_ms",
                    "jkit_ns/stmt", "druid_ns/stmt", "jsql_ns/stmt");
            Files.write(speed, sh.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void appendSpeedLine(CompareResult r) throws Exception {
        Path dir = reportDir();
        Path speed = dir.resolve("compare-speed.txt");
        if (!Files.exists(speed) || Files.size(speed) == 0) {
            String sh = String.format(Locale.ROOT,
                    "%-22s %6s %5s  %12s %12s %12s  %12s %12s %12s%n",
                    "corpus", "total", "iters",
                    "jkit_ms", "druid_ms", "jsql_ms",
                    "jkit_ns/stmt", "druid_ns/stmt", "jsql_ns/stmt");
            Files.write(speed, sh.getBytes(StandardCharsets.UTF_8));
        }
        long stmts = (long) r.total * Math.max(1, r.speedIters);
        String line = String.format(Locale.ROOT,
                "%-22s %6d %5d  %12.1f %12.1f %12.1f  %12.0f %12.0f %12.0f%n",
                r.name, r.total, r.speedIters,
                r.jkitNs / 1_000_000.0, r.druidNs / 1_000_000.0, r.jsqlNs / 1_000_000.0,
                nsPerStmt(r.jkitNs, stmts), nsPerStmt(r.druidNs, stmts), nsPerStmt(r.jsqlNs, stmts));
        Files.write(speed, line.getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);
    }

    private static Path reportDir() throws Exception {
        Path dir = Paths.get("target", "sql-corpus-reports");
        Files.createDirectories(dir);
        return dir;
    }

    private static double pct(int ok, int n) {
        return n == 0 ? 0.0 : 100.0 * ok / n;
    }

    private static double nsPerStmt(long totalNs, long stmts) {
        return stmts == 0 ? 0.0 : (double) totalNs / stmts;
    }

    private static double stmtsPerSec(long totalNs, long stmts) {
        if (totalNs <= 0) {
            return 0.0;
        }
        return stmts * 1_000_000_000.0 / totalNs;
    }

    private static String yn(boolean ok) {
        return ok ? "OK" : "FAIL";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static String trunc(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    private enum Parser { JKIT, DRUID, JSQL }

    private static final class Corpus {
        String name;
        List<String> sqls;
        List<String> prefers;

        static Corpus of(String name, List<String> sqls, List<String> prefers) {
            Corpus c = new Corpus();
            c.name = name;
            c.sqls = sqls;
            c.prefers = prefers;
            return c;
        }
    }

    private static final class CompareResult {
        String name;
        int total;
        int jkitPass;
        int druidPass;
        int jsqlPass;
        int diverge;
        int speedIters;
        long jkitNs;
        long druidNs;
        long jsqlNs;
        String tsvPath;
    }
}
