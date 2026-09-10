# External SQL corpora（jkit-sql 批量验收）

本目录存放从外部仓库复制的 SQL 语料，供 `ExternalSqlCorpusTest` 在 CI / 本地跑解析成功率，留下可检查的失败报告。

## 来源

| 文件 | 来源 | 说明 |
|------|------|------|
| `bird-simple-sql.json` | github/sqls | JSON 字符串数组；按 **ISO-8859-1 (latin-1)** 解码后再当 JSON 读 |
| `spider_ddl.jsonl` | spider data | 每行 JSON，字段 `sql` |
| `spider_dev_pairs.jsonl` | spider | 字段 `query` |
| `spider_test_pairs.jsonl` | spider | 字段 `query`；已知约 8 条 `GROUP BY …;, …` 噪声，测试允许 ≥99.5% 或 allowlist |
| `spider_train_others_pairs.jsonl` | spider | 字段 `query` |
| `spider_train_spider_pairs.jsonl` | spider | 字段 `query` |
| `complex100-主流数据库复杂业务SQL100条.md` | 本机下载目录 | Markdown；`sql` 代码块 |
| `complex100.jsonl` | 由上述 md 抽取 | 备用；字段 `sql` / `prefer` / `section` |

**未收录**：`spider_dml.json` 仅为 Git LFS pointer（真实对象约 165MB），未拉 LFS 前不要当语料用。

## 运行

在 **tools-test** 仓库根目录（独立 Maven 工程）：

```bash
mvn -Dtest=ExternalSqlCorpusTest test
```

需本地已安装匹配的 `jkit-sql`（pom 中版本，当前 `2.0.1`）。失败明细：

`target/sql-corpus-reports/<corpus>-fails.tsv`

## 阈值

- bird：≥ 99.9%（当前 100%）
- spider_ddl / spider_dev / spider_train*：100%
- spider_test：≥ 99.5%，或失败均命中 `;,` allowlist
- complex100：≥ 95%（当前 100%）
