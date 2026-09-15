package com.alianga.test.sql;

import java.sql.*;

public class OracleJdbcTest {
    public static void main(String[] args) {
        String url = "jdbc:oracle:thin:@//192.168.1.197:2521/M_PDB";
        String user = "ZML";
        String password = "123456";

        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            System.out.println("连接成功！");

            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("WITH kpi AS (\n" +
                    "    SELECT 'ecommerce_gmv'                                               AS kpi_name,\n" +
                    "           ROUND(SUM(o.pay_amount), 2)                                   AS kpi_value,\n" +
                    "           'CNY'                                                         AS unit\n" +
                    "    FROM orders o\n" +
                    "    WHERE o.status = 'completed'\n" +
                    "      AND o.order_date >= (TRUNC(SYSDATE) - INTERVAL '30' DAY)\n" +
                    "    UNION ALL\n" +
                    "    SELECT 'ecommerce_orders', COUNT(*), 'cnt'\n" +
                    "    FROM orders o\n" +
                    "    WHERE o.status = 'completed' AND o.order_date >= (TRUNC(SYSDATE) - INTERVAL '30' DAY)\n" +
                    "    UNION ALL\n" +
                    "    SELECT 'ecommerce_active_cust', COUNT(DISTINCT o.customer_id), 'cnt'\n" +
                    "    FROM orders o\n" +
                    "    WHERE o.status = 'completed' AND o.order_date >= (TRUNC(SYSDATE) - INTERVAL '30' DAY)\n" +
                    "    UNION ALL\n" +
                    "    SELECT 'finance_deposit', ROUND(SUM(a.balance), 2), 'CNY'\n" +
                    "    FROM accounts a\n" +
                    "    UNION ALL\n" +
                    "    SELECT 'finance_npl_ratio',\n" +
                    "           ROUND(SUM(CASE WHEN l.status = 'overdue' THEN l.loan_amount ELSE 0 END) * 100.0\n" +
                    "                 / NULLIF(SUM(l.loan_amount), 0), 2), 'pct'\n" +
                    "    FROM loans l\n" +
                    "    UNION ALL\n" +
                    "    SELECT 'finance_txn_amount', ROUND(SUM(t.amount), 2), 'CNY'\n" +
                    "    FROM transactions t\n" +
                    "    WHERE t.txn_date >= (TRUNC(SYSDATE) - INTERVAL '30' DAY)\n" +
                    "    UNION ALL\n" +
                    "    SELECT 'hr_active_headcount', COUNT(*), 'cnt'\n" +
                    "    FROM employees e WHERE e.status = 'active'\n" +
                    "    UNION ALL\n" +
                    "    SELECT 'hr_monthly_cost', ROUND(SUM(p.gross_pay), 2), 'CNY'\n" +
                    "    FROM payroll p\n" +
                    "    WHERE p.pay_month = TO_CHAR(TRUNC(SYSDATE), 'YYYY-MM')\n" +
                    "    UNION ALL\n" +
                    "    SELECT 'hr_avg_perf', ROUND(AVG(pf.score), 2), 'score'\n" +
                    "    FROM performance pf\n" +
                    "    UNION ALL\n" +
                    "    SELECT 'inventory_stock_value',\n" +
                    "           ROUND(SUM(i.stock_qty * p.price), 2), 'CNY'\n" +
                    "    FROM inventory i\n" +
                    "    JOIN products p ON i.product_id = p.product_id\n" +
                    ")\n" +
                    "SELECT k.kpi_name, k.kpi_value, k.unit,\n" +
                    "       ROUND(k.kpi_value - AVG(k.kpi_value) OVER (), 2)                  AS vs_avg_gap,\n" +
                    "       CASE WHEN k.kpi_value > AVG(k.kpi_value) OVER () THEN 'above_avg'\n" +
                    "            ELSE 'below_avg' END AS vs_avg_flag\n" +
                    "FROM kpi k\n" +
                    "ORDER BY k.kpi_name");
            while (rs.next()) {
                System.out.println(rs.getString(1));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
