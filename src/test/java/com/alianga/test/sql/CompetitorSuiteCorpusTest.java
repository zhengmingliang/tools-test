package com.alianga.test.sql;

import com.alianga.jkit.json.JSON;
import com.alianga.jkit.sql.SQL;
import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.ast.SqlStatement;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;

/**
 * Runs SQL harvested from the official test suites of Druid and JSqlParser
 * against all three parsers and reports success rates.
 *
 * <p>Corpora (JSONL: {"sql":…, "origin":…}) live in
 * {@code src/test/resources/sql-corpora/competitor-suites/}:</p>
 * <ul>
 *   <li>{@code druid-bvt-inline.jsonl} — SQL string literals harvested from
 *   druid's bvt parser tests ({@code core/src/test/java/.../bvt/sql})</li>
 *   <li>{@code jsqlparser-inline.jsonl} — literals from JSqlParser's test java</li>
 *   <li>{@code jsqlparser-files.jsonl} — .sql/.txt resource files (may hold scripts)</li>
 * </ul>
 *
 * <p>Fairness: every parser gets its full dialect bag — jkit tries
 * MYSQL→POSTGRES→ORACLE→SQLSERVER→ANSI→H2, Druid tries
 * mysql→postgresql→oracle→sqlserver, JSqlParser parses dialect-less.
 * A statement counts as OK when any dialect accepts it.</p>
 *
 * <p>Expect noise: both suites contain negative tests and template SQL that no
 * parser should accept; those show up as all-FAIL rows in the diff report and
 * are excluded from the gap analysis (a jkit gap is jkit=FAIL while a
 * competitor is OK). Reports land in {@code target/sql-corpus-reports/}.</p>
 */
public class CompetitorSuiteCorpusTest {

    private static final SqlDialect[] JKIT_DIALECTS = {
            SqlDialect.MYSQL, SqlDialect.POSTGRES, SqlDialect.ORACLE,
            SqlDialect.SQLSERVER, SqlDialect.ANSI, SqlDialect.H2};
    private static final DbType[] DRUID_TYPES = {
            DbType.mysql, DbType.postgresql, DbType.oracle, DbType.sqlserver};

