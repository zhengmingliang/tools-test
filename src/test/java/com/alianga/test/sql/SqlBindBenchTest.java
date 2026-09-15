package com.alianga.test.sql;

import com.alianga.jkit.sql.SQL;
import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.SqlParseOptions;
import com.alianga.jkit.sql.SqlPlaceholders;
import com.alianga.jkit.sql.ast.SqlStatement;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.visitor.ParameterizedOutputVisitorUtils;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.util.deparser.ExpressionDeParser;
import net.sf.jsqlparser.util.deparser.SelectDeParser;
import net.sf.jsqlparser.util.deparser.StatementDeParser;

import org.junit.Assert;
import org.junit.Test;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SQL.bind 吞吐微基准：jkit vs Druid SQLUtils.format/restore vs JSqlParser 反解析填值。
 * 不引入到 jkit-sql POM；仅 tools-test。
 *
 * @author 郑明亮
 */
public class SqlBindBenchTest {

    private static final String POS_SQL =
            "SELECT * FROM t_user WHERE name = ? AND age = ? AND nick = ?";
    private static final String NAMED_SQL =
            "SELECT * FROM t_user WHERE name = :name AND age = :age AND nick = :nick";
    private static final String DRUID_NAMED_SQL =
            "SELECT * FROM t_user WHERE name = #{name} AND age = #{age} AND nick = #{nick}";
    private static final String TPL_SQL =
            "SELECT * FROM #{table} WHERE name = :name AND age = :age AND ts > :now";
    private static final String PAYLOAD = "'; DROP TABLE t_user; --";

    private static final int WARMUP = 2000;
    private static final int ITER = 20000;

    @Test
    public void bindThroughput() throws Exception {
        List<Object> pos = Arrays.<Object>asList("bob", Integer.valueOf(20), PAYLOAD);
        Map<String, Object> named = new LinkedHashMap<String, Object>();
        named.put("name", "bob");
        named.put("age", Integer.valueOf(20));
        named.put("nick", PAYLOAD);

        StringBuilder report = new StringBuilder();
        report.append("# SQL bind bench (jkit vs Druid vs JSqlParser)\n\n");
        report.append("warmup=").append(WARMUP).append(" iter=").append(ITER).append('\n');
        report.append("host=").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.arch")).append(" java=")
                .append(System.getProperty("java.version")).append("\n\n");
        report.append("positional SQL: ").append(POS_SQL).append('\n');
        report.append("named SQL (jkit/jsql `:name`, druid `#{name}`): ").append(NAMED_SQL).append('\n');
        report.append('\n');

        bench(report, "A.full_path_positional",
                new Runnable() {
                    public void run() {
                        SQL.bind(POS_SQL, SqlDialect.MYSQL, pos.toArray());
                    }
                },
                new Runnable() {
                    public void run() {
                        SQLUtils.format(POS_SQL, DbType.mysql, pos);
                    }
                },
                new Runnable() {
                    public void run() {
                        jsqlBind(jsqlParse(POS_SQL), pos, null);
                    }
                });

        bench(report, "B.full_path_named",
                new Runnable() {
                    public void run() {
                        SQL.bindNamed(NAMED_SQL, SqlDialect.MYSQL, named);
                    }
                },
                new Runnable() {
                    public void run() {
                        ParameterizedOutputVisitorUtils.restore(DRUID_NAMED_SQL, DbType.mysql, named);
                    }
                },
                new Runnable() {
                    public void run() {
                        jsqlBind(jsqlParse(NAMED_SQL), null, named);
                    }
                });

        final SqlStatement jkitPosAst = SQL.parse(POS_SQL, SqlDialect.MYSQL);
        final List<SQLStatement> druidPosAst = SQLUtils.parseStatements(POS_SQL, DbType.mysql);
        final Statement jsqlPosAst = jsqlParse(POS_SQL);

        bench(report, "C.cached_ast_positional",
                new Runnable() {
                    public void run() {
                        SQL.toSqlString(SQL.bind(jkitPosAst, SqlDialect.MYSQL, pos.toArray()),
                                SqlDialect.MYSQL);
                    }
                },
                new Runnable() {
                    public void run() {
                        SQLUtils.toSQLString(druidPosAst, DbType.mysql, pos);
                    }
                },
                new Runnable() {
                    public void run() {
                        jsqlBind(jsqlPosAst, pos, null);
                    }
                });

        final SqlStatement jkitNamedAst = SQL.parse(NAMED_SQL, SqlDialect.MYSQL);
        final Statement jsqlNamedAst = jsqlParse(NAMED_SQL);
        bench(report, "C.cached_ast_named",
                new Runnable() {
                    public void run() {
                        SQL.toSqlString(SQL.bindNamed(jkitNamedAst, SqlDialect.MYSQL, named),
                                SqlDialect.MYSQL);
                    }
                },
                new Runnable() {
                    public void run() {
                        ParameterizedOutputVisitorUtils.restore(DRUID_NAMED_SQL, DbType.mysql, named);
                    }
                },
                new Runnable() {
                    public void run() {
                        jsqlBind(jsqlNamedAst, null, named);
                    }
                });

        SqlParseOptions tplOpt = SqlParseOptions.defaults()
                .placeholders(SqlPlaceholders.create().mybatis());
        Map<String, Object> tplVals = new LinkedHashMap<String, Object>();
        tplVals.put("table", Integer.valueOf(10086));
        tplVals.put("name", "bob");
        tplVals.put("age", Integer.valueOf(20));
        tplVals.put("now", SQL.parseExpr("NOW()"));
        long tplNs = nsPerOp(new Runnable() {
            public void run() {
                SQL.bindNamed(TPL_SQL, SqlDialect.MYSQL, tplOpt, tplVals);
            }
        });
        report.append(String.format(Locale.ROOT,
                "%-40s  jkit=%7d ns/op  druid=N/A        jsql=N/A        | template+formula (jkit only)%n",
                "D.template_hash_table_and_formula", tplNs));

        String jkitPos = SQL.bind(POS_SQL, SqlDialect.MYSQL, pos.toArray());
        String jkitNamed = SQL.bindNamed(NAMED_SQL, SqlDialect.MYSQL, named);
        String jkitTpl = SQL.bindNamed(TPL_SQL, SqlDialect.MYSQL, tplOpt, tplVals);
        String druidPos = SQLUtils.format(POS_SQL, DbType.mysql, pos);
        String jsqlPos = jsqlBind(jsqlParse(POS_SQL), pos, null);

        report.append("\n## samples\n");
        report.append("jkit positional: ").append(jkitPos).append('\n');
        report.append("jkit named:      ").append(jkitNamed).append('\n');
        report.append("jkit template:   ").append(jkitTpl).append('\n');
        report.append("druid positional:").append(druidPos).append('\n');
        try {
            report.append("druid named:     ")
                    .append(ParameterizedOutputVisitorUtils.restore(DRUID_NAMED_SQL, DbType.mysql, named))
                    .append('\n');
        } catch (RuntimeException ex) {
            report.append("druid named:     FAIL ").append(ex.getClass().getSimpleName())
                    .append(": ").append(ex.getMessage()).append('\n');
        }
        report.append("jsql positional: ").append(jsqlPos).append('\n');
        report.append("jsql named:      ").append(jsqlBind(jsqlParse(NAMED_SQL), null, named)).append('\n');

        Path out = Paths.get("target", "sql-bind-bench.md");
        Files.createDirectories(out.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            w.write(report.toString());
        }
        System.out.println(report);

        Assert.assertTrue("payload must stay inside a string: " + jkitPos,
                jkitPos.contains("'''; DROP TABLE t_user; --'"));
        Assert.assertEquals(1, SQL.parseAll(jkitPos).size());
        Assert.assertTrue(jkitNamed, jkitNamed.contains("'bob'"));
        Assert.assertTrue(jkitTpl, jkitTpl.contains("`10086`"));
        Assert.assertTrue(jkitTpl, jkitTpl.contains("NOW()"));
        Assert.assertFalse(jkitTpl, jkitTpl.contains("'NOW()'"));
        SQL.parse(jkitPos, SqlDialect.MYSQL);
        SQL.parse(jkitNamed, SqlDialect.MYSQL);
        SQL.parse(jkitTpl, SqlDialect.MYSQL);
    }

