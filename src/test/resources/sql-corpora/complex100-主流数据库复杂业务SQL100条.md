# 主流数据库复杂业务 SQL 100 条

> 整理日期：2026-09-10
> 说明：每个数据库 20 条，共 100 条。统一使用如下业务表结构，SQL 可直接在对应数据库执行（需先建表造数）。

## 统一表结构约定

```sql
users(user_id, name, city, reg_date)                          -- 用户
orders(order_id, user_id, amount, status, order_time)         -- 订单
products(product_id, category, price)                         -- 商品
order_items(order_id, product_id, qty)                        -- 订单明细
emp(emp_id, name, dept_id, salary, manager_id, hire_date)     -- 员工
login_log(user_id, login_date)                                -- 登录日志
```

---

# 一、MySQL 8（共 20 条）

## 1. 分组取 Top3：每个用户金额最高的 3 笔订单
```sql
SELECT * FROM (
  SELECT o.*,
         ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY amount DESC) AS rn
  FROM orders o
) t WHERE rn <= 3;
```

## 2. 连续 3 天登录的用户
```sql
SELECT DISTINCT user_id FROM (
  SELECT user_id, login_date,
         DATE_SUB(login_date, INTERVAL ROW_NUMBER() OVER
           (PARTITION BY user_id ORDER BY login_date) DAY) AS grp
  FROM login_log
) t
GROUP BY user_id, grp
HAVING COUNT(*) >= 3;
```

## 3. 次日留存率（注册次日登录比例）
```sql
SELECT u.reg_date,
       COUNT(DISTINCT u.user_id) AS new_users,
       COUNT(DISTINCT l.user_id) AS retained,
       ROUND(COUNT(DISTINCT l.user_id) / COUNT(DISTINCT u.user_id), 4) AS retention
FROM users u
LEFT JOIN login_log l
  ON l.user_id = u.user_id AND l.login_date = u.reg_date + INTERVAL 1 DAY
GROUP BY u.reg_date;
```

## 4. 每日销售额与累计销售额
```sql
SELECT order_date, daily_amt,
       SUM(daily_amt) OVER (ORDER BY order_date) AS cum_amt
FROM (
  SELECT DATE(order_time) AS order_date, SUM(amount) AS daily_amt
  FROM orders GROUP BY DATE(order_time)
) t;
```

## 5. 7 日滑动平均销售额
```sql
SELECT order_date, daily_amt,
       AVG(daily_amt) OVER (
         ORDER BY order_date ROWS BETWEEN 6 PRECEDING AND CURRENT ROW
       ) AS ma7
FROM (
  SELECT DATE(order_time) AS order_date, SUM(amount) AS daily_amt
  FROM orders GROUP BY DATE(order_time)
) t;
```

## 6. 订单金额中位数
```sql
SELECT AVG(amount) AS median_amt FROM (
  SELECT amount,
         ROW_NUMBER() OVER (ORDER BY amount) AS rn,
         COUNT(*) OVER () AS cnt
  FROM orders
) t
WHERE rn IN (FLOOR((cnt + 1) / 2), FLOOR((cnt + 2) / 2));
```

## 7. 行列转换：月度品类销售额透视
```sql
SELECT category,
       SUM(CASE WHEN MONTH(order_time) = 1 THEN amount END) AS m1,
       SUM(CASE WHEN MONTH(order_time) = 2 THEN amount END) AS m2,
       SUM(CASE WHEN MONTH(order_time) = 3 THEN amount END) AS m3
FROM orders o JOIN order_items oi ON o.order_id = oi.order_id
              JOIN products p ON oi.product_id = p.product_id
GROUP BY category;
```

## 8. 去重取每个用户最新一笔订单
```sql
SELECT * FROM (
  SELECT o.*,
         ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY order_time DESC) AS rn
  FROM orders o
) t WHERE rn = 1;
```

## 9. 用户复购间隔（相邻两次订单天数）
```sql
SELECT user_id, order_time,
       DATEDIFF(order_time, LAG(order_time) OVER
         (PARTITION BY user_id ORDER BY order_time)) AS gap_days
FROM orders;
```

## 10. 品类销售额占比（组内百分比）
```sql
SELECT category, cat_amt,
       ROUND(cat_amt / SUM(cat_amt) OVER (), 4) AS pct
FROM (
  SELECT p.category, SUM(o.amount) AS cat_amt
  FROM orders o JOIN order_items oi ON o.order_id = oi.order_id
                JOIN products p ON oi.product_id = p.product_id
  GROUP BY p.category
) t;
```

## 11. 销售额日环比
```sql
SELECT order_date, daily_amt,
       ROUND(daily_amt / LAG(daily_amt) OVER (ORDER BY order_date) - 1, 4) AS dod
FROM (
  SELECT DATE(order_time) AS order_date, SUM(amount) AS daily_amt
  FROM orders GROUP BY DATE(order_time)
) t;
```

## 12. 补全连续日期（无订单日期补 0）
```sql
WITH RECURSIVE dates AS (
  SELECT MIN(DATE(order_time)) AS d, MAX(DATE(order_time)) AS maxd FROM orders
  UNION ALL
  SELECT d + INTERVAL 1 DAY, maxd FROM dates WHERE d < maxd
)
SELECT dates.d, COALESCE(SUM(o.amount), 0) AS amt
FROM dates LEFT JOIN orders o ON DATE(o.order_time) = dates.d
GROUP BY dates.d;
```

## 13. 会话切分：相邻行为间隔超 30 分钟算新会话
```sql
SELECT user_id, order_time,
       SUM(IF(TIMESTAMPDIFF(MINUTE, prev_t, order_time) > 30, 1, 0))
           OVER (PARTITION BY user_id ORDER BY order_time) AS session_id
FROM (
  SELECT user_id, order_time,
         LAG(order_time) OVER (PARTITION BY user_id ORDER BY order_time) AS prev_t
  FROM orders
) t;
```

## 14. 每日累计独立购买用户数（累计 UV）
```sql
SELECT order_date,
       SUM(cnt) OVER (ORDER BY order_date) AS cum_uv
FROM (
  SELECT DATE(order_time) AS order_date, COUNT(DISTINCT user_id) AS cnt
  FROM orders GROUP BY DATE(order_time)
) t;
```

## 15. 高于本部门平均工资的员工
```sql
SELECT e.* FROM emp e
JOIN (SELECT dept_id, AVG(salary) AS avg_sal FROM emp GROUP BY dept_id) d
  ON e.dept_id = d.dept_id AND e.salary > d.avg_sal;
```

## 16. 每个品类销量最高的商品
```sql
SELECT category, product_id, total_qty FROM (
  SELECT p.category, oi.product_id, SUM(oi.qty) AS total_qty,
         ROW_NUMBER() OVER (PARTITION BY p.category ORDER BY SUM(oi.qty) DESC) AS rn
  FROM order_items oi JOIN products p ON oi.product_id = p.product_id
  GROUP BY p.category, oi.product_id
) t WHERE rn = 1;
```

## 17. 行转列拼接：每个用户买过的品类列表
```sql
SELECT o.user_id, GROUP_CONCAT(DISTINCT p.category SEPARATOR ',') AS categories
FROM orders o JOIN order_items oi ON o.order_id = oi.order_id
              JOIN products p ON oi.product_id = p.product_id
GROUP BY o.user_id;
```

## 18. 最大连续未登录天数
```sql
SELECT user_id, MAX(gap - 1) AS max_offline_days FROM (
  SELECT user_id,
         DATEDIFF(login_date, LAG(login_date) OVER
           (PARTITION BY user_id ORDER BY login_date)) AS gap
  FROM login_log
) t GROUP BY user_id;
```

## 19. 排名与聚合混合：每个品类内金额占比 + 名次
```sql
SELECT category, user_id, user_amt,
       RANK() OVER (PARTITION BY category ORDER BY user_amt DESC) AS rnk,
       ROUND(user_amt / SUM(user_amt) OVER (PARTITION BY category), 4) AS pct
FROM (
  SELECT p.category, o.user_id, SUM(o.amount) AS user_amt
  FROM orders o JOIN order_items oi ON o.order_id = oi.order_id
                JOIN products p ON oi.product_id = p.product_id
  GROUP BY p.category, o.user_id
) t;
```

## 20. 同期群分析：注册月份用户第 N 月留存
```sql
SELECT DATE_FORMAT(reg_date, '%Y-%m') AS cohort,
       PERIOD_DIFF(DATE_FORMAT(login_date, '%Y%m'), DATE_FORMAT(reg_date, '%Y%m')) AS month_idx,
       COUNT(DISTINCT u.user_id) AS users
FROM users u JOIN login_log l ON u.user_id = l.user_id
GROUP BY cohort, month_idx
ORDER BY cohort, month_idx;
```

---

# 二、PostgreSQL（共 20 条）

