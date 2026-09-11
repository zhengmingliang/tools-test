# SQL pagination rewrite bench (jkit vs Druid vs JSqlParser)

warmup=2000 iter=20000
host=Linux amd64 java=17.0.11

A.parse_only                              jkit=   3923 ns/op  druid=   3952 ns/op  jsql= 279245 ns/op  | jkit/druid=0.99x  jkit/jsql=0.01x
B.parse_format_same_mysql                 jkit=   4204 ns/op  druid=   3993 ns/op  jsql= 268272 ns/op  | jkit/druid=1.05x  jkit/jsql=0.02x
C.mysql_limit_to_oracle_format            jkit=   4153 ns/op  druid=   3053 ns/op  jsql= 266396 ns/op  | jkit/druid=1.36x  jkit/jsql=0.02x
C.mysql_comma_to_postgres_format          jkit=   2605 ns/op  druid=   2647 ns/op  jsql= 213460 ns/op  | jkit/druid=0.98x  jkit/jsql=0.01x
C.oracle_rownum_to_postgres_format        jkit=   5006 ns/op  druid=   5720 ns/op  jsql= 208845 ns/op  | jkit/druid=0.88x  jkit/jsql=0.02x
D.setPage_or_limit_page2                  jkit=   3035 ns/op  druid=   3983 ns/op  jsql= 203811 ns/op  | jkit/druid=0.76x  jkit/jsql=0.01x
E.same_form_postgres_format_noop_adapt  jkit=175 ns/op (expect no clone+adapt)

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
