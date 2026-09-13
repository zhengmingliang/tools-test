# SQL pagination rewrite bench (jkit vs Druid vs JSqlParser)

warmup=2000 iter=20000
host=Linux amd64 java=17.0.11

A.parse_only                              jkit=   4753 ns/op  druid=   4878 ns/op  jsql= 311304 ns/op  | jkit/druid=0.97x  jkit/jsql=0.02x
B.parse_format_same_mysql                 jkit=   4901 ns/op  druid=   4299 ns/op  jsql= 270023 ns/op  | jkit/druid=1.14x  jkit/jsql=0.02x
C.mysql_limit_to_oracle_format            jkit=   2478 ns/op  druid=   4397 ns/op  jsql= 269914 ns/op  | jkit/druid=0.56x  jkit/jsql=0.01x
C.mysql_comma_to_postgres_format          jkit=   4068 ns/op  druid=   4315 ns/op  jsql= 200501 ns/op  | jkit/druid=0.94x  jkit/jsql=0.02x
C.oracle_rownum_to_postgres_format        jkit=   6161 ns/op  druid=   8464 ns/op  jsql= 219855 ns/op  | jkit/druid=0.73x  jkit/jsql=0.03x
D.setPage_or_limit_page2                  jkit=   2440 ns/op  druid=   4661 ns/op  jsql= 207292 ns/op  | jkit/druid=0.52x  jkit/jsql=0.01x
E.same_form_postgres_format_noop_adapt  jkit=162 ns/op (expect no clone+adapt)

## samples
jkit mysql→oracle: SELECT * FROM (SELECT id, name FROM t_user WHERE age > 18) XX WHERE ROWNUM <= 10000
jkit user path: SELECT id, name
FROM t_user
WHERE age > 18
LIMIT 10000
jkit page2→pg: SELECT id, name
FROM t_user
WHERE age > 18
LIMIT 30 OFFSET 30
druid limit oracle: SELECT id, name
FROM t_user
WHERE age > 18
	AND ROWNUM <= 10000
druid limit pg: SELECT id, name
FROM t_user
WHERE age > 18
LIMIT 30 OFFSET 30