## 1. 分组取 Top3 订单
```sql
SELECT * FROM (
  SELECT o.*,
         ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY amount DESC) AS rn
  FROM orders o
) t WHERE rn <= 3;
```

## 2. 连续 3 天登录
```sql
SELECT DISTINCT user_id FROM (
  SELECT user_id, login_date,
         login_date - (ROW_NUMBER() OVER
           (PARTITION BY user_id ORDER BY login_date))::int AS grp
  FROM login_log
) t GROUP BY user_id, grp HAVING COUNT(*) >= 3;
```

## 3. 次日留存率
```sql
SELECT u.reg_date,
       COUNT(DISTINCT u.user_id) AS new_users,
       COUNT(DISTINCT l.user_id) AS retained,
       ROUND(COUNT(DISTINCT l.user_id)::numeric / COUNT(DISTINCT u.user_id), 4) AS retention
FROM users u
LEFT JOIN login_log l
  ON l.user_id = u.user_id AND l.login_date = u.reg_date + 1
GROUP BY u.reg_date;
```

## 4. 累计销售额
```sql
SELECT order_date, daily_amt,
       SUM(daily_amt) OVER (ORDER BY order_date) AS cum_amt
FROM (
  SELECT order_time::date AS order_date, SUM(amount) AS daily_amt
  FROM orders GROUP BY 1
) t;
```

## 5. 7 日滑动平均
```sql
SELECT order_date, daily_amt,
       AVG(daily_amt) OVER (ORDER BY order_date ROWS BETWEEN 6 PRECEDING AND CURRENT ROW) AS ma7
FROM (
  SELECT order_time::date AS order_date, SUM(amount) AS daily_amt
  FROM orders GROUP BY 1
) t;
```

## 6. 中位数（专用聚合函数）
```sql
SELECT PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY amount) AS median_amt
FROM orders;
```

## 7. 透视：crosstab 或 FILTER 条件聚合
```sql
SELECT p.category,
       SUM(o.amount) FILTER (WHERE EXTRACT(MONTH FROM o.order_time) = 1) AS m1,
       SUM(o.amount) FILTER (WHERE EXTRACT(MONTH FROM o.order_time) = 2) AS m2,
       SUM(o.amount) FILTER (WHERE EXTRACT(MONTH FROM o.order_time) = 3) AS m3
FROM orders o JOIN order_items oi ON o.order_id = oi.order_id
              JOIN products p ON oi.product_id = p.product_id
GROUP BY p.category;
```

## 8. DISTINCT ON 取每个用户最新订单
```sql
SELECT DISTINCT ON (user_id) *
FROM orders ORDER BY user_id, order_time DESC;
```

## 9. 复购间隔
```sql
SELECT user_id, order_time,
       order_time - LAG(order_time) OVER (PARTITION BY user_id ORDER BY order_time) AS gap
FROM orders;
```

## 10. 品类占比
```sql
SELECT category, cat_amt,
       ROUND(cat_amt / SUM(cat_amt) OVER (), 4) AS pct
FROM (
  SELECT p.category, SUM(o.amount) AS cat_amt
  FROM orders o JOIN order_items oi ON o.order_id = oi.order_id
                JOIN products p ON oi.product_id = p.product_id
  GROUP BY p.category
) t;
```

## 11. 日环比
```sql
SELECT order_date, daily_amt,
       ROUND(daily_amt / LAG(daily_amt) OVER (ORDER BY order_date) - 1, 4) AS dod
FROM (
  SELECT order_time::date AS order_date, SUM(amount) AS daily_amt
  FROM orders GROUP BY 1
) t;
```

## 12. GENERATE_SERIES 补全日期
```sql
SELECT d.d, COALESCE(SUM(o.amount), 0) AS amt
FROM GENERATE_SERIES(
       (SELECT MIN(order_time)::date FROM orders),
       (SELECT MAX(order_time)::date FROM orders),
       INTERVAL '1 day') AS d
LEFT JOIN orders o ON o.order_time::date = d.d
GROUP BY d.d;
```

## 13. 会话切分
```sql
SELECT user_id, order_time,
       SUM(CASE WHEN order_time - prev_t > INTERVAL '30 minutes' THEN 1 ELSE 0 END)
           OVER (PARTITION BY user_id ORDER BY order_time) AS session_id
FROM (
  SELECT user_id, order_time,
         LAG(order_time) OVER (PARTITION BY user_id ORDER BY order_time) AS prev_t
  FROM orders
) t;
```

## 14. 递归 CTE 查员工汇报层级
```sql
WITH RECURSIVE sub AS (
  SELECT emp_id, name, manager_id, salary, 1 AS lvl FROM emp WHERE emp_id = 1001
  UNION ALL
  SELECT e.emp_id, e.name, e.manager_id, e.salary, s.lvl + 1
  FROM emp e JOIN sub s ON e.manager_id = s.emp_id
)
SELECT * FROM sub;
```

## 15. LATERAL 查每个用户金额最高的 2 笔订单
```sql
SELECT u.user_id, t.*
FROM users u
CROSS JOIN LATERAL (
  SELECT order_id, amount FROM orders o
  WHERE o.user_id = u.user_id
  ORDER BY amount DESC LIMIT 2
) t;
```

## 16. 品类销量 Top1 商品
```sql
SELECT category, product_id, total_qty FROM (
  SELECT p.category, oi.product_id, SUM(oi.qty) AS total_qty,
         ROW_NUMBER() OVER (PARTITION BY p.category ORDER BY SUM(oi.qty) DESC) AS rn
  FROM order_items oi JOIN products p ON oi.product_id = p.product_id
  GROUP BY 1, 2
) t WHERE rn = 1;
```

## 17. 数组拼接：STRING_AGG
```sql
SELECT o.user_id, STRING_AGG(DISTINCT p.category, ',') AS categories
FROM orders o JOIN order_items oi ON o.order_id = oi.order_id
              JOIN products p ON oi.product_id = p.product_id
GROUP BY o.user_id;
```

## 18. 最大连续未登录天数
```sql
SELECT user_id, MAX(gap - 1) AS max_offline_days FROM (
  SELECT user_id,
         login_date - LAG(login_date) OVER (PARTITION BY user_id ORDER BY login_date) AS gap
  FROM login_log
) t GROUP BY user_id;
```

## 19. 分组占比 + 名次
```sql
SELECT category, user_id, user_amt,
       RANK() OVER (PARTITION BY category ORDER BY user_amt DESC) AS rnk,
       ROUND(user_amt / SUM(user_amt) OVER (PARTITION BY category), 4) AS pct
FROM (
  SELECT p.category, o.user_id, SUM(o.amount) AS user_amt
  FROM orders o JOIN order_items oi ON o.order_id = oi.order_id
                JOIN products p ON oi.product_id = p.product_id
  GROUP BY 1, 2
) t;
```

## 20. 占比与累计占比（帕累托分析）
```sql
SELECT category, cat_amt,
       ROUND(cat_amt / SUM(cat_amt) OVER (), 4) AS pct,
       ROUND(SUM(cat_amt) OVER (ORDER BY cat_amt DESC) / SUM(cat_amt) OVER (), 4) AS cum_pct
FROM (
  SELECT p.category, SUM(o.amount) AS cat_amt
  FROM orders o JOIN order_items oi ON o.order_id = oi.order_id
                JOIN products p ON oi.product_id = p.product_id
  GROUP BY 1
) t;
```

---

# 三、Oracle（共 20 条）

## 1. 分组取 Top3（分析函数）
```sql
SELECT * FROM (
  SELECT o.*,
         ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY amount DESC) AS rn
  FROM orders o
) WHERE rn <= 3;
```

## 2. 连续 3 天登录
```sql
SELECT DISTINCT user_id FROM (
  SELECT user_id, login_date,
         login_date - ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY login_date) AS grp
  FROM login_log
) GROUP BY user_id, grp HAVING COUNT(*) >= 3;
```

## 3. 次日留存率（DECODE 计数）
```sql
SELECT u.reg_date,
       COUNT(DISTINCT u.user_id) AS new_users,
       COUNT(DISTINCT l.user_id) AS retained,
       ROUND(COUNT(DISTINCT l.user_id) / COUNT(DISTINCT u.user_id), 4) AS retention
FROM users u
LEFT JOIN login_log l
  ON l.user_id = u.user_id AND l.login_date = u.reg_date + 1
GROUP BY u.reg_date;
```

## 4. 累计销售额（RANGE 窗口）
```sql
SELECT order_date, daily_amt,
       SUM(daily_amt) OVER (ORDER BY order_date RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS cum_amt
FROM (
  SELECT TRUNC(order_time) AS order_date, SUM(amount) AS daily_amt
  FROM orders GROUP BY TRUNC(order_time)
);
```

## 5. 7 日滑动平均
```sql
SELECT order_date, daily_amt,
       AVG(daily_amt) OVER (ORDER BY order_date ROWS BETWEEN 6 PRECEDING AND CURRENT ROW) AS ma7
FROM (
  SELECT TRUNC(order_time) AS order_date, SUM(amount) AS daily_amt
  FROM orders GROUP BY TRUNC(order_time)
);
```

