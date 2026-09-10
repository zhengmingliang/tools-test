package com.alianga.test.sql;

import com.alianga.jkit.sql.SQL;
import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.SqlParseException;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 外部批量 SQL 语料：Bird / Spider / complex100。
 * 资源见 {@code src/test/resources/sql-corpora/}；失败写 {@code target/sql-corpus-reports/}。
 */
public class ExternalSqlCorpusTest {

    private static final String RES = "sql-corpora/";
    private static final SqlDialect[] FALLBACK = {
            SqlDialect.MYSQL, SqlDialect.POSTGRES, SqlDialect.ORACLE,
            SqlDialect.SQLSERVER, SqlDialect.ANSI
    };

    @Test
    public void birdSimpleSql() throws Exception {
        List<String> sqls = loadBirdJsonArray(RES + "bird-simple-sql.json");
        Rate r = auditPreferred("bird", sqls, null);
        assertTrue("bird rate " + r.rate + "% pass=" + r.pass + "/" + r.total, r.rate >= 99.9);
    }

    @Test
    public void spiderDdl() throws Exception {
        Rate r = auditPreferred("spider_ddl", loadJsonlField(RES + "spider_ddl.jsonl", "sql"), null);
        assertTrue("spider_ddl must be 100%: " + r.pass + "/" + r.total, r.fail == 0);
    }

    @Test
    public void spiderDev() throws Exception {
        Rate r = auditPreferred("spider_dev", loadJsonlField(RES + "spider_dev_pairs.jsonl", "query"), null);
        assertTrue("spider_dev must be 100%: " + r.pass + "/" + r.total, r.fail == 0);
    }

    @Test
    public void spiderTrainOthers() throws Exception {
        Rate r = auditPreferred("spider_train_others",
                loadJsonlField(RES + "spider_train_others_pairs.jsonl", "query"), null);
        assertTrue("spider_train_others must be 100%: " + r.pass + "/" + r.total, r.fail == 0);
    }

    @Test
    public void spiderTrainSpider() throws Exception {
        Rate r = auditPreferred("spider_train_spider",
                loadJsonlField(RES + "spider_train_spider_pairs.jsonl", "query"), null);
        assertTrue("spider_train_spider must be 100%: " + r.pass + "/" + r.total, r.fail == 0);
    }

    @Test
    public void spiderTest() throws Exception {
        Rate r = auditPreferred("spider_test",
                loadJsonlField(RES + "spider_test_pairs.jsonl", "query"), null);
        int unexplained = 0;
        for (Fail f : r.fails) {
            if (!isSpiderTestAllowlisted(f.sql)) {
                unexplained++;
            }
        }
        assertTrue("spider_test rate=" + r.rate + "% fail=" + r.fail
                        + " unexplained=" + unexplained,
                r.rate >= 99.5 || unexplained == 0);
    }

    @Test
    public void complex100() throws Exception {
        List<Item> items = loadComplex100Markdown(
                RES + "complex100-主流数据库复杂业务SQL100条.md");
        if (items.isEmpty()) {
            items = loadComplex100Jsonl(RES + "complex100.jsonl");
        }
        assertTrue("complex100 corpus empty", !items.isEmpty());
        List<String> sqls = new ArrayList<String>();
        List<String> prefers = new ArrayList<String>();
        int skipped = 0;
        for (Item it : items) {
            if (!looksLikeSql(it.sql)) {
                skipped++;
                continue;
            }
            sqls.add(it.sql);
            prefers.add(it.prefer);
        }
        Rate r = auditPreferred("complex100", sqls, prefers);
        System.out.println("complex100 skipped_non_sql=" + skipped);
        assertTrue("complex100 rate " + r.rate + "% pass=" + r.pass + "/" + r.total, r.rate >= 95.0);
    }

    private static boolean isSpiderTestAllowlisted(String sql) {
        return sql != null && sql.contains(";,");
    }

