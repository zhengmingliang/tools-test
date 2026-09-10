package com.alianga.test.sql;

import com.alianga.jkit.sql.SQL;
import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.ast.SqlStatement;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.assertTrue;

/**
 * Batch round-trip fidelity over {@code sql-corpus.txt} (379 entries): for every
 * corpus line, {@code parse -> format} must keep the original words after
 * normalization. This catches semantic damage that "can parse" assertions miss
 * (e.g. {@code NATURAL LEFT JOIN} formatted as {@code NATURAL JOIN}).
 *
 * <p>Mirrors jkit-sql's {@code SqlRoundTripFidelityTest} normalization rules and
 * adds two corpus-specific equivalences:</p>
 * <ul>
 *   <li>MySQL executable comments {@code /*!40101 … *&#47;} are unwrapped before
 *   comment stripping — the parser expands them into the inner SQL, so the inner
 *   text is the semantic content.</li>
 *   <li>Trailing semicolons are dropped (empty statement marker).</li>
 * </ul>
 *
 * <p>Lines whose first statement does not cover the whole line (multi-statement
 * batches such as {@code … GO …}) are retried via {@code parseAll} with the
 * formatted statements re-joined by {@code "; "}.</p>
 *
 * <p>Entries in {@link #KNOWN} are justified verbatim mismatches with a reason;
 * the test fails when the list goes stale (entry starts passing) so it stays
 * minimal. Details are written to {@code target/sql-fidelity-report.txt}.</p>
 */
public class SqlRoundTripFidelityCorpusTest {

    /**
     * Justified verbatim mismatches: key = {@code dialect + " | " + sql}, value = reason.
     * Keep empty unless a real, defensible case shows up.
     */
    private static final Map<String, String> KNOWN = new HashMap<String, String>();

    static {
        // 单引号字符串字面量别名（'-- a' / '# a'）：解析按标识符别名吸收，
        // 回写统一为加引号的标识符形式（AS "-- a"），语义等价但词法类别不同
        String aliasReason = "string-literal alias rewritten as quoted identifier alias (AS \"-- a\"), semantically equal";
        KNOWN.put("oracle | select 1 '-- a' from dual;", aliasReason);
        KNOWN.put("oracle | select 1 '-- a' from dual", aliasReason);
        KNOWN.put("oracle | select 1 '# a' from dual;", aliasReason);
        KNOWN.put("oracle | select 1 '# a' from dual", aliasReason);
        // 单语句函数体 RETURN 1 统一回写为 BEGIN…END 过程体包装，语义等价
        KNOWN.put("mysql | CREATE FUNCTION fn_one() RETURNS INT RETURN 1",
                "single-statement function body rewritten as BEGIN…END wrapper, semantically equal");
    }

    @Test
    public void corpusRoundTripFidelity() throws Exception {
        List<Object[]> rows = loadCorpus();
        assertTrue("sql-corpus.txt should have entries", !rows.isEmpty());

        List<String> parseFailures = new ArrayList<String>();
        List<String> mismatches = new ArrayList<String>();
        List<String> staleKnown = new ArrayList<String>();
        int pass = 0;
        int viaParseAll = 0;

        for (Object[] row : rows) {
            String dialect = (String) row[0];
            String sql = (String) row[1];
            String key = dialect + " | " + sql;
            String detail = null;
            try {
                SqlDialect d = SqlDialect.fromName(dialect);
                String formatted = SQL.toSqlString(SQL.parse(sql, d));
                if (normalize(sql, dialect).equals(normalize(formatted, dialect))) {
                    pass++;
                } else {
                    String joined = formatAll(sql, d);
                    if (joined != null && normalize(sql, dialect).equals(normalize(joined, dialect))) {
                        pass++;
                        viaParseAll++;
                    } else {
                        detail = "formatted=" + formatted + (joined == null ? "" : " | joined=" + joined);
                    }
                }
            } catch (RuntimeException ex) {
                String reason = KNOWN.get(key);
                if (reason == null) {
                    parseFailures.add(key + "  [" + ex.getClass().getSimpleName() + "] " + ex.getMessage());
                }
                continue;
            }
            if (detail == null) {
                if (KNOWN.containsKey(key)) {
                    staleKnown.add(key + "  (was: " + KNOWN.get(key) + ")");
                }
            } else if (!KNOWN.containsKey(key)) {
                mismatches.add(key + "\n    " + detail);
            }
        }

        writeReport(rows.size(), pass, viaParseAll, parseFailures, mismatches, staleKnown);

        assertTrue("jkit parse failures (see target/sql-fidelity-report.txt):\n"
                + join(parseFailures), parseFailures.isEmpty());
        assertTrue("unexpected fidelity mismatches (see target/sql-fidelity-report.txt):\n"
                + join(mismatches), mismatches.isEmpty());
        assertTrue("stale KNOWN entries, remove them (see target/sql-fidelity-report.txt):\n"
                + join(staleKnown), staleKnown.isEmpty());
    }