## 6. 中位数（MEDIAN 函数）
```sql
SELECT MEDIAN(amount) AS median_amt FROM orders;
```

## 7. 透视（DECODE 行列转换）
```sql
SELECT p.category,
       SUM(DECODE(TO_CHAR(o.order_time, 'MM'), '01', o.amount)) AS m1,
       SUM(DECODE(TO_CHAR(o.order_time, 'MM'), '02', o.amount)) AS m2,
       SUM(DECODE(TO_CHAR(o.order_time, 'MM'), '03', o.amount)) AS m3
FROM orders o JOIN order_items oi ON o.order_id = oi.order_id
              JOIN products p ON oi.product_id = p.product_id
GROUP BY p.category;
```

## 8. 每个用户最新订单（ROW_NUMBER）
```sql
SELECT * FROM (
  SELECT o.*,
         ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY order_time DESC) AS rn
  FROM orders o
) WHERE rn = 1;
```

## 9. 复购间隔
```sql
SELECT user_id, order_time,
       order_time - LAG(order_time) OVER (PARTITION BY user_id ORDER BY order_time) AS gap_days
FROM orders;
```

## 10. 品类占比
```sql
SELECT category, cat_amt,
       ROUND(cat_amt / SUM(cat_amt) OVER (), 4) AS pct
FROM (
  SELECT p.category, SUM(o.amount) AS cat_amt
  FROM orders o JOIN order_items oi ON o.order_id = oi.order_id
                JOIN products p ON oi.product_id = p.product_id
  GROUP BY p.category
);
```

## 11. 日环比
```sql
SELECT order_date, daily_amt,
       ROUND(daily_amt / LAG(daily_amt) OVER (ORDER BY order_date) - 1, 4) AS dod
FROM (
  SELECT TRUNC(order_time) AS order_date, SUM(amount) AS daily_amt
  FROM orders GROUP BY TRUNC(order_time)
);
```

## 12. ROWNUM 分页（第 21~40 条）
```sql
SELECT * FROM (
  SELECT t.*, ROWNUM AS rn FROM (
    SELECT * FROM orders ORDER BY order_time DESC
  ) t WHERE ROWNUM <= 40
) WHERE rn > 20;
```

## 13. CONNECT BY 层级查询（员工树）
```sql
SELECT LPAD(' ', 2 * (LEVEL - 1)) || name AS org_chart, emp_id, manager_id, salary
FROM emp
START WITH manager_id IS NULL
CONNECT BY PRIOR emp_id = manager_id;
```

## 14. 会话切分
```sql
SELECT user_id, order_time,
       SUM(CASE WHEN order_time - prev_t > INTERVAL '30' MINUTE THEN 1 ELSE 0 END)
           OVER (PARTITION BY user_id ORDER BY order_time) AS session_id
FROM (
  SELECT user_id, order_time,
         LAG(order_time) OVER (PARTITION BY user_id ORDER BY order_time) AS prev_t
  FROM orders
);
```

## 15. KEEP 取每组最值对应行（每组最贵订单金额及其订单号）
```sql
SELECT user_id,
       MAX(order_id) KEEP (DENSE_RANK FIRST ORDER BY amount DESC) AS top_order,
       MAX(amount) AS top_amt
FROM orders GROUP BY user_id;
```

## 16. MERGE INTO 订单汇总表增量更新
```sql
MERGE INTO user_order_sum s
USING (
  SELECT user_id, SUM(amount) AS amt FROM orders
  WHERE order_time >= DATE '2026-09-01' GROUP BY user_id
) src
ON (s.user_id = src.user_id)
WHEN MATCHED THEN UPDATE SET s.total_amt = s.total_amt + src.amt
WHEN NOT MATCHED THEN INSERT (user_id, total_amt) VALUES (src.user_id, src.amt);
```

## 17. 最大连续未登录天数
```sql
SELECT user_id, MAX(gap - 1) AS max_offline_days FROM (
  SELECT user_id,
         login_date - LAG(login_date) OVER (PARTITION BY user_id ORDER BY login_date) AS gap
  FROM login_log
) GROUP BY user_id;
```

## 18. LISTAGG 行转列
```sql
SELECT o.user_id, LISTAGG(p.category, ',') WITHIN GROUP (ORDER BY p.category) AS categories
FROM orders o JOIN order_items oi ON o.order_id = oi.order_id
              JOIN products p ON oi.product_id = p.product_id
GROUP BY o.user_id;
```

## 19. 移动累计 UV
```sql
SELECT order_date, cnt,
       SUM(cnt) OVER (ORDER BY order_date) AS cum_uv
FROM (
  SELECT TRUNC(order_time) AS order_date, COUNT(DISTINCT user_id) AS cnt
  FROM orders GROUP BY TRUNC(order_time)
);
```

## 20. 分组占比 + 名次 + 累计占比
```sql
SELECT category, user_id, user_amt,
       RANK() OVER (PARTITION BY category ORDER BY user_amt DESC) AS rnk,
       ROUND(user_amt / SUM(user_amt) OVER (PARTITION BY category), 4) AS pct,
       ROUND(SUM(user_amt) OVER (PARTITION BY category ORDER BY user_amt DESC
              ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)
             / SUM(user_amt) OVER (PARTITION BY category), 4) AS cum_pct
FROM (
  SELECT p.category, o.user_id, SUM(o.amount) AS user_amt
  FROM orders o JOIN order_items oi ON o.order_id = oi.order_id
                JOIN products p ON oi.product_id = p.product_id
  GROUP BY p.category, o.user_id
);
```

---

# 四、SQL Server（共 20 条）

## 1. 分组取 Top3
```sql
SELECT * FROM (
  SELECT o.*,
         ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY amount DESC) AS rn
  FROM orders o
) t WHERE rn <= 3;
```

## 2. 连续 3 天登录
```sql
SELECT DISTINCT user_id FROM (
  SELECT user_id, login_date,
         DATEADD(DAY, -ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY login_date), login_date) AS grp
  FROM login_log
) t GROUP BY user_id, grp HAVING COUNT(*) >= 3;
```

## 3. 次日留存率
```sql
SELECT u.reg_date,
       COUNT(DISTINCT u.user_id) AS new_users,
       COUNT(DISTINCT l.user_id) AS retained,
       CAST(COUNT(DISTINCT l.user_id) AS DECIMAL(10,4)) / COUNT(DISTINCT u.user_id) AS retention
FROM users u
LEFT JOIN login_log l
  ON l.user_id = u.user_id AND l.login_date = DATEADD(DAY, 1, u.reg_date)
GROUP BY u.reg_date;
```

## 4. 累计销售额
```sql
SELECT order_date, daily_amt,
       SUM(daily_amt) OVER (ORDER BY order_date) AS cum_amt
FROM (
  SELECT CAST(order_time AS DATE) AS order_date, SUM(amount) AS daily_amt
  FROM orders GROUP BY CAST(order_time AS DATE)
) t;
```

## 5. 7 日滑动平均
```sql
SELECT order_date, daily_amt,
       AVG(daily_amt) OVER (ORDER BY order_date ROWS BETWEEN 6 PRECEDING AND CURRENT ROW) AS ma7
FROM (
  SELECT CAST(order_time AS DATE) AS order_date, SUM(amount) AS daily_amt
  FROM orders GROUP BY CAST(order_time AS DATE)
) t;
```

## 6. 中位数（PERCENTILE_CONT）
```sql
SELECT DISTINCT PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY amount)
       OVER () AS median_amt
FROM orders;
```

## 7. PIVOT 行列转换
```sql
SELECT category, [1] AS m1, [2] AS m2, [3] AS m3
FROM (
  SELECT p.category, MONTH(o.order_time) AS m, o.amount
  FROM orders o JOIN order_items oi ON o.order_id = oi.order_id
                JOIN products p ON oi.product_id = p.product_id
) src
PIVOT (SUM(amount) FOR m IN ([1],[2],[3])) pvt;
```

## 8. TOP 1 WITH TIES 取最新订单
```sql
SELECT TOP 1 WITH TIES *
FROM orders ORDER BY ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY order_time DESC);
```

## 9. 复购间隔（DATEDIFF）
```sql
SELECT user_id, order_time,
       DATEDIFF(DAY, LAG(order_time) OVER (PARTITION BY user_id ORDER BY order_time), order_time) AS gap_days
FROM orders;
```

## 10. 品类占比
```sql
SELECT category, cat_amt,
       CAST(cat_amt AS DECIMAL(18,4)) / SUM(cat_amt) OVER () AS pct
FROM (
  SELECT p.category, SUM(o.amount) AS cat_amt
  FROM orders o JOIN order_items oi ON o.order_id = oi.order_id
                JOIN products p ON oi.product_id = p.product_id
  GROUP BY p.category
) t;
```

