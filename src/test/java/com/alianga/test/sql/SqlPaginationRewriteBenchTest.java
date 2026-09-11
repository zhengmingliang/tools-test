package com.alianga.test.sql;

import com.alianga.jkit.sql.SQL;
import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.SqlRewrites;
import com.alianga.jkit.sql.ast.SqlStatement;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.PagerUtils;
import com.alibaba.druid.sql.SQLUtils;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.Offset;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;

import org.junit.Test;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * 分页改写吞吐微基准：jkit vs Druid PagerUtils/SQLUtils vs JSqlParser Limit 改写。
 * 不引入到 jkit-sql POM；仅 tools-test。
 *
 * @author 郑明亮
 */
public class SqlPaginationRewriteBenchTest {

    private static final String BARE = "SELECT id, name FROM t_user WHERE age > 18";
    private static final String MYSQL_COMMA = BARE + " limit 0,10000";
    private static final String MYSQL_OFF = BARE + " LIMIT 30,30";
    private static final int WARMUP = 2000;
    private static final int ITER = 20000;

    @Test
    public void paginationRewriteThroughput() throws Exception {
        StringBuilder report = new StringBuilder();
        report.append("# SQL pagination rewrite bench (jkit vs Druid vs JSqlParser)\n\n");
        report.append("warmup=").append(WARMUP).append(" iter=").append(ITER).append('\n');
        report.append("host=").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.arch")).append(" java=")
                .append(System.getProperty("java.version")).append("\n\n");

        // A parse only
        bench(report, "A.parse_only", new Runnable() {
            public void run() { SQL.parse(MYSQL_COMMA, SqlDialect.MYSQL); }
        }, new Runnable() {
            public void run() { SQLUtils.parseStatements(MYSQL_COMMA, DbType.mysql); }
        }, new Runnable() {
            public void run() {
                try { CCJSqlParserUtil.parse(MYSQL_COMMA); }
                catch (Exception e) { throw new RuntimeException(e); }
            }
        });

        // B parse + format same dialect
        bench(report, "B.parse_format_same_mysql", new Runnable() {
            public void run() {
                SqlStatement s = SQL.parse(MYSQL_COMMA, SqlDialect.MYSQL);
                SQL.toSqlString(s, SqlDialect.MYSQL);
            }
        }, new Runnable() {
            public void run() {
                SQLUtils.toSQLString(SQLUtils.parseStatements(MYSQL_COMMA, DbType.mysql), DbType.mysql);
            }
        }, new Runnable() {
            public void run() {
                try { CCJSqlParserUtil.parse(MYSQL_COMMA).toString(); }
                catch (Exception e) { throw new RuntimeException(e); }
            }
        });

        // C1 MySQL LIMIT → ORACLE format
        bench(report, "C.mysql_limit_to_oracle_format", new Runnable() {
            public void run() {
                SQL.toSqlString(SQL.parse(MYSQL_COMMA, SqlDialect.MYSQL), SqlDialect.ORACLE);
            }
        }, new Runnable() {
            public void run() {
                // Druid: 对无分页裸 SQL 按方言 limit；逗号 LIMIT 源串对 oracle 解析不稳，用裸 SQL+limit
                PagerUtils.limit(BARE, DbType.oracle, 0, 10000);
            }
        }, new Runnable() {
            public void run() {
                // JSqlParser 无跨方言 ROWNUM 适配：parse+deparse 仅保 LIMIT
                try { CCJSqlParserUtil.parse(MYSQL_COMMA).toString(); }
                catch (Exception e) { throw new RuntimeException(e); }
            }
        });

        // C2 MySQL LIMIT → POSTGRES format（含逗号规范化）
        bench(report, "C.mysql_comma_to_postgres_format", new Runnable() {
            public void run() {
                SQL.toSqlString(SQL.parse(MYSQL_COMMA, SqlDialect.MYSQL), SqlDialect.POSTGRES);
            }
        }, new Runnable() {
            public void run() {
                PagerUtils.limit(BARE, DbType.postgresql, 0, 10000);
            }
        }, new Runnable() {
            public void run() {
                try {
                    Statement st = CCJSqlParserUtil.parse(BARE);
                    PlainSelect ps = (PlainSelect) ((Select) st).getSelectBody();
                    Limit lim = new Limit();
                    lim.setRowCount(new LongValue(10000));
                    ps.setLimit(lim);
                    st.toString();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });

        // C3 ORACLE ROWNUM → POSTGRES（用户原句路径）
        bench(report, "C.oracle_rownum_to_postgres_format", new Runnable() {
            public void run() {
                SqlStatement o = SQL.adaptPagination(SQL.parse(MYSQL_COMMA), SqlDialect.ORACLE);
                SQL.format(o, SqlDialect.POSTGRES);
            }
        }, new Runnable() {
            public void run() {
                String oracleSql = PagerUtils.limit(BARE, DbType.oracle, 0, 10000);
                // Druid 无「ROWNUM AST → PG LIMIT」一等 API：再按 PG limit 重写裸查询近似
                PagerUtils.limit(BARE, DbType.postgresql, 0, 10000);
                SQLUtils.toSQLString(SQLUtils.parseStatements(oracleSql, DbType.oracle), DbType.oracle);
            }
        }, new Runnable() {
            public void run() {
                try {
                    // JSQL 无法识别 ROWNUM 包装语义；测 parse+Limit 改写基线
                    Statement st = CCJSqlParserUtil.parse(BARE);
                    PlainSelect ps = (PlainSelect) ((Select) st).getSelectBody();
                    Limit lim = new Limit();
                    lim.setRowCount(new LongValue(10000));
                    ps.setLimit(lim);
                    st.toString();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });

        // D setPage / adaptPagination
        bench(report, "D.setPage_or_limit_page2", new Runnable() {
            public void run() {
                SQL.setPage(SQL.parse(BARE), 2, 30, SqlDialect.ORACLE);
            }
        }, new Runnable() {
            public void run() {
                PagerUtils.limit(BARE, DbType.oracle, 30, 30);
            }
        }, new Runnable() {
            public void run() {
                try {
                    Statement st = CCJSqlParserUtil.parse(BARE);
                    PlainSelect ps = (PlainSelect) ((Select) st).getSelectBody();
                    Limit lim = new Limit();
                    lim.setRowCount(new LongValue(30));
                    Offset off = new Offset();
                    off.setOffset(new LongValue(30));
                    ps.setLimit(lim);
                    ps.setOffset(off);
                    st.toString();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });

        // same-dialect no-op path (efficiency constraint)
        final SqlStatement pgReady = SQL.parse(BARE + " LIMIT 10000", SqlDialect.POSTGRES);
        long t0 = System.nanoTime();
        for (int i = 0; i < WARMUP; i++) {
            SQL.toSqlString(pgReady, SqlDialect.POSTGRES);
        }
        t0 = System.nanoTime();
        for (int i = 0; i < ITER; i++) {
            SQL.toSqlString(pgReady, SqlDialect.POSTGRES);
        }
        long ns = (System.nanoTime() - t0) / ITER;
        report.append(String.format(Locale.ROOT,
                "E.same_form_postgres_format_noop_adapt  jkit=%d ns/op (expect no clone+adapt)%n", ns));

        // correctness samples
        report.append("\n## samples\n");
        report.append("jkit mysql→oracle: ")
                .append(SQL.toSqlString(SQL.parse(MYSQL_COMMA), SqlDialect.ORACLE)).append('\n');
        report.append("jkit user path: ")
                .append(SQL.format(SQL.rewrite(SQL.parse(MYSQL_COMMA),
                        SqlRewrites.create().add(SqlRewrites.adaptPagination(SqlDialect.ORACLE))),
                        SqlDialect.POSTGRES)).append('\n');
        report.append("jkit page2→pg: ")
                .append(SQL.format(SQL.setPage(SQL.parse(MYSQL_OFF), 2, 30, SqlDialect.ORACLE),
                        SqlDialect.POSTGRES)).append('\n');
        report.append("druid limit oracle: ").append(PagerUtils.limit(BARE, DbType.oracle, 0, 10000)).append('\n');
        report.append("druid limit pg: ").append(PagerUtils.limit(BARE, DbType.postgresql, 30, 30)).append('\n');

        Path out = Paths.get("target", "sql-pagination-rewrite-bench.md");
        Files.createDirectories(out.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            w.write(report.toString());
        }
        System.out.println(report);
    }

    private static void bench(StringBuilder report, String name,
                              Runnable jkit, Runnable druid, Runnable jsql) {
        long jk = nsPerOp(jkit);
        long dr = nsPerOp(druid);
        long js = nsPerOp(jsql);
        report.append(String.format(Locale.ROOT,
                "%-40s  jkit=%7d ns/op  druid=%7d ns/op  jsql=%7d ns/op  | jkit/druid=%.2fx  jkit/jsql=%.2fx%n",
                name, jk, dr, js, (double) jk / (double) dr, (double) jk / (double) js));
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
}
