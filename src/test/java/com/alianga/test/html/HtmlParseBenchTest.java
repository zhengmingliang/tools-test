package com.alianga.test.html;

import com.alianga.jkit.html.Document;
import com.alianga.jkit.html.Element;
import com.alianga.jkit.html.Elements;
import com.alianga.jkit.html.Html;
import com.alianga.jkit.html.SelectorException;

import org.jsoup.Jsoup;
import org.junit.Assert;
import org.junit.Test;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * {@code com.alianga.jkit.html} 吞吐微基准：jkit vs Jsoup 1.18.1。
 *
 * <p>不引入到 jkit POM；仅 tools-test。Jsoup 只作为对照，jkit 本体保持零第三方依赖。
 * 报告写到 {@code target/html-parse-bench.md}。
 *
 * <p>计时用「预热 + 多轮取中位数」，避免单次抖动；断言只卡数量级，不卡具体数字，
 * 防止在不同机器上误报。真正要守住的是「不出现数量级退化」——
 * 例如此前 script 结束标签查找是 O(标签数 × 文档长度)，真实页面会比 Jsoup 慢 14 倍。
 *
 * @author 郑明亮
 */
public class HtmlParseBenchTest {

    private static final int WARMUP = 3;
    private static final int ROUNDS = 7;

    /** 构造真实感的文章列表页，items 越多页面越大。 */
    static String page(int items) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"utf-8\">")
                .append("<title>文章列表页</title>")
                .append("<meta name=\"description\" content=\"性能对比用页面\">")
                .append("<style>.item{margin:0}.title{font-weight:bold}</style>")
                .append("<script>var page=").append(items).append(";</script></head>")
                .append("<body class=\"pg list\">")
                .append("<header><h1>文章列表</h1><nav><a href=\"/\">首页</a>")
                .append("<a href=\"https://x.io/a\">外链</a></nav></header>")
                .append("<main id=\"main\" class=\"content\">");
        for (int i = 0; i < items; i++) {
            sb.append("<article class=\"post item\" data-id=\"").append(i).append("\">")
                    .append("<h2 class=\"title\">文章标题 ").append(i).append("</h2>")
                    .append("<p class=\"summary\">这是第 ").append(i)
                    .append(" 篇文章的摘要，包含 <b>加粗</b> 与 <a href=\"/p/").append(i)
                    .append("\">详情链接</a>。</p>")
                    .append("<ul class=\"tags\"><li>Java<li>HTML<li>CSS</ul>")
                    .append("<img src=\"/i/").append(i).append(".png\" alt=\"图 ").append(i)
                    .append("\"></article>");
        }
        sb.append("</main><table id=\"t\"><tr><th>名称<th>数量<tr><td>苹果<td>3<tr><td>梨<td>5</table>")
                .append("<footer><p>版权 &copy; 2026</p></footer></body></html>");
        return sb.toString();
    }

    /** 跑一轮 reps 次，返回单次耗时（纳秒）。 */
    private static double round(int reps, Runnable op) {
        long s = System.nanoTime();
        for (int k = 0; k < reps; k++) {
            op.run();
        }
        return (System.nanoTime() - s) / (double) reps;
    }

    /** 预热加上多轮取中位数（纳秒/次）。 */
    static double time(int reps, Runnable op) {
        for (int i = 0; i < WARMUP; i++) {
            round(reps, op);
        }
        double[] t = new double[ROUNDS];
        for (int r = 0; r < ROUNDS; r++) {
            t[r] = round(reps, op);
        }
        Arrays.sort(t);
        return t[ROUNDS / 2];
    }

    /**
     * 成对计时：按 A→B→B→A 的顺序各测两轮，各取较小值。
     *
     * <p>固定「先 jkit 后 jsoup」会让先跑的一方吃亏——JIT 编译、分支预测与缓存预热
     * 都发生在前几轮。实测大页面 {@code .post} 选择器在这种顺序下能差出 0.60x 与 0.89x，
     * 交替顺序 + 取小值可以把这个系统性偏差压掉。
     *
     * @param reps 每轮执行次数
     * @param a 被测操作 A
     * @param b 被测操作 B
     * @return 长度为 2 的数组：{@code [0]} 为 A 耗时，{@code [1]} 为 B 耗时，单位纳秒/次
     */
    static double[] pair(int reps, Runnable a, Runnable b) {
        for (int i = 0; i < WARMUP; i++) {
            round(reps, a);
            round(reps, b);
        }
        double[] a1 = new double[ROUNDS];
        double[] b1 = new double[ROUNDS];
        for (int r = 0; r < ROUNDS; r++) {
            a1[r] = round(reps, a);
            b1[r] = round(reps, b);
        }
        double[] b2 = new double[ROUNDS];
        double[] a2 = new double[ROUNDS];
        for (int r = 0; r < ROUNDS; r++) {
            b2[r] = round(reps, b);
            a2[r] = round(reps, a);
        }
        Arrays.sort(a1);
        Arrays.sort(a2);
        Arrays.sort(b1);
        Arrays.sort(b2);
        return new double[]{Math.min(a1[ROUNDS / 2], a2[ROUNDS / 2]),
                Math.min(b1[ROUNDS / 2], b2[ROUNDS / 2])};
    }

    private static String ms(double ns) {
        return String.format(Locale.ROOT, "%8.3f", ns / 1e6);
    }

    private static String x(double faster, double slower) {
        return String.format(Locale.ROOT, "%5.2fx", slower / faster);
    }

    // ---------------------------------------------------------------- 差分

    private static String jkitOf(com.alianga.jkit.html.Document doc, String q) {
        try {
            Elements es = doc.select(q);
            StringBuilder sb = new StringBuilder();
            for (Element e : es) {
                sb.append(e.tagName()).append(':').append(e.text()).append('|');
            }
            return es.size() + " [" + sb + "]";
        } catch (SelectorException ex) {
            return "EX:" + ex.getMessage();
        } catch (RuntimeException ex) {
            return "EX:" + ex;
        }
    }

    private static String jsoupOf(org.jsoup.nodes.Document doc, String q) {
        try {
            org.jsoup.select.Elements es = doc.select(q);
            StringBuilder sb = new StringBuilder();
            for (org.jsoup.nodes.Element e : es) {
                sb.append(e.tagName()).append(':').append(e.text()).append('|');
            }
            return es.size() + " [" + sb + "]";
        } catch (RuntimeException ex) {
            return "EX:" + ex.getClass().getSimpleName();
        }
    }

    /** 一组「HTML + 选择器」用例。 */
    static final class Case {
        final String name;
        final String html;
        final String[] queries;

        Case(String name, String html, String[] queries) {
            this.name = name;
            this.html = html;
            this.queries = queries;
        }
    }

    static List<Case> corpus() {
        String page = "<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"utf-8\">"
                + "<title>标题 &amp; 副标题</title>"
                + "<meta name=\"description\" content=\"页面描述\">"
                + "<link rel=\"stylesheet\" href=\"/a.css\">"
                + "<style>.x{color:red}</style>"
                + "<script>var a = 1 < 2 && 3 > 2;</script>"
                + "</head><body class=\"pg home\">"
                + "<header><h1>大标题</h1><nav><a href=\"/\">首页</a><a href=\"https://x.com/a\">外链</a></nav></header>"
                + "<main id=\"main\">"
                + "<article class=\"post\"><h2>文章 A</h2><p>第一段 <b>加粗</b> 内容</p>"
                + "<p>第二段 <a href=\"/p/1\">详情</a></p><img src=\"/i/1.png\" alt=\"图\">"
                + "<ul class=\"tags\"><li>Java<li>HTML<li>CSS</ul></article>"
                + "<article class=\"post\"><h2>文章 B</h2><p>简介</p></article>"
                + "</main>"
                + "<table id=\"t\"><tr><th>名称<th>数量<tr><td>苹果<td>3<tr><td>梨<td>5</table>"
                + "<footer><p>版权 &copy; 2026</p></footer>"
                + "<!-- 尾部注释 --></body></html>";

        List<Case> cs = new ArrayList<Case>();
        cs.add(new Case("完整页面", page, new String[]{
                "title", "h1", "h2", "p", "a", "a[href]", "a[href^=https]", "a[href^=/]",
                "#main .post", "article.post h2", "main > article", "article > p",
                ".tags li", "li:first-child", "li:last-child", "li:nth-child(2)",
                "li:nth-child(odd)", "ul.tags > li", "img[src$=.png]", "img[alt]",
                "h2 + p", "h1 ~ nav", "nav a:not([href^=https])",
                "table tr", "table > tbody > tr", "table td", "table th",
                "tr:first-child td", "td:first-child", "p:contains(第二段)",
                "meta[name=description]", "head > title", "body > header",
                "article:last-of-type", "h2:first-of-type", "footer p",
                "p:only-child", "div",
                "a:not([href])", "p:contains(加粗)", "p:containsOwn(详情)", "p:containsOwn(第二段)",
                "h2:containsOwn(文章)", "p:matches(.*详情.*)", "a[href!=/]", "a[href!=#]",
                "article:has(h2)", "li:eq(1)", "li:lt(2)", "p:header",
                "td:nth-child(2)", "tr:nth-of-type(2)", "tr:nth-last-child(1)",
                "div, p", "main article.post p a", "html > body > main", "p b", "p > b",
                "body header nav a[href^=https]", "*", "h2 ~ img", "img + ul", "footer > p"}));
        cs.add(new Case("省略结束标签", "<p>一<p>二<ul><li>a<li>b</ul><dl><dt>x<dd>y<dt>z<dd>w</dl>",
                new String[]{"p", "body > p", "li", "ul > li", "dt", "dd", "dl > dt", "dd:last-child"}));
        cs.add(new Case("表格", "<table><tr><td>1<td>2<tr><td>3<td>4</table>",
                new String[]{"tbody", "tr", "td", "table tr td", "table > tbody > tr > td", "td:first-child"}));
        cs.add(new Case("脏属性", "<DIV CLASS=Box  ><a href=/x?a=1&b=2>X</A><IMG SRC=a.png><input disabled>",
                new String[]{"div", ".Box", "a", "a[href]", "img", "img[src]", "input[disabled]"}));
        cs.add(new Case("实体与原文", "<div>&lt;a&gt;&amp;&nbsp;</div><textarea>&lt;p&gt;</textarea>"
                        + "<script>if(a<b){}</script>",
                new String[]{"div", "textarea", "script"}));
        cs.add(new Case("片段", "<div><span>hi</span></div><br><p>尾巴",
                new String[]{"div", "span", "br", "p", "body > *"}));
        cs.add(new Case("被行内元素埋住的 p", "<div><p>a<b>c<div>d</div></div><p>e",
                new String[]{"p", "div", "b", "div > p", "p b"}));
        cs.add(new Case("复杂表格", "<table><thead><tr><th>A<th>B</thead><tbody><tr><td>1<td>2"
                        + "<tr><td>3<td>4</tbody><tfoot><tr><td>x</table>",
                new String[]{"thead th", "tbody td", "tfoot td", "tr", "td", "th",
                        "tbody > tr:first-child td", "td:nth-child(2)"}));
        cs.add(new Case("表单与嵌套", "<form action=/s method=post><input name=q value='a b'>"
                        + "<select name=c><option value=1 selected>一<option>二</select>"
                        + "<textarea>默认 &lt; 内容</textarea><label><input type=checkbox checked>记住</label></form>",
                new String[]{"input", "input[name=q]", "input[type=checkbox]", "option",
                        "option[selected]", "textarea", "label input", "form > select option"}));
        cs.add(new Case("注释与空元素", "<div><!-- c1 --><span></span><p>  </p><i>x</i></div>",
                new String[]{"div", "span", "span:empty", "p:empty", "i", "div > *"}));
        return cs;
    }

    /**
     * 与 Jsoup 的差分比对。已知差异清单是刻意保留的取舍（见 {@code docs/html.md} §7），
     * 这里把「差异条数与内容」一起断言——新增差异会立刻失败，不会静默漂移。
     */
    @Test
    public void differentialAgainstJsoup() {
        List<String> diffs = new ArrayList<String>();
        int same = 0;
        for (Case c : corpus()) {
            com.alianga.jkit.html.Document a = Html.parse(c.html);
            org.jsoup.nodes.Document b = Jsoup.parse(c.html);
            for (String q : c.queries) {
                String ra = jkitOf(a, q);
                String rb = jsoupOf(b, q);
                if (ra.equals(rb)) {
                    same++;
                } else {
                    diffs.add(c.name + " :: " + q + "\n      jkit  = " + ra + "\n      jsoup = " + rb);
                }
            }
        }
        System.out.println("差分比对：一致 " + same + " / 差异 " + diffs.size());
        for (String d : diffs) {
            System.out.println("  - " + d);
        }
        // 已知差异：:has() / :eq() / :lt() / :header（Jsoup 专有扩展），以及
        // 活动格式化元素重排（收养算法）导致的 p 结构差异。
        // 数量变化说明行为漂移，必须人工确认后再更新。
        Assert.assertEquals("差异条数与已知清单不一致，请人工确认后更新本断言",
                7, diffs.size());
        Assert.assertTrue("一致条数偏少，疑似引入回归", same >= 112);
    }

    /** 真实页面常见的 script 密集场景：不能出现 O(标签数 × 文档长度) 的退化。 */
    @Test
    public void scriptHeavyPageHasNoQuadraticRegression() {
        StringBuilder sb = new StringBuilder("<html><head>");
        for (int i = 0; i < 24; i++) {
            sb.append("<script>var s").append(i).append(" = ").append(i).append(";</script>");
        }
        sb.append("</head><body>").append(page(60)).append("</body></html>");
        String html = sb.toString();

        double[] p = pair(200, new Runnable() {
            public void run() {
                Html.parse(html);
            }
        }, new Runnable() {
            public void run() {
                Jsoup.parse(html);
            }
        });
        double jkit = p[0];
        double jsoup = p[1];
        System.out.printf(Locale.ROOT, "script 密集页解析：jkit %s ms / jsoup %s ms%n", ms(jkit), ms(jsoup));
        // 只卡数量级：修复前这里是 14 倍以上的退化
        Assert.assertTrue("解析出现数量级退化（jkit " + ms(jkit) + " ms vs jsoup " + ms(jsoup) + " ms）",
                jkit < jsoup * 3.0 + 1.0e6);
    }

    /** 吞吐基准：解析、文本抽取、选择器、常驻内存、端到端。 */
    @Test
    public void throughputAgainstJsoup() throws Exception {
        String[] scales = {"小页面 2 条 (1.6 KB)", "中页面 60 条 (30 KB)", "大页面 600 条 (300 KB)"};
        int[] items = {2, 60, 600};
        int[] reps = {400, 400, 60};

        StringBuilder rep = new StringBuilder();
        rep.append("# HTML parse bench (jkit vs Jsoup 1.18.1)\n\n");
        rep.append("warmup=").append(WARMUP).append(" rounds=").append(ROUNDS).append(" (中位数)\n");
        rep.append("os=").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.arch")).append(" java=")
                .append(System.getProperty("java.version"))
                .append(" maxHeap=").append(Runtime.getRuntime().maxMemory() / 1048576L)
                .append(" MB\n\n");
        rep.append("倍数 = jsoup / jkit，>1 表示 jkit 更快\n\n");

        rep.append("## 解析\n\n```\n");
        rep.append(String.format(Locale.ROOT, "%-24s %10s %10s %8s%n", "scenario", "jkit(ms)", "jsoup(ms)", "jkit快"));
        double parseRatio = 1;
        for (int i = 0; i < items.length; i++) {
            final String html = page(items[i]);
            final int n = reps[i];
            double[] p = pair(n, new Runnable() {
                public void run() {
                    Html.parse(html);
                }
            }, new Runnable() {
                public void run() {
                    Jsoup.parse(html);
                }
            });
            double a = p[0];
            double b = p[1];
            if (i == items.length - 1) {
                parseRatio = b / a;
            }
            rep.append(String.format(Locale.ROOT, "%-24s %10s %10s %8s%n", scales[i], ms(a), ms(b), x(a, b)));
        }
        rep.append("```\n\n## 解析 + text()\n\n```\n");
        rep.append(String.format(Locale.ROOT, "%-24s %10s %10s %8s%n", "scenario", "jkit(ms)", "jsoup(ms)", "jkit快"));
        for (int i = 0; i < items.length; i++) {
            final String html = page(items[i]);
            final int n = reps[i];
            double[] p = pair(n, new Runnable() {
                public void run() {
                    Html.parse(html).text();
                }
            }, new Runnable() {
                public void run() {
                    Jsoup.parse(html).text();
                }
            });
            rep.append(String.format(Locale.ROOT, "%-24s %10s %10s %8s%n", scales[i], ms(p[0]), ms(p[1]), x(p[0], p[1])));
        }
        rep.append("```\n");

        String midHtml = page(60);
        String bigHtml = page(600);
        final Document midJ = Html.parse(midHtml);
        final org.jsoup.nodes.Document midS = Jsoup.parse(midHtml);
        final Document bigJ = Html.parse(bigHtml);
        final org.jsoup.nodes.Document bigS = Jsoup.parse(bigHtml);

        String[] queries = {"#main", ".post", "article.post h2", "main > article",
                "a[href^=/p/]", "li:first-child", "h2 + p", "p b"};

        rep.append("\n## 选择器（中页面 60 条，预解析后重复查询）\n\n```\n");
        rep.append(String.format(Locale.ROOT, "%-24s %10s %10s %8s%n", "selector", "jkit(ms)", "jsoup(ms)", "jkit快"));
        for (final String q : queries) {
            double[] p = pair(500, new Runnable() {
                public void run() {
                    midJ.select(q);
                }
            }, new Runnable() {
                public void run() {
                    midS.select(q);
                }
            });
            rep.append(String.format(Locale.ROOT, "%-24s %10s %10s %8s%n", q, ms(p[0]), ms(p[1]), x(p[0], p[1])));
        }
        rep.append("```\n");

        rep.append("\n## 选择器（大页面 600 条）\n\n```\n");
        rep.append(String.format(Locale.ROOT, "%-24s %10s %10s %8s%n", "selector", "jkit(ms)", "jsoup(ms)", "jkit快"));
        for (final String q : new String[]{"#main", ".post", "article.post h2", "a[href^=/p/]"}) {
            double[] p = pair(50, new Runnable() {
                public void run() {
                    bigJ.select(q);
                }
            }, new Runnable() {
                public void run() {
                    bigS.select(q);
                }
            });
            rep.append(String.format(Locale.ROOT, "%-24s %10s %10s %8s%n", q, ms(p[0]), ms(p[1]), x(p[0], p[1])));
        }
        rep.append("```\n");

        long jm = retained(150, bigHtml, true);
        long sm = retained(150, bigHtml, false);
        // 量完立刻归还，否则这几百兆垃圾会挤掉后面的端到端测量
        System.gc();
        sleep(300);
        rep.append("\n## 常驻内存（150 份大页面 DOM）\n\n```\n");
        rep.append(String.format(Locale.ROOT, "jkit=%.1f MB  jsoup=%.1f MB  jkit/jsoup=%.2fx%n",
                jm / 1048576.0, sm / 1048576.0, sm / (double) jm));
        rep.append("```\n");

        final String[] q3 = {".post", "article h2", "a[href^=/p/]"};
        double[] p3 = pair(60, new Runnable() {
            public void run() {
                Document d = Html.parse(bigHtml);
                for (String q : q3) {
                    Elements es = d.select(q);
                    if (es.isEmpty()) {
                        throw new IllegalStateException(q);
                    }
                }
                if (d.text().isEmpty()) {
                    throw new IllegalStateException("text");
                }
            }
        }, new Runnable() {
            public void run() {
                org.jsoup.nodes.Document d = Jsoup.parse(bigHtml);
                for (String q : q3) {
                    if (d.select(q).isEmpty()) {
                        throw new IllegalStateException(q);
                    }
                }
                if (d.text().isEmpty()) {
                    throw new IllegalStateException("text");
                }
            }
        });
        double a3 = p3[0];
        double b3 = p3[1];
        rep.append("\n## 端到端（大页面：解析 + 3 次查询 + 取文本）\n\n```\n");
        rep.append(String.format(Locale.ROOT, "jkit=%s ms  jsoup=%s ms  jkit快=%s%n", ms(a3), ms(b3), x(a3, b3)));
        rep.append("```\n");

        Path out = Paths.get("target", "html-parse-bench.md");
        Files.createDirectories(out.getParent());
        BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8);
        try {
            w.write(rep.toString());
        } finally {
            w.close();
        }
        System.out.println(rep);
        System.out.println("报告已写入 " + out.toAbsolutePath());

        Assert.assertTrue("大页面解析出现数量级退化", parseRatio > 0.5);
    }

    private static long retained(int n, final String html, final boolean useJkit) {
        for (int i = 0; i < 20; i++) {
            if (useJkit) {
                Html.parse(html);
            } else {
                Jsoup.parse(html);
            }
        }
        System.gc();
        sleep(200);
        long before = used();
        List<Object> keep = new ArrayList<Object>(n);
        for (int i = 0; i < n; i++) {
            keep.add(useJkit ? Html.parse(html) : Jsoup.parse(html));
        }
        System.gc();
        sleep(200);
        long after = used();
        keep.clear();
        System.gc();
        sleep(200);
        return after - before;
    }

    private static long used() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