## 11. 日环比
```sql
SELECT order_date, daily_amt,
       CAST(daily_amt AS DECIMAL(18,4)) / LAG(daily_amt) OVER (ORDER BY order_date) - 1 AS dod
FROM (
  SELECT CAST(order_time AS DATE) AS order_date, SUM(amount) AS daily_amt
  FROM orders GROUP BY CAST(order_time AS DATE)
) t;
```

## 12. 递归 CTE 生成日期序列并补全
```sql
WITH dates AS (
  SELECT CAST(MIN(CAST(order_time AS DATE)) AS DATE) AS d,
         CAST(MAX(CAST(order_time AS DATE)) AS DATE) AS maxd
  FROM orders
  UNION ALL
  SELECT DATEADD(DAY, 1, d), maxd FROM dates WHERE d < maxd
)
SELECT dates.d, ISNULL(SUM(o.amount), 0) AS amt
FROM dates LEFT JOIN orders o ON CAST(o.order_time AS DATE) = dates.d
GROUP BY dates.d OPTION (MAXRECURSION 10000);
```

## 13. 会话切分（DATEDIFF 分钟）
```sql
SELECT user_id, order_time,
       SUM(CASE WHEN DATEDIFF(MINUTE, prev_t, order_time) > 30 THEN 1 ELSE 0 END)
           OVER (PARTITION BY user_id ORDER BY order_time) AS session_id
FROM (
  SELECT user_id, order_time,
         LAG(order_time) OVER (PARTITION BY user_id ORDER BY order_time) AS prev_t
  FROM orders
) t;
```

## 14. 递归 CTE 员工层级
```sql
WITH sub AS (
  SELECT emp_id, name, manager_id, salary, 1 AS lvl FROM emp WHERE emp_id = 1001
  UNION ALL
  SELECT e.emp_id, e.name, e.manager_id, e.salary, s.lvl + 1
  FROM emp e JOIN sub s ON e.manager_id = s.emp_id
)
SELECT * FROM sub;
```

## 15. CROSS APPLY 每用户金额最高的 2 笔
```sql
SELECT u.user_id, t.*
FROM users u
CROSS APPLY (
  SELECT TOP 2 order_id, amount FROM orders o
  WHERE o.user_id = u.user_id
  ORDER BY amount DESC
) t;
```

## 16. 品类销量 Top1 商品
```sql
SELECT category, product_id, total_qty FROM (
  SELECT p.category, oi.product_id, SUM(oi.qty) AS total_qty,
         ROW_NUMBER() OVER (PARTITION BY p.category ORDER BY SUM(oi.qty) DESC) AS rn
  FROM order_items oi JOIN products p ON oi.product_id = p.product_id
  GROUP BY p.category, oi.product_id
) t WHERE rn = 1;
```

## 17. STRING_AGG 拼接品类
```sql
SELECT o.user_id, STRING_AGG(p.category, ',') AS categories
FROM orders o JOIN order_items oi ON o.order_id = oi.order_id
              JOIN products p ON oi.product_id = p.product_id
GROUP BY o.user_id;
```

## 18. 最大连续未登录天数
```sql
SELECT user_id, MAX(gap - 1) AS max_offline_days FROM (
  SELECT user_id,
         DATEDIFF(DAY, LAG(login_date) OVER (PARTITION BY user_id ORDER BY login_date), login_date) AS gap
  FROM login_log
) t GROUP BY user_id;
```

## 19. 分组占比 + 名次
```sql
SELECT category, user_id, user_amt,
       RANK() OVER (PARTITION BY category ORDER BY user_amt DESC) AS rnk,
       CAST(user_amt AS DECIMAL(18,4)) / SUM(user_amt) OVER (PARTITION BY category) AS pct
FROM (
  SELECT p.category, o.user_id, SUM(o.amount) AS user_amt
  FROM orders o JOIN order_items oi ON o.order_id = oi.order_id
                JOIN products p ON oi.product_id = p.product_id
  GROUP BY p.category, o.user_id
) t;
```

## 20. 同比（同月去年对比）
```sql
SELECT ym, amt,
       LAG(amt, 12) OVER (ORDER BY ym) AS amt_last_year,
       CAST(amt AS DECIMAL(18,4)) / LAG(amt, 12) OVER (ORDER BY ym) - 1 AS yoy
FROM (
  SELECT FORMAT(order_time, 'yyyy-MM') AS ym, SUM(amount) AS amt
  FROM orders GROUP BY FORMAT(order_time, 'yyyy-MM')
) t;
```

---

# 五、Hive（共 20 条）

## 1. 分组取 Top3
```sql
SELECT * FROM (
  SELECT o.*,
         ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY amount DESC) AS rn
  FROM orders o
) t WHERE rn <= 3;
```

## 2. 连续 3 天登录
```sql
SELECT DISTINCT user_id FROM (
  SELECT user_id,
         DATE_SUB(login_date, ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY login_date)) AS grp
  FROM login_log
) t GROUP BY user_id, grp HAVING COUNT(*) >= 3;
```

## 3. 次日留存率（按 dt 分区）
```sql
SELECT a.dt,
       COUNT(DISTINCT a.user_id) AS dau,
       COUNT(DISTINCT b.user_id) AS retained,
       ROUND(COUNT(DISTINCT b.user_id) / COUNT(DISTINCT a.user_id), 4) AS retention
FROM login_log a
LEFT JOIN login_log b
  ON a.user_id = b.user_id AND b.dt = DATE_ADD(a.dt, 1)
GROUP BY a.dt;
```

## 4. 累计销售额
```sql
SELECT dt, daily_amt,
       SUM(daily_amt) OVER (ORDER BY dt) AS cum_amt
FROM (
  SELECT dt, SUM(amount) AS daily_amt
  FROM orders GROUP BY dt
) t;
```

## 5. 7 日滑动平均
```sql
SELECT dt, daily_amt,
       AVG(daily_amt) OVER (ORDER BY dt ROWS BETWEEN 6 PRECEDING AND CURRENT ROW) AS ma7
FROM (
  SELECT dt, SUM(amount) AS daily_amt FROM orders GROUP BY dt
) t;
```

## 6. 中位数（分位数函数）
```sql
SELECT PERCENTILE_APPROX(amount, 0.5) AS median_amt FROM orders;
```

## 7. 条件聚合透视
```sql
SELECT p.category,
       SUM(CASE WHEN MONTH(o.order_time) = 1 THEN o.amount END) AS m1,
       SUM(CASE WHEN MONTH(o.order_time) = 2 THEN o.amount END) AS m2,
       SUM(CASE WHEN MONTH(o.order_time) = 3 THEN o.amount END) AS m3
FROM orders o JOIN order_items oi ON o.order_id = oi.order_id
              JOIN products p ON oi.product_id = p.product_id
GROUP BY p.category;
```

## 8. 每用户最新订单
```sql
SELECT * FROM (
  SELECT o.*,
         ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY order_time DESC) AS rn
  FROM orders o
) t WHERE rn = 1;
```

## 9. 复购间隔
```sql
SELECT user_id, order_time,
       DATEDIFF(order_time, LAG(order_time) OVER (PARTITION BY user_id ORDER BY order_time)) AS gap_days
FROM orders;
```

## 10. 品类占比
```sql
SELECT category, cat_amt,
       ROUND(cat_amt / SUM(cat_amt) OVER (), 4) AS pct
FROM (
  SELECT p.category, SUM(o.amount) AS cat_amt
  FROM orders o JOIN order_items oi ON o.order_id = oi.order_id
                JOIN products p ON oi.product_id = p.product_id
  GROUP BY p.category
) t;
```

## 11. 日环比
```sql
SELECT dt, daily_amt,
       ROUND(daily_amt / LAG(daily_amt) OVER (ORDER BY dt) - 1, 4) AS dod
FROM (
  SELECT dt, SUM(amount) AS daily_amt FROM orders GROUP BY dt
) t;
```

## 12. LATERAL VIEW explode 展开订单商品数组
```sql
SELECT o.order_id, p_id, qty
FROM orders o
LATERAL VIEW explode(item_ids) items AS p_id, qty;
```

## 13. collect_set / collect_list 行转列
```sql
SELECT o.user_id, COLLECT_SET(p.category) AS categories
FROM orders o JOIN order_items oi ON o.order_id = oi.order_id
              JOIN products p ON oi.product_id = p.product_id
GROUP BY o.user_id;
```

## 14. 会话切分
```sql
SELECT user_id, order_time,
       SUM(CASE WHEN unix_timestamp(order_time) - unix_timestamp(prev_t) > 1800 THEN 1 ELSE 0 END)
           OVER (PARTITION BY user_id ORDER BY order_time) AS session_id
FROM (
  SELECT user_id, order_time,
         LAG(order_time) OVER (PARTITION BY user_id ORDER BY order_time) AS prev_t
  FROM orders
) t;
```

