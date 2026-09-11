package com.alianga.test.sql.jmh;

import com.alianga.jkit.sql.SQL;
import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.ast.SqlStatement;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.PagerUtils;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * JMH：跨方言分页改写。构建后：
 * {@code java -jar target/benchmarks.jar com.alianga.test.sql.jmh.SqlRewriteBenchmark -f 1 -wi 1 -i 1}
 *
 * @author 郑明亮
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(1)
@State(Scope.Thread)
public class SqlRewriteBenchmark {

    private static final String BARE = "SELECT id, name FROM t_user WHERE age > 18";
    private static final String MYSQL_COMMA = BARE + " limit 0,10000";

    @Benchmark
    public void jkitMysqlToOracle(Blackhole bh) {
        bh.consume(SQL.toSqlString(SQL.parse(MYSQL_COMMA, SqlDialect.MYSQL), SqlDialect.ORACLE));
    }

    @Benchmark
    public void jkitRownumToPostgres(Blackhole bh) {
        SqlStatement o = SQL.adaptPagination(SQL.parse(MYSQL_COMMA), SqlDialect.ORACLE);
        bh.consume(SQL.format(o, SqlDialect.POSTGRES));
    }

    @Benchmark
    public void druidPagerOracle(Blackhole bh) {
        bh.consume(PagerUtils.limit(BARE, DbType.oracle, 0, 10000));
    }

    @Benchmark
    public void druidPagerPostgres(Blackhole bh) {
        bh.consume(PagerUtils.limit(BARE, DbType.postgresql, 0, 10000));
    }

    @Benchmark
    public void jsqlSetLimit(Blackhole bh) throws Exception {
        Statement st = CCJSqlParserUtil.parse(BARE);
        PlainSelect ps = (PlainSelect) ((Select) st).getSelectBody();
        Limit lim = new Limit();
        lim.setRowCount(new LongValue(10000));
        ps.setLimit(lim);
        bh.consume(st.toString());
    }
}
