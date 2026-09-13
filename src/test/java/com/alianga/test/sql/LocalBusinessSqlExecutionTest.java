package com.alianga.test.sql;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 把 {@code jkit-sql/src/test/resources/sqls/1.md} 的 10 条跨方言业务 SQL，配合
 * {@code 1.1.md} 的初始化脚本，丢进本机真实数据库执行，验证它们不仅能被 jkit 解析，
 * 也能在目标方言真库上正确建表、插数、查询并返回预期结果。
 *
 * <p>数据源见 {@code tools-test/src/test/resources/datasource}。为绝对避免触碰用户既有数据，
 * 每条 SQL 的表名都加 {@code jkit_qN_} 前缀，在各自方言库里建独立的隔离表；测试结束
 * （{@link #teardown()}）统一 DROP 这些前缀表。</p>
 *
 * <p>数据库不可达时相关用例自动 skip（{@link Assume}）。外部 JDBC 依赖本就在 tools-test 中。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class LocalBusinessSqlExecutionTest {

    private static Connection mysql;   // 127.0.0.1:3308/bi_mysql_source
    private static Connection pg;      // localhost:5532/postgres
    private static Connection oracle;  // localhost:1521:ORCL (ZML)
    private static Connection dm;      // localhost:5236

    @BeforeClass
    public static void connect() {
        mysql = tryConnect("jdbc:mysql://127.0.0.1:3308/bi_mysql_source"
                        + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "root", "mingliang", "com.mysql.cj.jdbc.Driver");
        pg = tryConnect("jdbc:postgresql://localhost:5532/postgres",
                "postgres", "zml-mingliang", "org.postgresql.Driver");
        oracle = tryConnect("jdbc:oracle:thin:@localhost:1521:ORCL",
                "ZML", "mingliang", "oracle.jdbc.OracleDriver");
        dm = tryConnect("jdbc:dm://localhost:5236",
                "SYSDBA", "SYSDBA001", "dm.jdbc.driver.DmDriver");
        if (oracle != null) {
            try (Statement st = oracle.createStatement()) {
                st.execute("ALTER SESSION SET NLS_DATE_FORMAT='YYYY-MM-DD'");
            } catch (Exception ignored) {
            }
        }
    }

    @AfterClass
    public static void teardown() {
        dropPrefixed(mysql, "jkit_q");
        dropPrefixed(pg, "jkit_q");
        dropPrefixed(oracle, "jkit_q");
        dropPrefixed(dm, "jkit_q");
        for (Connection c : new Connection[]{mysql, pg, oracle, dm}) {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ====================== MySQL ======================

    @Test
    public void mysqlQ1Window7Day() throws Exception {
        Assume.assumeNotNull(mysql);
        String t = "jkit_q1_sales";
        init(mysql, new String[]{
                "DROP TABLE IF EXISTS " + t,
                "CREATE TABLE " + t + " (`date` DATE, sales_amount DECIMAL(10,2))",
                "INSERT INTO " + t + " (`date`, sales_amount) VALUES"
                        + "('2023-10-01',100.00),('2023-10-02',150.00),('2023-10-03',200.00),"
                        + "('2023-10-04',120.00),('2023-10-05',300.00),('2023-10-06',250.00),"
                        + "('2023-10-07',400.00),('2023-10-08',350.00)"
        });
        int rows = queryRows(mysql,
                "SELECT date, sales_amount,"
                        + " AVG(sales_amount) OVER (ORDER BY date ROWS BETWEEN 6 PRECEDING AND CURRENT ROW)"
                        + " AS moving_average_7day FROM " + t);
        assertEquals(8, rows);
    }

    @Test
    public void mysqlQ5JoinGroupByHaving() throws Exception {
        Assume.assumeNotNull(mysql);
        String c = "jkit_q5_customers", o = "jkit_q5_orders";
        init(mysql, new String[]{
                "DROP TABLE IF EXISTS " + o, "DROP TABLE IF EXISTS " + c,
                "CREATE TABLE " + c + " (customer_id INT PRIMARY KEY, name VARCHAR(50))",
                "CREATE TABLE " + o + " (order_id INT PRIMARY KEY, customer_id INT, total_amount DECIMAL(10,2))",
                "INSERT INTO " + c + " VALUES (1,'Alice'),(2,'Bob'),(3,'Charlie')",
                "INSERT INTO " + o + " VALUES (101,1,600.00),(102,1,500.00),(103,2,300.00),(104,3,1200.00)"
        });
        // Alice 1100、Charlie 1200 通过 HAVING；Bob 300 不通过
        int rows = queryRows(mysql,
                "SELECT c.customer_id, c.name, COUNT(o.order_id) AS order_count,"
                        + " SUM(o.total_amount) AS total_spent FROM " + c + " c"
                        + " LEFT JOIN " + o + " o ON c.customer_id = o.customer_id"
                        + " GROUP BY c.customer_id, c.name HAVING total_spent > 1000");
        assertEquals(2, rows);
    }

    @Test
    public void mysqlQ9CteScalarSubquery() throws Exception {
        Assume.assumeNotNull(mysql);
        String t = "jkit_q9_sales";
        init(mysql, new String[]{
                "DROP TABLE IF EXISTS " + t,
                "CREATE TABLE " + t + " (`date` DATE, product_id INT, amount DECIMAL(10,2))",
                "INSERT INTO " + t + " VALUES"
                        + "('2023-10-01',1,100.00),('2023-10-02',1,120.00),"
                        + "('2023-10-03',1,900.00),('2023-10-04',1,110.00)"
        });
        // 日均：100/120/900/110，均值 307.5，两倍 615，仅 900 命中
        int rows = queryRows(mysql,
                "WITH daily_sales AS ("
                        + " SELECT date, product_id, SUM(amount) as daily_amount FROM " + t
                        + " GROUP BY date, product_id)"
                        + " SELECT date, product_id, daily_amount FROM daily_sales"
                        + " WHERE daily_amount > (SELECT AVG(daily_amount) * 2 FROM daily_sales)");
        assertEquals(1, rows);
    }

    // ====================== Oracle ======================

    @Test
    public void oracleQ2ConnectByHierarchy() throws Exception {
        Assume.assumeNotNull(oracle);
        String t = "jkit_q2_employees";
        safeDrop(oracle, t);
        init(oracle, new String[]{
                "CREATE TABLE " + t + " (employee_id NUMBER PRIMARY KEY, first_name VARCHAR2(50), manager_id NUMBER)",
                "INSERT INTO " + t + " VALUES (1,'CEO_Alice',NULL)",
                "INSERT INTO " + t + " VALUES (2,'VP_Bob',1)",
                "INSERT INTO " + t + " VALUES (3,'Manager_Charlie',2)",
                "INSERT INTO " + t + " VALUES (4,'Staff_David',3)",
                "COMMIT"
        });
        // 4 人层级，最大 LEVEL = 4
        int maxLevel = 0;
        try (Statement st = oracle.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT LEVEL as hierarchy_level FROM " + t
                             + " START WITH manager_id IS NULL"
                             + " CONNECT BY PRIOR employee_id = manager_id")) {
            while (rs.next()) {
                maxLevel = Math.max(maxLevel, rs.getInt(1));
            }
        }
        assertEquals(4, maxLevel);
    }

    @Test
    public void oracleQ6AvgPartitionDiff() throws Exception {
        Assume.assumeNotNull(oracle);
        String t = "jkit_q6_employees";
        safeDrop(oracle, t);
        init(oracle, new String[]{
                "CREATE TABLE " + t + " (employee_id NUMBER PRIMARY KEY, department_id NUMBER, salary NUMBER(10,2))",
                "INSERT INTO " + t + " VALUES (1,10,5000)",
                "INSERT INTO " + t + " VALUES (2,10,7000)",
                "INSERT INTO " + t + " VALUES (3,20,8000)",
                "INSERT INTO " + t + " VALUES (4,20,10000)",
                "COMMIT"
        });
        // 部门 10 均值 6000、部门 20 均值 9000，diff 应分别为 ±1000
        boolean seenMinus1000 = false;
        try (Statement st = oracle.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT salary - AVG(salary) OVER (PARTITION BY department_id) AS diff"
                             + " FROM " + t)) {
            while (rs.next()) {
                if (Math.abs(rs.getDouble(1) - (-1000.0)) < 0.01) {
                    seenMinus1000 = true;
                }
            }
        }
        assertTrue("应出现与部门均值差额为 -1000 的记录", seenMinus1000);
    }

    @Test
    public void oracleQ10MergeUpsert() throws Exception {
        Assume.assumeNotNull(oracle);
        String tg = "jkit_q10_target", src = "jkit_q10_source";
        safeDrop(oracle, tg);
        safeDrop(oracle, src);
        init(oracle, new String[]{
                "CREATE TABLE " + tg + " (id NUMBER PRIMARY KEY, value VARCHAR2(50), update_date DATE, create_date DATE)",
                "CREATE TABLE " + src + " (id NUMBER PRIMARY KEY, value VARCHAR2(50))",
                "INSERT INTO " + tg + " VALUES (1,'Old_Value_1',NULL,SYSDATE-1)",
                "INSERT INTO " + tg + " VALUES (2,'Old_Value_2',NULL,SYSDATE-1)",
                "INSERT INTO " + src + " VALUES (1,'New_Value_1')",
                "INSERT INTO " + src + " VALUES (3,'New_Value_3')",
                "COMMIT"
        });
        // 目标表原有 2 行（id 1,2），源表 id 1 命中更新、id 3 插入 → 共 3 行；用数据行数断言
        try (Statement st = oracle.createStatement()) {
            st.execute("MERGE INTO " + tg + " t USING " + src + " s ON (t.id = s.id)"
                    + " WHEN MATCHED THEN UPDATE SET t.value = s.value, t.update_date = SYSDATE"
                    + " WHEN NOT MATCHED THEN INSERT (id, value, create_date) VALUES (s.id, s.value, SYSDATE)");
        }
        assertEquals(3, queryRows(oracle, "SELECT id FROM " + tg));
    }

    // ====================== PostgreSQL ======================

    @Test
    public void pgQ3CteRankTop3() throws Exception {
        Assume.assumeNotNull(pg);
        String t = "jkit_q3_sales";
        init(pg, new String[]{
                "DROP TABLE IF EXISTS " + t,
                "CREATE TABLE " + t + " (product_id INT, sale_date DATE, amount DECIMAL(10,2))",
                "INSERT INTO " + t + " (product_id, sale_date, amount) VALUES"
                        + "(1,'2023-10-01',500.00),(1,'2023-10-02',800.00),(1,'2023-10-03',300.00),"
                        + "(1,'2023-10-04',900.00),(2,'2023-10-01',150.00),(2,'2023-10-02',450.00),(2,'2023-10-03',200.00)"
        });
        // 产品1 取 rank<=3（共4行取3），产品2 取 rank<=3（共3行取3）→ 6 行
        int rows = queryRows(pg,
                "WITH ranked_sales AS ("
                        + " SELECT product_id, sale_date, amount,"
                        + " RANK() OVER (PARTITION BY product_id ORDER BY amount DESC) as rank"
                        + " FROM " + t + ")"
                        + " SELECT * FROM ranked_sales WHERE rank <= 3");
        assertEquals(6, rows);
    }

    @Test
    public void pgQ7RecursiveCte() throws Exception {
        Assume.assumeNotNull(pg);
        String t = "jkit_q7_categories";
        init(pg, new String[]{
                "DROP TABLE IF EXISTS " + t,
                "CREATE TABLE " + t + " (id INT PRIMARY KEY, name VARCHAR(50), parent_id INT)",
                "INSERT INTO " + t + " VALUES (1,'Electronics',NULL),(2,'Computers',1),"
                        + "(3,'Laptops',2),(4,'Smartphones',1),(5,'Furniture',NULL)"
        });
        int rows = queryRows(pg,
                "WITH RECURSIVE category_tree AS ("
                        + " SELECT id, name, parent_id FROM " + t + " WHERE parent_id IS NULL"
                        + " UNION ALL"
                        + " SELECT c.id, c.name, c.parent_id FROM " + t + " c"
                        + " JOIN category_tree ct ON c.parent_id = ct.id)"
                        + " SELECT * FROM category_tree");
        assertEquals(5, rows);
    }

    // ====================== 达梦 ======================

    @Test
    public void dmQ4CumulativeSum() throws Exception {
        Assume.assumeNotNull(dm);
        String t = "jkit_q4_orders";
        safeDrop(dm, t);
        init(dm, new String[]{
                "CREATE TABLE " + t + " (order_id INT, order_date DATE, amount DECIMAL(10,2))",
                "INSERT INTO " + t + " (order_id, order_date, amount) VALUES"
                        + "(101, DATE '2023-10-01', 1000.00),(102, DATE '2023-10-02', 1500.00),"
                        + "(103, DATE '2023-10-03', 800.00),(104, DATE '2023-10-04', 2000.00)",
                "COMMIT"
        });
        assertEquals(4, queryRows(dm,
                "SELECT order_id, order_date, amount,"
                        + " SUM(amount) OVER (ORDER BY order_date) AS cumulative_sum FROM " + t));
    }

    @Test
    public void dmQ8CasePivot() throws Exception {
        Assume.assumeNotNull(dm);
        String t = "jkit_q8_monthly_sales";
        safeDrop(dm, t);
        init(dm, new String[]{
                "CREATE TABLE " + t + " (product_id INT, month VARCHAR(10), sales DECIMAL(10,2))",
                "INSERT INTO " + t + " VALUES"
                        + "(1,'Jan',100.00),(1,'Feb',150.00),(1,'Mar',200.00),"
                        + "(2,'Jan',300.00),(2,'Feb',250.00)",
                "COMMIT"
        });
        // 两件产品 → 2 行；产品1 的 mar_sales=200、产品2 的 jan_sales=300
        int rows = queryRows(dm,
                "SELECT product_id,"
                        + " SUM(CASE WHEN month = 'Jan' THEN sales ELSE 0 END) AS jan_sales,"
                        + " SUM(CASE WHEN month = 'Feb' THEN sales ELSE 0 END) AS feb_sales,"
                        + " SUM(CASE WHEN month = 'Mar' THEN sales ELSE 0 END) AS mar_sales"
                        + " FROM " + t + " GROUP BY product_id");
        assertEquals(2, rows);
    }

    // ====================== helpers ======================

    private static Connection tryConnect(String url, String user, String pass, String driver) {
        try {
            Class.forName(driver);
            Connection c = DriverManager.getConnection(url, user, pass);
            c.setAutoCommit(true);
            System.out.println("[ok] connect " + url);
            return c;
        } catch (Exception e) {
            System.out.println("[skip] " + url + " -> " + e.getMessage());
            return null;
        }
    }

    private static void init(Connection c, String[] statements) throws Exception {
        try (Statement st = c.createStatement()) {
            for (String s : statements) {
                if (s == null || s.trim().isEmpty()) {
                    continue;
                }
                st.execute(s.trim());
            }
        }
    }

    /** 执行查询并返回行数（同时打印列数，便于排查）。 */
    private static int queryRows(Connection c, String sql) throws Exception {
        int rows = 0;
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            while (rs.next()) {
                rows++;
            }
            System.out.println("[exec] rows=" + rows + " cols=" + md.getColumnCount() + " :: " + sql.replace("\n", " "));
        }
        return rows;
    }

    /** Oracle/达梦无 IF EXISTS：尝试 DROP，表不存在则忽略。 */
    private static void safeDrop(Connection c, String table) {
        try (Statement st = c.createStatement()) {
            st.execute("DROP TABLE " + table);
        } catch (Exception ignored) {
            // ORA-00942 / 表不存在：首跑正常忽略
        }
    }

    /** teardown：删除所有 jkit_q 前缀表。 */
    private static void dropPrefixed(Connection c, String prefix) {
        if (c == null) {
            return;
        }
        List<String> tables = new ArrayList<String>();
        // 用数据库字典找出前缀表，避免盲删
        String dictSql;
        try {
            if (c == mysql) {
                dictSql = "SELECT table_name FROM information_schema.tables"
                        + " WHERE table_schema = DATABASE() AND table_name LIKE '" + prefix + "%'";
            } else if (c == pg) {
                dictSql = "SELECT table_name FROM information_schema.tables"
                        + " WHERE table_schema = CURRENT_SCHEMA AND table_name LIKE '" + prefix + "%'";
            } else {
                // Oracle / 达梦：ALL_TABLES
                dictSql = "SELECT table_name FROM user_tables WHERE table_name LIKE '" + prefix.toUpperCase() + "%'";
            }
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(dictSql)) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
        } catch (Exception e) {
            System.out.println("[teardown] 字典查询失败，跳过前缀清理: " + e.getMessage());
            return;
        }
        for (String t : tables) {
            try (Statement st = c.createStatement()) {
                st.execute("DROP TABLE " + t);
                System.out.println("[teardown] dropped " + t);
            } catch (Exception ignored) {
            }
        }
    }
}
