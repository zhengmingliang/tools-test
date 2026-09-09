package com.alianga.test.sql.jmh;

import com.alianga.jkit.sql.SQL;
import com.alianga.jkit.sql.SqlDialect;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Formal JMH parse throughput for simple / join / window SQL.
 *
 * <p>Build uber-jar then run:
 * <pre>
 *   mvn -DskipTests package
 *   # smoke
 *   java -jar target/benchmarks.jar com.alianga.test.sql.jmh.SqlParseBenchmark -f 1 -wi 1 -i 1
 *   # full (fork≥2)
 *   java -jar target/benchmarks.jar com.alianga.test.sql.jmh.SqlParseBenchmark -f 2 -wi 5 -i 5
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(2)
@State(Scope.Thread)
public class SqlParseBenchmark {

    public enum Engine {
        JKIT, DRUID, JSQL
    }

    public enum SqlKind {
        SIMPLE("SELECT id, name, age FROM user WHERE id = ?"),
        JOIN("SELECT a.id, b.name FROM user a LEFT JOIN order_t b ON a.id = b.uid WHERE a.age > 18"),
        WINDOW("SELECT id, ROW_NUMBER() OVER (PARTITION BY dept ORDER BY score) FROM emp");

        final String sql;

        SqlKind(String sql) {
            this.sql = sql;
        }
    }

    @Param({"SIMPLE", "JOIN", "WINDOW"})
    public SqlKind kind;

    @Param({"JKIT", "DRUID", "JSQL"})
    public Engine engine;

    private String sql;
    private SqlDialect dialect;
    private DbType dbType;

    @Setup
    public void setup() {
        sql = kind.sql;
        dialect = SqlDialect.MYSQL;
        dbType = DbType.mysql;
    }

    @Benchmark
    public void parse(Blackhole bh) throws Exception {
        switch (engine) {
            case JKIT:
                bh.consume(SQL.parse(sql, dialect));
                break;
            case DRUID:
                bh.consume(SQLUtils.parseStatements(sql, dbType));
                break;
            case JSQL:
                bh.consume(CCJSqlParserUtil.parse(sql));
                break;
            default:
                throw new IllegalStateException(String.valueOf(engine));
        }
    }
}