## 15. 累计独立用户数（COUNT DISTINCT 窗口）
```sql
SELECT dt,
       SUM(cnt) OVER (ORDER BY dt) AS cum_uv
FROM (
  SELECT dt, COUNT(DISTINCT user_id) AS cnt FROM orders GROUP BY dt
) t;
```

## 16. 分组 TopN 输出（DISTRIBUTE + SORT 优化写法）
```sql
SELECT category, user_id, user_amt FROM (
  SELECT p.category, o.user_id, SUM(o.amount) AS user_amt,
         ROW_NUMBER() OVER (PARTITION BY p.category ORDER BY SUM(o.amount) DESC) AS rn
  FROM orders o JOIN order_items oi ON o.order_id = oi.order_id
                JOIN products p ON oi.product_id = p.product_id
  GROUP BY p.category, o.user_id
) t WHERE rn <= 3
DISTRIBUTE BY category SORT BY category, user_amt DESC;
```

## 17. 最大连续未登录天数
```sql
SELECT user_id, MAX(gap - 1) AS max_offline_days FROM (
  SELECT user_id,
         DATEDIFF(login_date, LAG(login_date) OVER (PARTITION BY user_id ORDER BY login_date)) AS gap
  FROM login_log
) t GROUP BY user_id;
```

## 18. 同期群分析（注册月 × 活跃月）
```sql
SELECT SUBSTR(u.reg_date, 1, 7) AS cohort,
       MONTHS_BETWEEN(SUBSTR(l.dt, 1, 7), SUBSTR(u.reg_date, 1, 7)) AS month_idx,
       COUNT(DISTINCT u.user_id) AS users
FROM users u JOIN login_log l ON u.user_id = l.user_id
GROUP BY SUBSTR(u.reg_date, 1, 7),
         MONTHS_BETWEEN(SUBSTR(l.dt, 1, 7), SUBSTR(u.reg_date, 1, 7));
```

## 19. 间隔分组：用户连续活跃区间
```sql
SELECT user_id, MIN(login_date) AS start_date, MAX(login_date) AS end_date,
       COUNT(*) AS days FROM (
  SELECT user_id, login_date,
         SUM(CASE WHEN DATEDIFF(login_date, prev_d) > 1 THEN 1 ELSE 0 END)
             OVER (PARTITION BY user_id ORDER BY login_date) AS seg
  FROM (
    SELECT user_id, login_date,
           LAG(login_date) OVER (PARTITION BY user_id ORDER BY login_date) AS prev_d
    FROM login_log
  ) t1
) t2 GROUP BY user_id, seg;
```

## 20. 分组占比 + 名次 + 累计占比
```sql
SELECT category, user_id, user_amt,
       RANK() OVER (PARTITION BY category ORDER BY user_amt DESC) AS rnk,
       ROUND(user_amt / SUM(user_amt) OVER (PARTITION BY category), 4) AS pct,
       ROUND(SUM(user_amt) OVER (PARTITION BY category ORDER BY user_amt DESC
              ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)
             / SUM(user_amt) OVER (PARTITION BY category), 4) AS cum_pct
FROM (
  SELECT p.category, o.user_id, SUM(o.amount) AS user_amt
  FROM orders o JOIN order_items oi ON o.order_id = oi.order_id
                JOIN products p ON oi.product_id = p.product_id
  GROUP BY p.category, o.user_id
) t;
```

---

## 附：五大数据库方言差异速查

| 场景 | MySQL 8 | PostgreSQL | Oracle | SQL Server | Hive |
|---|---|---|---|---|---|
| 分组TopN | ROW_NUMBER 子查询 | 同左 | 同左 | TOP 1 WITH TIES | 同左 |
| 取每组最新1条 | ROW_NUMBER=1 | DISTINCT ON | ROW_NUMBER=1 | TOP 1 WITH TIES | ROW_NUMBER=1 |
| 分页 | LIMIT n, m | LIMIT/OFFSET | ROWNUM | OFFSET FETCH/TOP | LIMIT |
| 中位数 | 窗口法 | PERCENTILE_CONT | MEDIAN | PERCENTILE_CONT | PERCENTILE_APPROX |
| 日期加减 | DATE_ADD/DATE_SUB | +/- interval | +N | DATEADD | DATE_ADD |
| 日期补全 | 递归CTE | GENERATE_SERIES | CONNECT BY LEVEL | 递归CTE | posexplode/维表 |
| 递归查询 | 递归CTE | 递归CTE | CONNECT BY | 递归CTE | 不支持 |
| 行转列拼接 | GROUP_CONCAT | STRING_AGG | LISTAGG | STRING_AGG | COLLECT_SET |
| 列转行 | UNION ALL | LATERAL | UNION ALL | CROSS APPLY | LATERAL VIEW explode |
| 层级树 | 不支持 | 递归CTE | CONNECT BY | 递归CTE | 不支持 |
| 条件聚合 | CASE WHEN | FILTER | DECODE/CASE | CASE WHEN | CASE WHEN |

### 各主流数据库的核心“复杂业务 SQL”范式对比

不同的数据库在复杂业务处理上各有侧重，以下列举了几类最具代表性的复杂业务场景范式：

#### 1. MySQL 8.0+ / PostgreSQL：复杂用户月度留存与多维漏斗（CTE + 窗口函数）
* **业务场景**：电商/App 分析不同注册周期的用户在后续各月的复购留存率（Cohort Analysis）。

```sql
WITH user_first_order AS (
    -- 用户首次下单周期
    SELECT 
        user_id,
        DATE_TRUNC('month', order_time) AS cohort_month
    FROM orders
    WHERE order_status = 'COMPLETED'
    GROUP BY user_id, DATE_TRUNC('month', order_time)
),
user_activities AS (
    -- 用户后续所有活跃月份
    SELECT 
        o.user_id,
        u.cohort_month,
        DATE_TRUNC('month', o.order_time) AS activity_month,
        (EXTRACT(YEAR FROM o.order_time) - EXTRACT(YEAR FROM u.cohort_month)) * 12 +
        (EXTRACT(MONTH FROM o.order_time) - EXTRACT(MONTH FROM u.cohort_month)) AS month_diff
    FROM orders o
    JOIN user_first_order u ON o.user_id = u.user_id
    WHERE o.order_status = 'COMPLETED'
),
cohort_size AS (
    -- 每期初始总人数
    SELECT cohort_month, COUNT(DISTINCT user_id) AS total_users
    FROM user_first_order
    GROUP BY cohort_month
)
SELECT 
    a.cohort_month,
    cs.total_users,
    a.month_diff,
    COUNT(DISTINCT a.user_id) AS active_users,
    ROUND(COUNT(DISTINCT a.user_id)::numeric / cs.total_users * 100, 2) AS retention_rate
FROM user_activities a
JOIN cohort_size cs ON a.cohort_month = cs.cohort_month
GROUP BY a.cohort_month, cs.total_users, a.month_diff
ORDER BY a.cohort_month, a.month_diff;
```

#### 2. Oracle：复杂组织层级/物料清单穿透（BOM 展开与多级折旧）
* **业务场景**：制造型企业物料清单（BOM）多级展开计算成本，结合分析函数做累进分摊。

```sql
WITH bom_hierarchy (part_id, parent_part_id, qty, cost, hierarchy_level, path) AS (
    -- 锚点：顶层产品
    SELECT 
        p.part_id,
        p.parent_part_id,
        p.unit_qty AS qty,
        p.base_cost AS cost,
        1 AS hierarchy_level,
        TO_CHAR(p.part_id) AS path
    FROM part_relationships p
    WHERE p.parent_part_id IS NULL
    UNION ALL
    -- 递归展开子级零件
    SELECT 
        c.part_id,
        c.parent_part_id,
        c.unit_qty * b.qty,
        c.base_cost,
        b.hierarchy_level + 1,
        b.path || '->' || TO_CHAR(c.part_id)
    FROM part_relationships c
    JOIN bom_hierarchy b ON c.parent_part_id = b.part_id
    WHERE b.hierarchy_level < 10 -- 防止循环
)
SELECT 
    path,
    hierarchy_level,
    part_id,
    qty,
    cost,
    (qty * cost) AS extended_cost,
    SUM(qty * cost) OVER (PARTITION BY SUBSTR(path, 1, INSTR(path, '->') - 1)) AS total_root_cost,
    DENSE_RANK() OVER (ORDER BY (qty * cost) DESC) AS cost_impact_rank
FROM bom_hierarchy
ORDER BY path;
```

#### 3. SQL Server：跨维度进销存差额对账（MERGE + OUTPUT + 递归期初结存）
* **业务场景**：处理高并发库存过账、期初/期末移动加权平均成本核算。

