# SQL pagination rewrite bench (jkit vs Druid vs JSqlParser)

warmup=2000 iter=20000
host=Linux amd64 java=17.0.11

A.parse_only                              jkit=   4858 ns/op  druid=   3665 ns/op  jsql= 264044 ns/op  | jkit/druid=1.33x  jkit/jsql=0.02x
B.parse_format_same_mysql                 jkit=   5272 ns/op  druid=   4363 ns/op  jsql= 267309 ns/op  | jkit/druid=1.21x  jkit/jsql=0.02x
C.mysql_limit_to_oracle_format            jkit=   6352 ns/op  druid=   3306 ns/op  jsql= 270597 ns/op  | jkit/druid=1.92x  jkit/jsql=0.02x
C.mysql_comma_to_postgres_format          jkit=   4354 ns/op  druid=   3403 ns/op  jsql= 204515 ns/op  | jkit/druid=1.28x  jkit/jsql=0.02x
C.oracle_rownum_to_postgres_format        jkit=  13566 ns/op  druid=   7292 ns/op  jsql= 217038 ns/op  | jkit/druid=1.86x  jkit/jsql=0.06x
D.setPage_or_limit_page2                  jkit=   4355 ns/op  druid=   3818 ns/op  jsql= 219850 ns/op  | jkit/druid=1.14x  jkit/jsql=0.02x
E.same_form_postgres_format_noop_adapt  jkit=168 ns/op (expect no clone+adapt)

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