    private static Rate auditPreferred(String name, List<String> sqls, List<String> prefers)
            throws Exception {
        Rate r = new Rate();
        r.total = sqls.size();
        Path dir = Paths.get("target", "sql-corpus-reports");
        Files.createDirectories(dir);
        Path failPath = dir.resolve(name + "-fails.tsv");
        BufferedWriter failLog = Files.newBufferedWriter(failPath, StandardCharsets.UTF_8);
        failLog.write("idx\terror\tsql\n");
        for (int i = 0; i < sqls.size(); i++) {
            String sql = sqls.get(i);
            SqlDialect prefer = null;
            if (prefers != null && i < prefers.size() && prefers.get(i) != null
                    && !prefers.get(i).isEmpty()) {
                try {
                    prefer = SqlDialect.valueOf(prefers.get(i));
                } catch (Exception ignored) {
                    prefer = null;
                }
            }
            String err = tryParse(sql, prefer);
            if (err == null) {
                r.pass++;
            } else {
                r.fail++;
                Fail f = new Fail();
                f.idx = i;
                f.error = err;
                f.sql = sql;
                r.fails.add(f);
                failLog.write(i + "\t" + esc(err) + "\t" + esc(trunc(sql, 400)) + "\n");
            }
        }
        failLog.close();
        r.rate = r.total == 0 ? 0.0 : 100.0 * r.pass / r.total;
        System.out.printf("CORPUS %s total=%d pass=%d fail=%d rate=%.2f%%%n",
                name, r.total, r.pass, r.fail, r.rate);
        System.out.println("wrote " + failPath.toAbsolutePath());
        return r;
    }

    private static String tryParse(String sql, SqlDialect prefer) {
        if (prefer != null) {
            try {
                SQL.parse(sql, prefer);
                return null;
            } catch (Throwable ignored) {
                // fallback
            }
        }
        String last = null;
        for (SqlDialect d : FALLBACK) {
            if (prefer != null && d == prefer) {
                continue;
            }
            try {
                SQL.parse(sql, d);
                return null;
            } catch (SqlParseException e) {
                last = e.getMessage();
            } catch (Throwable t) {
                last = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
        }
        return last == null ? "parse failed" : last;
    }

    private static List<String> loadBirdJsonArray(String resource) throws Exception {
        byte[] raw = readResourceBytes(resource);
        String text = new String(raw, Charset.forName("ISO-8859-1"));
        return parseJsonStringArray(text);
    }

    private static List<String> loadJsonlField(String resource, String field) throws Exception {
        List<String> out = new ArrayList<String>();
        InputStream in = open(resource);
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            out.add(extractJsonField(line, field));
        }
        br.close();
        return out;
    }