```sql
WITH daily_inventory_movement AS (
    SELECT 
        warehouse_id,
        sku_id,
        trans_date,
        SUM(CASE WHEN trans_type = 'IN' THEN qty ELSE 0 END) AS in_qty,
        SUM(CASE WHEN trans_type = 'OUT' THEN qty ELSE 0 END) AS out_qty,
        SUM(CASE WHEN trans_type = 'IN' THEN total_cost ELSE 0 END) AS in_cost
    FROM stock_transactions
    WHERE trans_date BETWEEN '2025-01-01' AND '2025-01-31'
    GROUP BY warehouse_id, sku_id, trans_date
),
cumulative_balance AS (
    SELECT 
        warehouse_id,
        sku_id,
        trans_date,
        in_qty,
        out_qty,
        SUM(in_qty - out_qty) OVER(
            PARTITION BY warehouse_id, sku_id 
            ORDER BY trans_date 
            ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
        ) AS closing_stock_qty
    FROM daily_inventory_movement
)
SELECT 
    cb.warehouse_id,
    cb.sku_id,
    cb.trans_date,
    cb.closing_stock_qty,
    p.reorder_level,
    CASE 
        WHEN cb.closing_stock_qty < p.safety_stock THEN 'CRITICAL'
        WHEN cb.closing_stock_qty < p.reorder_level THEN 'WARNING'
        ELSE 'NORMAL'
    END AS inventory_status
FROM cumulative_balance cb
JOIN product_master p ON cb.sku_id = p.sku_id
WHERE cb.closing_stock_qty < p.reorder_level;
```

#### 4. ClickHouse：超大规模事件漏斗与滑动归因分析
* **业务场景**：亿级行为日志的高性能会话切分与多步转化漏斗。

```sql
SELECT 
    toStartOfDay(event_time) AS report_date,
    windowFunnel(86400)(
        toDateTime(event_time),
        event_name = 'view_product',
        event_name = 'add_to_cart',
        event_name = 'start_checkout',
        event_name = 'pay_success'
    ) AS funnel_step,
    count(DISTINCT user_id) AS reached_users
FROM events_log
WHERE event_time >= '2025-01-01' AND event_time < '2025-02-01'
GROUP BY report_date, funnel_step
ORDER BY report_date, funnel_step;
```

---

# 各主流数据库复杂业务 SQL 种子集

先坦白一点：**4 个库 × 300 条 = 1200 条完整 SQL 无法在一次回复中输出**（总计数万行）。我给你的是**可直接运行的高质量种子集**：每库 15 条最具代表性的复杂业务查询，覆盖窗口函数、递归、漏斗、RFM、留存、同比、会话化、PIVOT 等，并标注每个库的方言特性。文末给出**扩展到 300 条的方法**。


## 通用电商 Schema（所有示例基于此）

```sql
customers(customer_id, name, city, register_date)
orders(order_id, customer_id, order_date, status, total_amount)
order_items(item_id, order_id, product_id, quantity, price)
products(product_id, name, category_id, price, stock)
categories(category_id, name, parent_id)
payments(payment_id, order_id, pay_date, amount, method)
events(event_id, customer_id, event, event_time)  -- view/cart/pay
```


## 一、MySQL 8.0（15 条）

**1. 每个分类销售额 Top 3 商品**
```sql
WITH ranked AS (
  SELECT c.name AS category, p.name AS product,
         SUM(oi.quantity*oi.price) AS sales,
         ROW_NUMBER() OVER (PARTITION BY c.category_id
                            ORDER BY SUM(oi.quantity*oi.price) DESC) rn
  FROM order_items oi
  JOIN orders o   ON o.order_id=oi.order_id AND o.status='completed'
  JOIN products p ON p.product_id=oi.product_id
  JOIN categories c ON c.category_id=p.category_id
  GROUP BY c.category_id, c.name, p.product_id, p.name
)
SELECT * FROM ranked WHERE rn<=3;
```

**2. 连续 3 天以上下单的用户（Gap-and-Islands）**
```sql
WITH d AS (SELECT DISTINCT customer_id, DATE(order_date) dt FROM orders),
g AS (
  SELECT customer_id, dt,
         DATE_SUB(dt, INTERVAL ROW_NUMBER() OVER (PARTITION BY customer_id ORDER BY dt) DAY) grp
  FROM d
)
SELECT customer_id, MIN(dt) start_dt, MAX(dt) end_dt, COUNT(*) days
FROM g GROUP BY customer_id, grp HAVING COUNT(*)>=3;
```

**3. 用户次日/7日/30日留存**
```sql
WITH first_dt AS (
  SELECT customer_id, MIN(DATE(order_date)) fd FROM orders GROUP BY customer_id
),
act AS (SELECT DISTINCT customer_id, DATE(order_date) dt FROM orders)
SELECT f.fd,
  COUNT(DISTINCT f.customer_id) new_users,
  COUNT(DISTINCT CASE WHEN a.dt=DATE_ADD(f.fd,INTERVAL 1 DAY)  THEN a.customer_id END) d1,
  COUNT(DISTINCT CASE WHEN a.dt=DATE_ADD(f.fd,INTERVAL 7 DAY)  THEN a.customer_id END) d7,
  COUNT(DISTINCT CASE WHEN a.dt=DATE_ADD(f.fd,INTERVAL 30 DAY) THEN a.customer_id END) d30
FROM first_dt f LEFT JOIN act a ON a.customer_id=f.customer_id
GROUP BY f.fd;
```

**4. RFM 客户分群**
```sql
WITH rfm AS (
  SELECT customer_id,
    DATEDIFF(CURDATE(), MAX(order_date)) recency,
    COUNT(*) frequency, SUM(total_amount) monetary
  FROM orders WHERE status='completed' GROUP BY customer_id
),
s AS (
  SELECT *,
    NTILE(5) OVER (ORDER BY recency DESC) r,
    NTILE(5) OVER (ORDER BY frequency)   f,
    NTILE(5) OVER (ORDER BY monetary)    m
  FROM rfm
)
SELECT customer_id, r, f, m,
  CASE WHEN r>=4 AND f>=4 AND m>=4 THEN '高价值'
       WHEN r>=4 AND f<=2 THEN '新客'
       WHEN r<=2 AND f>=4 THEN '流失风险' ELSE '一般' END segment
FROM s;
```

**5. 月度销售额 + 环比 + 同比**
```sql
SELECT DATE_FORMAT(order_date,'%Y-%m') ym, SUM(total_amount) amt,
  LAG(SUM(total_amount),1)  OVER (ORDER BY DATE_FORMAT(order_date,'%Y-%m')) prev_mom,
  LAG(SUM(total_amount),12) OVER (ORDER BY DATE_FORMAT(order_date,'%Y-%m')) prev_yoy
FROM orders WHERE status='completed'
GROUP BY DATE_FORMAT(order_date,'%Y-%m');
```

**6. 递归分类树 + 路径**
```sql
WITH RECURSIVE tree AS (
  SELECT category_id, name, parent_id, 1 lvl, CAST(name AS CHAR(500)) path
  FROM categories WHERE parent_id IS NULL
  UNION ALL
  SELECT c.category_id, c.name, c.parent_id, t.lvl+1, CONCAT(t.path,'/',c.name)
  FROM categories c JOIN tree t ON c.parent_id=t.category_id
)
SELECT * FROM tree ORDER BY path;
```

**7. 购物篮关联分析（一起购买的商品对）**
```sql
SELECT a.product_id p1, b.product_id p2, COUNT(*) cnt
FROM order_items a
JOIN order_items b ON a.order_id=b.order_id AND a.product_id<b.product_id
GROUP BY p1,p2 ORDER BY cnt DESC LIMIT 20;
```

**8. 每日销售额与累计滚动**
```sql
SELECT DATE(order_date) dt, SUM(total_amount) daily,
  SUM(SUM(total_amount)) OVER (ORDER BY DATE(order_date)) running
FROM orders GROUP BY DATE(order_date);
```

**9. 每客户订单间隔天数**
```sql
SELECT customer_id, order_date,
  DATEDIFF(order_date,
           LAG(order_date) OVER (PARTITION BY customer_id ORDER BY order_date)) gap_days
FROM orders;
```

**10. 7 日移动平均**
```sql
SELECT DATE(order_date) dt, SUM(total_amount) amt,
  AVG(SUM(total_amount)) OVER (ORDER BY DATE(order_date)
                                ROWS BETWEEN 6 PRECEDING AND CURRENT ROW) ma7
FROM orders GROUP BY DATE(order_date);
```

**11. 转化漏斗**
```sql
SELECT
  COUNT(DISTINCT customer_id) visitors,
  COUNT(DISTINCT CASE WHEN event='view' THEN customer_id END) viewers,
  COUNT(DISTINCT CASE WHEN event='cart' THEN customer_id END) carters,
  COUNT(DISTINCT CASE WHEN event='pay'  THEN customer_id END) payers
FROM events;
```

