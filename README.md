# tools-test

jkit 的基准测试与真库回归工程。

测试脚本不放进 jkit 仓库，是为了让 jkit 本体保持零第三方依赖：本工程可以用 Jsoup 做 HTML 解析的对照组、用 Testcontainers 起真库，这些依赖只存在于这里。

## HTML 解析压测

包名 `com.alianga.test.html`，对照 Jsoup 1.18.1：

- `HtmlParseBenchTest`：合成页面吞吐（解析 / 取文本 / 选择器 / 端到端）、与 Jsoup 的差分比对（119 组「HTML + 选择器」用例）、script 密集页退化回归、常驻内存。
- `HtmlRealSiteBenchTest`：真实站点 alianga.com 的抽取正确性与端到端耗时。需要网络，取不到就跳过，不会让构建变红。

```bash
mvn test -Dtest='HtmlParseBenchTest,HtmlRealSiteBenchTest'
```

报告写到 `target/html-parse-bench.md`、`target/html-real-site-bench.md`，定稿副本归档在 `reports/`。结论同步到 jkit 的 `docs/html.md` §9。

计时口径：预热 3 轮 + 计时 7 轮取中位数，并且**成对交替**（A→B→B→A 各测两轮、各取较小值）。固定「先 jkit 后 jsoup」会让先跑的一方吃亏——JIT 编译、分支预测、缓存预热都压在前几轮——实测大页面 `.post` 能凭空多出 0.6 倍的「jkit 更慢」。堆固定为 `-Xms1g -Xmx2g`，因为常驻内存用例会在堆里放上百份 DOM。断言只卡数量级、不卡具体数字，换台机器不会误报。

## 注意事项

`pom.xml` 里有一批 `system` 域的 JDBC 驱动（Oracle / 达梦 / GBase / openGauss / SQLite / DuckDB / SQL Server），`systemPath` 指向本机 `/opt/...`。换机器要改这些路径，否则 Maven 在解析依赖阶段就会失败，连编译都进不去。