    /** 单语句单引擎超时（druid 1.2.23 存在 PG ANALYZE 死循环；超时按 FAIL 记并计数）。 */
    private static final long ENGINE_TIMEOUT_MS = 2000;
    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "competitor-suite-parse");
        t.setDaemon(true);
        return t;
    });
    private static int jkitTimeouts;
    private static int druidTimeouts;
    private static int jsqlTimeouts;

    private static <T> T withTimeout(Callable<T> call, T fallback) {
        try {
            return POOL.submit(call).get(ENGINE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            return fallback;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    public void druidBvtInline() throws Exception {
        run("druid-bvt-inline");
    }

    @Test
    public void jsqlParserInline() throws Exception {
        run("jsqlparser-inline");
    }

    @Test
    public void jsqlParserFiles() throws Exception {
        run("jsqlparser-files");
    }

    private void run(String corpus) throws Exception {
        List<String[]> rows = load(corpus);
        assertTrue("empty corpus " + corpus, !rows.isEmpty());

        int jkit = 0;
        int druid = 0;
        int jsql = 0;
        Map<SqlDialect, Integer> jkitByDialect = new EnumMap<SqlDialect, Integer>(SqlDialect.class);
        List<String> diff = new ArrayList<String>();
        List<String> jkitGaps = new ArrayList<String>();

        for (int i = 0; i < rows.size(); i++) {
            String sql = rows.get(i)[0];
            String origin = rows.get(i)[1];
            SqlDialect hit = jkitDialect(sql);
            boolean jk = hit != null;
            boolean dr = druidOk(sql);
            boolean js = jsqlOk(sql);
            if (jk) {
                jkit++;
                Integer c = jkitByDialect.get(hit);
                jkitByDialect.put(hit, c == null ? 1 : c + 1);
            }
            if (dr) {
                druid++;
            }
            if (js) {
                jsql++;
            }
            if (!(jk == dr && dr == js)) {
                diff.add((jk ? "OK" : "FAIL") + "\t" + (dr ? "OK" : "FAIL") + "\t" + (js ? "OK" : "FAIL")
                        + "\t" + origin + "\t" + oneLine(sql));
            }
            if (!jk && (dr || js)) {
                jkitGaps.add((dr ? "druid=OK " : "") + (js ? "jsql=OK " : "")
                        + "\t" + origin + "\t" + oneLine(sql));
            }
        }

        writeReports(corpus, rows.size(), jkit, druid, jsql, jkitByDialect, diff, jkitGaps);
        System.out.printf("[competitor-suite] %s total=%d jkit=%d(%.1f%%) druid=%d(%.1f%%) jsql=%d(%.1f%%)"
                        + " jkitGaps=%d allEqual=%d timeouts(jkit=%d,druid=%d,jsql=%d)%n",
                corpus, rows.size(),
                jkit, pct(jkit, rows.size()), druid, pct(druid, rows.size()), jsql, pct(jsql, rows.size()),
                jkitGaps.size(), rows.size() - diff.size(), jkitTimeouts, druidTimeouts, jsqlTimeouts);
        System.out.println("[competitor-suite] " + corpus + " jkitByDialect=" + jkitByDialect);
    }

    private static SqlDialect jkitDialect(String sql) {
        for (SqlDialect d : JKIT_DIALECTS) {
            SqlDialect hit;
            try {
                hit = withTimeout(() -> {
                    List<SqlStatement> l = SQL.parseAll(sql, d);
                    return l.isEmpty() ? null : d;
                }, null);
            } catch (IllegalStateException e) {
                continue; // ordinary parse failure → next dialect
            }
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static boolean druidOk(String sql) {
        for (DbType t : DRUID_TYPES) {
            Boolean ok;
            try {
                ok = withTimeout(() -> !SQLUtils.parseStatements(sql, t).isEmpty(), null);
            } catch (IllegalStateException e) {
                continue; // ordinary parse failure → next db type
            }
            if (ok == null) {
                druidTimeouts++; // genuine hang (e.g. druid 1.2.23 PG ANALYZE loop)
                return false;
            }
            if (ok) {
                return true;
            }
        }
        return false;
    }

    private static boolean jsqlOk(String sql) {
        Boolean ok;
        try {
            ok = withTimeout(() -> {
                try {
                    CCJSqlParserUtil.parseStatements(sql);
                    return true;
                } catch (StackOverflowError | Exception e) {
                    return false;
                }
            }, null);
        } catch (IllegalStateException e) {
            return false;
        }
        if (ok == null) {
            jsqlTimeouts++;
            return false;
        }
        return ok;
    }

    private static List<String[]> load(String corpus) throws Exception {
        String name = "sql-corpora/competitor-suites/" + corpus + ".jsonl";
        InputStream in = CompetitorSuiteCorpusTest.class.getClassLoader().getResourceAsStream(name);
        assertTrue("classpath " + name + " missing", in != null);
        List<String[]> rows = new ArrayList<String[]>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) JSON.parse(line);
                Object sql = m.get("sql");
                if (sql == null || sql.toString().trim().isEmpty()) {
                    continue;
                }
                Object origin = m.get("origin");
                rows.add(new String[]{sql.toString(), origin == null ? "?" : origin.toString()});
            }
        }
        return rows;
    }

    private static void writeReports(String corpus, int total, int jkit, int druid, int jsql,
                                     Map<SqlDialect, Integer> jkitByDialect,
                                     List<String> diff, List<String> jkitGaps) throws Exception {
        File dir = new File("target/sql-corpus-reports");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("cannot create " + dir);
        }
        try (FileWriter w = new FileWriter(new File(dir, "competitor-" + corpus + "-summary.txt"), false)) {
            w.write("corpus=" + corpus + " total=" + total + "\n");
            w.write(String.format("jkit=%d (%.1f%%)%n", jkit, pct(jkit, total)));
            w.write(String.format("druid=%d (%.1f%%)%n", druid, pct(druid, total)));
            w.write(String.format("jsql=%d (%.1f%%)%n", jsql, pct(jsql, total)));
            w.write("jkitByDialect=" + jkitByDialect + "\n");
            w.write("timeouts jkit=" + jkitTimeouts + " druid=" + druidTimeouts + " jsql=" + jsqlTimeouts + "\n");
            w.write("diffRows=" + diff.size() + " jkitGaps=" + jkitGaps.size() + "\n");
        }
        try (FileWriter w = new FileWriter(new File(dir, "competitor-" + corpus + "-diff.tsv"), false)) {
            w.write("jkit\tdruid\tjsql\torigin\tsql\n");
            for (String s : diff) {
                w.write(s + "\n");
            }
        }
        try (FileWriter w = new FileWriter(new File(dir, "competitor-" + corpus + "-jkit-gaps.tsv"), false)) {
            w.write("# jkit=FAIL while a competitor parses it (actionable gaps)\n");
            for (String s : jkitGaps) {
                w.write(s + "\n");
            }
        }
    }

    private static String oneLine(String sql) {
        String s = sql.replaceAll("\\s+", " ").trim();
        return s.length() <= 220 ? s : s.substring(0, 220) + "…";
    }

    private static double pct(int ok, int total) {
        return total == 0 ? 0 : ok * 100.0 / total;
    }
}