    private static Statement jsqlParse(String sql) {
        try {
            return CCJSqlParserUtil.parse(sql);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String jsqlBind(Statement st, List<Object> positional, Map<String, Object> named) {
        StringBuilder buf = new StringBuilder(128);
        BindExprDeParser expr = new BindExprDeParser(
                positional == null ? Collections.emptyList() : positional,
                named == null ? Collections.<String, Object>emptyMap() : named);
        SelectDeParser select = new SelectDeParser(expr, buf);
        expr.setSelectVisitor(select);
        expr.setBuffer(buf);
        StatementDeParser deparser = new StatementDeParser(expr, select, buf);
        st.accept(deparser);
        return buf.toString();
    }

    private static void bench(StringBuilder report, String name,
            Runnable jkit, Runnable druid, Runnable jsql) {
        long jk = timed(jkit);
        long dr = timed(druid);
        long js = timed(jsql);
        report.append(String.format(Locale.ROOT,
                "%-40s  jkit=%7s ns/op  druid=%7s ns/op  jsql=%7s ns/op  | jkit/druid=%s  jkit/jsql=%s%n",
                name, fmtNs(jk), fmtNs(dr), fmtNs(js), ratio(jk, dr), ratio(jk, js)));
    }

    private static long timed(Runnable r) {
        try {
            return nsPerOp(r);
        } catch (RuntimeException ex) {
            return -1L;
        }
    }

    private static String fmtNs(long ns) {
        return ns < 0 ? "FAIL" : Long.toString(ns);
    }

    private static String ratio(long jkit, long other) {
        if (jkit < 0 || other <= 0) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%.2fx", (double) jkit / (double) other);
    }

    private static long nsPerOp(Runnable r) {
        for (int i = 0; i < WARMUP; i++) {
            r.run();
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < ITER; i++) {
            r.run();
        }
        return (System.nanoTime() - t0) / ITER;
    }

    private static final class BindExprDeParser extends ExpressionDeParser {
        private final List<Object> positional;
        private final Map<String, Object> named;
        private int pos;

        BindExprDeParser(List<Object> positional, Map<String, Object> named) {
            this.positional = positional;
            this.named = named;
        }

        @Override
        public void visit(JdbcParameter parameter) {
            Object v = pos < positional.size() ? positional.get(pos++) : null;
            getBuffer().append(sqlLiteral(v));
        }

        @Override
        public void visit(JdbcNamedParameter parameter) {
            getBuffer().append(sqlLiteral(named.get(parameter.getName())));
        }

        private static String sqlLiteral(Object v) {
            if (v == null) {
                return "NULL";
            }
            if (v instanceof Number || v instanceof Boolean) {
                return v.toString();
            }
            String s = String.valueOf(v);
            return "'" + s.replace("'", "''") + "'";
        }
    }
}