    private static List<Item> loadComplex100Markdown(String resource) throws Exception {
        List<Item> out = new ArrayList<Item>();
        InputStream in = ExternalSqlCorpusTest.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            return out;
        }
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String section = "";
        String title = "";
        String prefer = "MYSQL";
        StringBuilder fence = null;
        String line;
        int idx = 0;
        while ((line = br.readLine()) != null) {
            String t = line.trim();
            if (t.startsWith("#")) {
                if (t.startsWith("# ")) {
                    section = t;
                    prefer = preferOfSection(section);
                } else if (t.startsWith("## ")) {
                    title = t;
                }
                continue;
            }
            if (fence == null) {
                if (t.regionMatches(true, 0, "```sql", 0, 6)) {
                    fence = new StringBuilder();
                }
                continue;
            }
            if (t.startsWith("```")) {
                Item it = new Item();
                it.idx = idx++;
                it.section = section;
                it.title = title;
                it.prefer = prefer;
                it.sql = fence.toString().trim();
                out.add(it);
                fence = null;
                continue;
            }
            if (fence.length() > 0) {
                fence.append('\n');
            }
            fence.append(line);
        }
        br.close();
        return out;
    }

    private static List<Item> loadComplex100Jsonl(String resource) throws Exception {
        List<Item> out = new ArrayList<Item>();
        InputStream in = open(resource);
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            Item it = new Item();
            it.sql = extractJsonField(line, "sql");
            it.prefer = extractJsonField(line, "prefer");
            it.section = extractJsonField(line, "section");
            it.title = extractJsonField(line, "title");
            out.add(it);
        }
        br.close();
        return out;
    }

    private static String preferOfSection(String section) {
        String u = section.toUpperCase();
        if (u.contains("POSTGRES")) {
            return "POSTGRES";
        }
        if (u.contains("ORACLE")) {
            return "ORACLE";
        }
        if (u.contains("SQL SERVER") || u.contains("SQLSERVER")) {
            return "SQLSERVER";
        }
        return "MYSQL";
    }

    private static boolean looksLikeSql(String sql) {
        if (sql == null) {
            return false;
        }
        String s = sql.trim();
        if (s.isEmpty()) {
            return false;
        }
        String u = s.toUpperCase();
        return u.startsWith("SELECT") || u.startsWith("WITH") || u.startsWith("INSERT")
                || u.startsWith("UPDATE") || u.startsWith("DELETE") || u.startsWith("CREATE")
                || u.startsWith("MERGE") || u.startsWith("REPLACE") || u.startsWith("(")
                || u.startsWith("EXPLAIN") || u.startsWith("VALUES");
    }

    private static InputStream open(String resource) {
        InputStream in = ExternalSqlCorpusTest.class.getClassLoader().getResourceAsStream(resource);
        assertNotNull("missing classpath resource: " + resource, in);
        return in;
    }

    private static byte[] readResourceBytes(String resource) throws Exception {
        InputStream in = open(resource);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        return bos.toByteArray();
    }

    private static List<String> parseJsonStringArray(String text) {
        List<String> out = new ArrayList<String>();
        int i = 0;
        int n = text.length();
        while (i < n && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        if (i >= n || text.charAt(i) != '[') {
            throw new IllegalArgumentException("bird: not a JSON array");
        }
        i++;
        while (i < n) {
            while (i < n && (Character.isWhitespace(text.charAt(i)) || text.charAt(i) == ',')) {
                i++;
            }
            if (i < n && text.charAt(i) == ']') {
                break;
            }
            if (i >= n || text.charAt(i) != '"') {
                throw new IllegalArgumentException("bird: expected string at " + i);
            }
            i++;
            StringBuilder sb = new StringBuilder();
            while (i < n) {
                char c = text.charAt(i++);
                if (c == '\\') {
                    if (i >= n) {
                        break;
                    }
                    char e = text.charAt(i++);
                    switch (e) {
                        case '"':
                        case '\\':
                        case '/':
                            sb.append(e);
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'u':
                            if (i + 4 <= n) {
                                sb.append((char) Integer.parseInt(text.substring(i, i + 4), 16));
                                i += 4;
                            }
                            break;
                        default:
                            sb.append(e);
                            break;
                    }
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                }
            }
            out.add(sb.toString());
        }
        return out;
    }

    private static String extractJsonField(String json, String field) {
        String key = "\"" + field + "\"";
        int i = json.indexOf(key);
        if (i < 0) {
            return "";
        }
        i = json.indexOf(':', i + key.length());
        if (i < 0) {
            return "";
        }
        i++;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        if (i >= json.length() || json.charAt(i) != '"') {
            return "";
        }
        i++;
        StringBuilder sb = new StringBuilder();
        while (i < json.length()) {
            char c = json.charAt(i++);
            if (c == '\\') {
                if (i >= json.length()) {
                    break;
                }
                char e = json.charAt(i++);
                switch (e) {
                    case '"':
                    case '\\':
                    case '/':
                        sb.append(e);
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u':
                        if (i + 4 <= json.length()) {
                            sb.append((char) Integer.parseInt(json.substring(i, i + 4), 16));
                            i += 4;
                        }
                        break;
                    default:
                        sb.append(e);
                        break;
                }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
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

    private static final class Rate {
        int total;
        int pass;
        int fail;
        double rate;
        final List<Fail> fails = new ArrayList<Fail>();
    }

    private static final class Fail {
        int idx;
        String error;
        String sql;
    }

    private static final class Item {
        int idx;
        String section;
        String title;
        String prefer;
        String sql;
    }
}