    /**
     * Formats every statement and re-joins with {@code "; "}; {@code null} when the
     * multi-statement path also fails to parse.
     */
    private static String formatAll(String sql, SqlDialect dialect) {
        try {
            List<SqlStatement> all = SQL.parseAll(sql, dialect, true);
            if (all.size() <= 1) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (SqlStatement s : all) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(SQL.toSqlString(s));
            }
            return sb.toString();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String normalize(String s, String dialect) {
        String r = s.replaceAll("(?s)/\\*!\\d*\\s*(.*?)\\*/", "$1");
        r = r.replaceAll("--[^\\n]*", " ").replaceAll("/\\*[\\s\\S]*?\\*/", " ");
        // 方言相关的等价：MySQL `||` 即 OR；SQL Server GO 即批分隔符
        if ("mysql".equalsIgnoreCase(dialect)) {
            r = r.replaceAll("\\|\\|", " OR ");
        }
        if ("sqlserver".equalsIgnoreCase(dialect)) {
            r = r.replaceAll("(?i)\\bGO\\b", ";");
        }
        // 标识符引号风格（`、"、[]）属于排版差异，不改变语义
        r = r.replace("`", "").replace("\"", "").replace("[", "").replace("]", "");
        r = r.replaceAll("(?i)\\s+AS\\s+", " ").replaceAll("(?i)\\bAS\\b", " ");
        r = r.replaceAll("(?i)\\bINNER\\b", " ").replaceAll("(?i)\\bOUTER\\b", " ");
        r = r.replaceAll("(?i)\\bASC\\b", " ");
        r = r.replaceAll("(?i)\\bTRUNCATE\\s+TABLE\\b", "TRUNCATE");
        // 语义等价的写法归一
        r = r.replaceAll("(?i)^\\s*DESC\\b", "DESCRIBE");
        r = r.replaceAll("(?i)\\bINSERT\\s+INTO\\s+TABLE\\b", "INSERT INTO");
        r = r.replaceAll("(?i)\\bDELETE\\s+(?!FROM\\b)", "DELETE FROM ");
        r = r.replaceAll("(?i)\\bFETCH\\s+NEXT\\b", "FETCH FIRST");
        r = r.replaceAll("(?i)\\bPRIOR\\s*\\(\\s*([^()]+?)\\s*\\)", "PRIOR $1");
        r = r.replaceAll("(?i)\\bTOP\\s*\\(\\s*(\\d+)\\s*\\)", "TOP $1");
        r = r.replaceAll("(?i)\\bON\\s*\\(\\s*([^()]+?)\\s*\\)", "ON $1");
        r = r.replaceAll("(?i)SUBSTRING\\s*\\(\\s*([^(),]+?)\\s+FROM\\s+([^(),]+?)\\s+FOR\\s+([^(),]+?)\\s*\\)",
                "SUBSTRING($1,$2,$3)");
        r = r.trim().replaceAll("^;+", "").replaceAll(";+$", "");
        r = r.replaceAll("\\s+", "");
        return r.toUpperCase(Locale.ROOT);
    }

    private static List<Object[]> loadCorpus() throws Exception {
        List<Object[]> rows = new ArrayList<Object[]>();
        InputStream in = SqlRoundTripFidelityCorpusTest.class.getClassLoader()
                .getResourceAsStream("sql-corpus.txt");
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

    private static void writeReport(int total, int pass, int viaParseAll,
                                    List<String> parseFailures, List<String> mismatches,
                                    List<String> staleKnown) throws Exception {
        File f = new File("target/sql-fidelity-report.txt");
        try (FileWriter w = new FileWriter(f, false)) {
            w.write("# round-trip fidelity report for sql-corpus.txt\n");
            w.write("# total=" + total + " pass=" + pass + " (viaParseAll=" + viaParseAll + ")"
                    + " parseFailures=" + parseFailures.size()
                    + " mismatches=" + mismatches.size()
                    + " staleKnown=" + staleKnown.size() + "\n");
            writeSection(w, "parse failures", parseFailures);
            writeSection(w, "mismatches", mismatches);
            writeSection(w, "stale known", staleKnown);
        }
    }

    private static void writeSection(FileWriter w, String title, List<String> items) throws Exception {
        if (items.isEmpty()) {
            return;
        }
        w.write("\n## " + title + " (" + items.size() + ")\n");
        for (String s : items) {
            w.write(s + "\n");
        }
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("  ").append(s);
        }
        return sb.toString();
    }
}
