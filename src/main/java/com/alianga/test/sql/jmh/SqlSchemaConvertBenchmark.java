package com.alianga.test.sql.jmh;

import com.alianga.jkit.sql.SQL;
import com.alianga.jkit.sql.SqlDialect;

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
 * JMH：跨方言 DDL/SELECT 转换。构建后：
 * {@code java -jar target/benchmarks.jar com.alianga.test.sql.jmh.SqlSchemaConvertBenchmark -f 1 -wi 1 -i 1}
 *
 * @author 郑明亮
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(1)
@State(Scope.Thread)
public class SqlSchemaConvertBenchmark {

    private static final String DDL = "CREATE TABLE t ("
            + "id INT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
            + "name VARCHAR(32) NOT NULL, "
            + "flag TINYINT(1) DEFAULT 0, "
            + "amt DECIMAL(10,2), "
            + "ts DATETIME"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

    private static final String SELECT = "SELECT IF(a>1,b,c), NOW(), IFNULL(x,0), "
            + "GROUP_CONCAT(n SEPARATOR ',') FROM t LIMIT 10";

    /**
     * @param bh 黑洞
     */
    @Benchmark
    public void convertDdlMysqlToPostgres(Blackhole bh) {
        bh.consume(SQL.convert(DDL, SqlDialect.MYSQL, SqlDialect.POSTGRES));
    }

    /**
     * @param bh 黑洞
     */
    @Benchmark
    public void convertSelectMysqlToPostgres(Blackhole bh) {
        bh.consume(SQL.convert(SELECT, SqlDialect.MYSQL, SqlDialect.POSTGRES));
    }

    /**
     * @param bh 黑洞
     */
    @Benchmark
    public void convertDdlMysqlToOracle12(Blackhole bh) {
        bh.consume(SQL.convert(DDL, SqlDialect.MYSQL, SqlDialect.ORACLE12));
    }
}