**12. 库存周转天数**
```sql
SELECT p.product_id, p.name, p.stock,
  COALESCE(SUM(oi.quantity),0) sold_30d,
  ROUND(p.stock/NULLIF(SUM(oi.quantity)/30,0),1) days_of_stock
FROM products p
LEFT JOIN order_items oi ON oi.product_id=p.product_id
LEFT JOIN orders o ON o.order_id=oi.order_id
      AND o.order_date>=CURDATE()-INTERVAL 30 DAY
GROUP BY p.product_id,p.name,p.stock;
```

**13. 客户生命周期价值（CLV）**
```sql
SELECT customer_id, SUM(total_amount) lifetime_value,
  AVG(total_amount) avg_order, COUNT(*) orders,
  DATEDIFF(MAX(order_date),MIN(order_date))/NULLIF(COUNT(*)-1,0) avg_gap_days
FROM orders WHERE status='completed' GROUP BY customer_id;
```

**14. 订单金额分位数分布**
```sql
SELECT
  MAX(CASE WHEN pct<=0.25 THEN amt END) p25,
  MAX(CASE WHEN pct<=0.5  THEN amt END) p50,
  MAX(CASE WHEN pct<=0.75 THEN amt END) p75,
  MAX(CASE WHEN pct<=0.95 THEN amt END) p95
FROM (
  SELECT total_amount amt,
    PERCENT_RANK() OVER (ORDER BY total_amount) pct
  FROM orders WHERE status='completed'
) t;
```

**15. JSON 扩展字段查询**
```sql
SELECT order_id,
  JSON_UNQUOTE(JSON_EXTRACT(extra,'$.channel')) channel,
  JSON_UNQUOTE(JSON_EXTRACT(extra,'$.coupon.code')) coupon
FROM orders
WHERE JSON_EXTRACT(extra,'$.channel')='app';
```


## 二、PostgreSQL（15 条）

**1. 递归分类树 + 深度 + 排序路径**
```sql
WITH RECURSIVE tree AS (
  SELECT category_id, name, parent_id, 1 lvl,
         ARRAY[name] path
  FROM categories WHERE parent_id IS NULL
  UNION ALL
  SELECT c.category_id, c.name, c.parent_id, t.lvl+1,
         t.path || c.name
  FROM categories c JOIN tree t ON c.parent_id=t.category_id
)
SELECT * FROM tree ORDER BY path;
```

**2. LATERAL JOIN 取每客户最近 3 单**
```sql
SELECT c.customer_id, c.name, o.*
FROM customers c
CROSS JOIN LATERAL (
  SELECT order_id, order_date, total_amount
  FROM orders WHERE customer_id=c.customer_id
  ORDER BY order_date DESC LIMIT 3
) o;
```

**3. FILTER 子句一次算出多状态聚合**
```sql
SELECT DATE(order_date) dt,
  COUNT(*) FILTER (WHERE status='completed') ok,
  COUNT(*) FILTER (WHERE status='cancelled') cancel,
  SUM(total_amount) FILTER (WHERE status='completed') amt
FROM orders GROUP BY 1 ORDER BY 1;
```

**4. DISTINCT ON 取每客户最新一笔**
```sql
SELECT DISTINCT ON (customer_id)
  customer_id, order_id, order_date, total_amount
FROM orders ORDER BY customer_id, order_date DESC;
```

**5. 数组聚合 + 去重**
```sql
SELECT o.order_id,
  ARRAY_AGG(p.name ORDER BY p.name) products,
  ARRAY_AGG(DISTINCT p.category_id) cats
FROM orders o
JOIN order_items oi ON oi.order_id=o.order_id
JOIN products p ON p.product_id=oi.product_id
GROUP BY o.order_id;
```

**6. JSONB 包含查询**
```sql
SELECT order_id,
       extra->>'channel' AS channel,
       (extra->'coupon'->>'amount')::numeric AS coupon_amt
FROM orders
WHERE extra @> '{"channel":"app"}'::jsonb;
```

**7. generate_series 填充日期空洞**
```sql
SELECT gs.dt, COALESCE(SUM(o.total_amount),0) amt
FROM generate_series(CURRENT_DATE-INTERVAL '30 day',
                     CURRENT_DATE, '1 day') gs(dt)
LEFT JOIN orders o ON DATE(o.order_date)=gs.dt
GROUP BY gs.dt ORDER BY gs.dt;
```

**8. 会话化（30 分钟切分 Session）**
```sql
WITH e AS (
  SELECT user_id, ts,
    CASE WHEN ts - LAG(ts) OVER (PARTITION BY user_id ORDER BY ts)
              > INTERVAL '30 min'
              OR LAG(ts) OVER (PARTITION BY user_id ORDER BY ts) IS NULL
         THEN 1 ELSE 0 END new_session
  FROM events
),
s AS (SELECT *, SUM(new_session) OVER (PARTITION BY user_id ORDER BY ts) sid FROM e)
SELECT user_id, sid, MIN(ts) start_ts, MAX(ts) end_ts, COUNT(*) cnt
FROM s GROUP BY user_id, sid;
```

**9. 分位数 / 中位数**
```sql
SELECT category_id,
  PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY price) median,
  PERCENTILE_CONT(0.9) WITHIN GROUP (ORDER BY price) p90
FROM products GROUP BY category_id;
```

**10. GROUPING SETS 多维度汇总**
```sql
SELECT p.category_id, c.city, SUM(o.total_amount) amt
FROM orders o
JOIN order_items oi ON oi.order_id=o.order_id
JOIN products  p    ON p.product_id=oi.product_id
JOIN customers c    ON c.customer_id=o.customer_id
GROUP BY GROUPING SETS ((p.category_id),(c.city),(p.category_id,c.city),());
```

**11. FULL JOIN 订单与支付对账**
```sql
SELECT COALESCE(o.order_id,p.order_id) order_id,
       o.total_amount order_amt, p.amount pay_amt
FROM orders o FULL JOIN payments p USING(order_id)
WHERE o.total_amount IS DISTINCT FROM p.amount;
```

**12. 窗口帧：累计 / MA7 / 前 7 日**
```sql
SELECT dt, amt,
  SUM(amt) OVER (ORDER BY dt ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) cum,
  AVG(amt) OVER (ORDER BY dt ROWS BETWEEN 6 PRECEDING AND CURRENT ROW) ma7,
  SUM(amt) OVER (ORDER BY dt ROWS BETWEEN 6 PRECEDING AND 1 PRECEDING) prev7
FROM daily_sales;
```

**13. TABLESAMPLE 抽样**
```sql
SELECT * FROM orders TABLESAMPLE SYSTEM (1) WHERE status='completed';
```

**14. 状态透视 + 状态串**
```sql
SELECT customer_id,
  COUNT(*) FILTER (WHERE status='completed') completed,
  COUNT(*) FILTER (WHERE status='pending')   pending,
  STRING_AGG(DISTINCT status, ',') statuses
FROM orders GROUP BY customer_id;
```

**15. 时间区间重叠检测**
```sql
SELECT a.order_id, b.order_id
FROM orders a JOIN orders b
  ON a.customer_id=b.customer_id AND a.order_id<b.order_id
 AND tstzrange(a.start_time,a.end_time) && tstzrange(b.start_time,b.end_time);
```


## 三、Oracle（15 条）

**1. CONNECT BY 层级查询**
```sql
SELECT LEVEL lvl,
       LPAD(' ',2*(LEVEL-1))||name tree_name,
       category_id, parent_id
FROM categories
START WITH parent_id IS NULL
CONNECT BY PRIOR category_id = parent_id
ORDER SIBLINGS BY name;
```

**2. 分析函数全家桶**
```sql
SELECT customer_id, order_date, total_amount,
  SUM(total_amount) OVER (PARTITION BY customer_id ORDER BY order_date) cum,
  RANK()      OVER (PARTITION BY customer_id ORDER BY total_amount DESC) rk,
  LAG(total_amount) OVER (PARTITION BY customer_id ORDER BY order_date) prev,
  RATIO_TO_REPORT(total_amount) OVER (PARTITION BY customer_id) pct
FROM orders;
```

**3. MODEL 子句（预测下一年）**
```sql
SELECT product, y2022, y2023, y2024
FROM sales_view
MODEL RETURN UPDATED ROWS
  DIMENSION BY (product)
  MEASURES (y2022,y2023,y2024)
  RULES (y2024[ANY] = y2023[CV()] * 1.1);
```

**4. PIVOT 透视**
```sql
SELECT * FROM (
  SELECT EXTRACT(YEAR FROM order_date) yr, status, total_amount FROM orders
)
PIVOT (SUM(total_amount) FOR status IN
       ('completed' AS completed, 'pending' AS pending, 'cancelled' AS cancelled));
```

**5. 递归 WITH 分类树**
```sql
WITH tree (id,name,parent_id,lvl) AS (
  SELECT category_id,name,parent_id,1 FROM categories WHERE parent_id IS NULL
  UNION ALL
  SELECT c.category_id,c.name,c.parent_id,t.lvl+1
  FROM categories c JOIN tree t ON c.parent_id=t.id
)
SELECT * FROM tree;
```

**6. LISTAGG 拼接**
```sql
SELECT category_id,
  LISTAGG(name, ',') WITHIN GROUP (ORDER BY name) products
FROM products GROUP BY category_id;
```

**7. KEEP FIRST / LAST 取极值对应行**
```sql
SELECT category_id,
  MIN(price) KEEP (DENSE_RANK FIRST ORDER BY price) cheapest,
  MAX(price) KEEP (DENSE_RANK LAST  ORDER BY price) most_exp
FROM products GROUP BY category_id;
```

**8. MATCH_RECOGNIZE 行模式匹配**
```sql
SELECT * FROM orders
MATCH_RECOGNIZE (
  PARTITION BY customer_id ORDER BY order_date
  MEASURES A.order_date start_dt, B.order_date end_dt
  ONE ROW PER MATCH
  PATTERN (A B{2,})
  DEFINE A AS A.total_amount>0, B AS B.total_amount>0
);
```

**9. 年度同比**
```sql
SELECT yr, amt,
  LAG(amt) OVER (ORDER BY yr) prev,
  ROUND((amt-LAG(amt) OVER (ORDER BY yr))
        /LAG(amt) OVER (ORDER BY yr)*100, 2) growth_pct
FROM (
  SELECT EXTRACT(YEAR FROM order_date) yr, SUM(total_amount) amt
  FROM orders GROUP BY EXTRACT(YEAR FROM order_date)
);
```

**10. ROLLUP 层次汇总**
```sql
SELECT category_id, SUM(total_amount) amt
FROM orders o
JOIN order_items oi ON oi.order_id=o.order_id
JOIN products p ON p.product_id=oi.product_id
GROUP BY ROLLUP(category_id);
```

**11. 正则匹配**
```sql
SELECT * FROM customers WHERE REGEXP_LIKE(name,'^[A-Z][a-z]+( [A-Z][a-z]+)+$');
```

**12. 分组内 Top-N**
```sql
SELECT * FROM (
  SELECT p.*, ROW_NUMBER() OVER (PARTITION BY category_id ORDER BY price DESC) rn
  FROM products p
) WHERE rn<=3;
```

**13. 累计与移动平均**
```sql
SELECT dt, amt,
  SUM(amt) OVER (ORDER BY dt ROWS UNBOUNDED PRECEDING) cum,
  AVG(amt) OVER (ORDER BY dt ROWS BETWEEN 6 PRECEDING AND CURRENT ROW) ma7
FROM daily_sales;
```

**14. 转化漏斗**
```sql
SELECT
  COUNT(DISTINCT customer_id) visitors,
  COUNT(DISTINCT CASE WHEN event='view' THEN customer_id END) viewers,
  COUNT(DISTINCT CASE WHEN event='cart' THEN customer_id END) carters,
  COUNT(DISTINCT CASE WHEN event='pay'  THEN customer_id END) payers
FROM events;
```

**15. 闪回查询（历史时点）**
```sql
SELECT * FROM orders AS OF TIMESTAMP (SYSTIMESTAMP - INTERVAL '1' HOUR)
WHERE customer_id=1001;
```


## 四、SQL Server（15 条）

**1. 递归 CTE**
```sql
WITH tree AS (
  SELECT category_id, name, parent_id, 0 lvl
  FROM categories WHERE parent_id IS NULL
  UNION ALL
  SELECT c.category_id, c.name, c.parent_id, t.lvl+1
  FROM categories c JOIN tree t ON c.parent_id=t.category_id
)
SELECT * FROM tree OPTION (MAXRECURSION 100);
```

**2. CROSS APPLY 取每客户最近 3 单**
```sql
SELECT c.customer_id, c.name, o.order_id, o.order_date, o.total_amount
FROM customers c
CROSS APPLY (
  SELECT TOP 3 * FROM orders
  WHERE customer_id=c.customer_id ORDER BY order_date DESC
) o;
```

**3. OUTER APPLY 保留无单客户**
```sql
SELECT c.customer_id, c.name, x.last_order
FROM customers c
OUTER APPLY (
  SELECT TOP 1 order_date last_order FROM orders
  WHERE customer_id=c.customer_id ORDER BY order_date DESC
) x;
```

**4. PIVOT**
```sql
SELECT * FROM (
  SELECT YEAR(order_date) yr, status, total_amount FROM orders
) s
PIVOT (SUM(total_amount) FOR status IN
       ([completed],[pending],[cancelled])) p;
```

**5. UNPIVOT**
```sql
SELECT product_id, period, amount
FROM sales_pivot
UNPIVOT (amount FOR period IN (q1,q2,q3,q4)) u;
```

**6. 窗口函数 + 帧**
```sql
SELECT customer_id, order_date, total_amount,
  SUM(total_amount) OVER (PARTITION BY customer_id ORDER BY order_date
                          ROWS UNBOUNDED PRECEDING) cum,
  AVG(total_amount) OVER (PARTITION BY customer_id ORDER BY order_date
                          ROWS BETWEEN 2 PRECEDING AND CURRENT ROW) ma3
FROM orders;
```

**7. GROUPING SETS**
```sql
SELECT p.category_id, c.city, SUM(o.total_amount) amt
FROM orders o
JOIN order_items oi ON oi.order_id=o.order_id
JOIN products  p    ON p.product_id=oi.product_id
JOIN customers c    ON c.customer_id=o.customer_id
GROUP BY GROUPING SETS ((p.category_id),(c.city),());
```

**8. STRING_AGG**
```sql
SELECT category_id,
  STRING_AGG(name, ',') WITHIN GROUP (ORDER BY name) products
FROM products GROUP BY category_id;
```

**9. 时态表历史查询**
```sql
SELECT * FROM products
FOR SYSTEM_TIME AS OF '2024-01-01'
WHERE product_id=1;
```

**10. Gap-and-Islands 连续下单**
```sql
WITH d AS (SELECT DISTINCT customer_id, CAST(order_date AS DATE) dt FROM orders),
g AS (
  SELECT *, DATEADD(DAY,
    -ROW_NUMBER() OVER (PARTITION BY customer_id ORDER BY dt), dt) grp
  FROM d
)
SELECT customer_id, MIN(dt) start_dt, MAX(dt) end_dt, COUNT(*) days
FROM g GROUP BY customer_id, grp HAVING COUNT(*)>=3;
```

**11. 排名函数组合**
```sql
SELECT customer_id, order_id, total_amount,
  RANK()       OVER (PARTITION BY customer_id ORDER BY total_amount DESC) rk,
  DENSE_RANK() OVER (PARTITION BY customer_id ORDER BY total_amount DESC) drk,
  NTILE(4)     OVER (PARTITION BY customer_id ORDER BY total_amount) quartile
FROM orders;
```

**12. 年度同比**
```sql
SELECT YEAR(order_date) yr, MONTH(order_date) mo,
  SUM(total_amount) amt,
  LAG(SUM(total_amount),12) OVER
    (ORDER BY YEAR(order_date),MONTH(order_date)) yoy
FROM orders GROUP BY YEAR(order_date),MONTH(order_date);
```

**13. JSON 查询**
```sql
SELECT order_id,
  JSON_VALUE(extra,'$.channel') channel,
  JSON_QUERY(extra,'$.coupon')  coupon
FROM orders
WHERE JSON_VALUE(extra,'$.channel')='app';
```

**14. 占比分析**
```sql
SELECT customer_id, order_id, total_amount,
  SUM(total_amount) OVER (PARTITION BY customer_id) total_per_cust,
  total_amount*1.0 / SUM(total_amount) OVER (PARTITION BY customer_id) pct
FROM orders;
```

**15. 分页 OFFSET/FETCH**
```sql
SELECT * FROM orders
ORDER BY order_date DESC
OFFSET 20 ROWS FETCH NEXT 10 ROWS ONLY;
```


## 五、如何把种子集扩展到 300 条/库

上面 60 条是**方法论级别的模板**。要真正凑到 300 条/库，按下面 4 个维度做笛卡尔积扩展：

| 维度         | 取值                                                         | 倍数 |
| ------------ | ------------------------------------------------------------ | ---- |
| **技术模式** | 窗口 / CTE / 递归 / LATERAL / PIVOT / 漏斗 / RFM / 留存 / Gap-Islands / 会话化 / 累计 / 同比 / 分位数 … | 15+  |
| **业务域**   | 电商 / 金融风控 / 物流 / SaaS 订阅 / 社交 / 广告 / 医疗 / 教育 / 游戏 | ×9   |
| **指标口径** | 金额 / 数量 / 去重用户 / 转化率 / 留存率 / 客单价 / 复购率 / 时长 | ×8   |
| **难度层级** | 单表聚合 → 多表 JOIN → 窗口 → 递归 → 性能优化                | ×5   |

即 **15 × 9 × 8 × 5 ≈ 5400 种组合**，取其中 300 条即可轻松覆盖。
