package com.alianga.test.html;

import com.alianga.jkit.html.Document;
import com.alianga.jkit.html.Element;
import com.alianga.jkit.html.Elements;
import com.alianga.jkit.html.Html;

import org.jsoup.Jsoup;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 真实站点解析基准：alianga.com（Halo 1.4.5 博客）。
 *
 * <p>合成页面会掩盖真实页面的病态路径——此前 {@code script} 结束标签查找是
 * O(标签数 × 文档长度)，合成页面只有 1 个 script 看不出来，真实页面有 24 个 script
 * 就直接退化成比 Jsoup 慢 14 倍。这个测试就是用来守住这类问题。
 *
 * <p>需要网络；取不到就 {@link Assume} 跳过，不因断网而失败。
 *
 * @author 郑明亮
 */
public class HtmlRealSiteBenchTest {

    private static final String HOME = "https://alianga.com/";
    private static final int WARMUP = 3;
    private static final int ROUNDS = 7;

    /** 一篇文章抽出的字段。 */
    static final class Post {
        String link;
        String title;
        String imgLazy;
        String imgPlace;
        String date;

        boolean same(Post o) {
            return eq(link, o.link) && eq(title, o.title) && eq(imgLazy, o.imgLazy)
                    && eq(imgPlace, o.imgPlace) && eq(date, o.date);
        }

        static boolean eq(String a, String b) {
            return a == null ? b == null : a.equals(b);
        }

        String dump() {
            return link + "\n      title=" + title + "\n      date=" + date
                    + "\n      data-src=" + imgLazy + "\n      src=" + imgPlace;
        }
    }

    static List<Post> jkitPosts(Document doc) {
        List<Post> out = new ArrayList<Post>();
        Elements arts = doc.select("article.post-list-thumb");
        for (int i = 0; i < arts.size(); i++) {
            Element a = arts.get(i);
            Post p = new Post();
            Element titleA = a.selectFirst("a.post-title");
            p.link = titleA == null ? "" : titleA.attr("href");
            Element h3 = a.selectFirst("a.post-title h3");
            p.title = h3 == null ? "" : h3.text();
            Element img = a.selectFirst(".post-thumb img");
            p.imgLazy = img == null ? "" : img.attr("data-src");
            p.imgPlace = img == null ? "" : img.attr("src");
            Element dt = a.selectFirst(".post-date span.i18n");
            p.date = dt == null ? "" : dt.attr("data-ivalue");
            out.add(p);
        }
        return out;
    }

    static List<Post> jsoupPosts(org.jsoup.nodes.Document doc) {
        List<Post> out = new ArrayList<Post>();
        for (org.jsoup.nodes.Element a : doc.select("article.post-list-thumb")) {
            Post p = new Post();
            org.jsoup.nodes.Element titleA = a.selectFirst("a.post-title");
            p.link = titleA == null ? "" : titleA.attr("href");
            org.jsoup.nodes.Element h3 = a.selectFirst("a.post-title h3");
            p.title = h3 == null ? "" : h3.text();
            org.jsoup.nodes.Element img = a.selectFirst(".post-thumb img");
            p.imgLazy = img == null ? "" : img.attr("data-src");
            p.imgPlace = img == null ? "" : img.attr("src");
            org.jsoup.nodes.Element dt = a.selectFirst(".post-date span.i18n");
            p.date = dt == null ? "" : dt.attr("data-ivalue");
            out.add(p);
        }
        return out;
    }

    static String fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 jkit-html-bench");
        c.setInstanceFollowRedirects(true);
        try {
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) {
                bos.write(buf, 0, r);
            }
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            c.disconnect();
        }
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
     * 成对计时：按 A→B→B→A 的顺序各测两轮，各取较小值，抵消执行顺序带来的系统性偏差。
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

    private static String x(double jsoupNs, double jkitNs) {
        return String.format(Locale.ROOT, "%7.2fx", jsoupNs / jkitNs);
    }

    private static void row(StringBuilder rep, String name, double jkitNs, double jsoupNs) {
        rep.append(String.format(Locale.ROOT, "%-28s %10s %10s %8s%n",
                name, ms(jkitNs), ms(jsoupNs), x(jsoupNs, jkitNs)));
    }

    /**
     * 首页：抽最近 10 篇文章的链接 / 标题 / 懒加载图 / 占位图 / 发布日期，
     * 逐字段与 Jsoup 比对，并测解析与抽取吞吐。
     */
    @Test
    public void homePageExtractAndBench() throws Exception {
        String html;
        try {
            html = fetch(HOME);
        } catch (Exception e) {
            Assume.assumeNoException("取不到 " + HOME + "，跳过真实站点基准", e);
            return;
        }
        Assume.assumeTrue("首页内容异常", html.length() > 5000);

        Document jd = Html.parse(html);
        org.jsoup.nodes.Document sd = Jsoup.parse(html);

        List<Post> jp = jkitPosts(jd);
        List<Post> sp = jsoupPosts(sd);
        System.out.println("抽到文章：jkit " + jp.size() + " · jsoup " + sp.size());
        Assert.assertFalse("没抽到文章，选择器或解析有问题", jp.isEmpty());
        Assert.assertEquals("文章条数与 Jsoup 不一致", sp.size(), jp.size());
        for (int i = 0; i < jp.size(); i++) {
            if (!jp.get(i).same(sp.get(i))) {
                Assert.fail("第 " + (i + 1) + " 篇与 Jsoup 不一致：\n  jkit : " + jp.get(i).dump()
                        + "\n  jsoup: " + sp.get(i).dump());
            }
        }

        final String h = html;
        double[] p1 = pair(400, new Runnable() {
            public void run() {
                Html.parse(h);
            }
        }, new Runnable() {
            public void run() {
                Jsoup.parse(h);
            }
        });
        double a1 = p1[0];
        double b1 = p1[1];
        double[] p2 = pair(250, new Runnable() {
            public void run() {
                jkitPosts(Html.parse(h));
            }
        }, new Runnable() {
            public void run() {
                jsoupPosts(Jsoup.parse(h));
            }
        });
        double a2 = p2[0];
        double b2 = p2[1];

        // 预解析后重复查询，衡量纯选择器开销
        final Document pj = jd;
        final org.jsoup.nodes.Document ps = sd;
        String[] sels = {"article.post-list-thumb", "a.post-title h3", ".post-thumb img", ".post-date span.i18n"};
        double[] sa = new double[sels.length];
        double[] sb = new double[sels.length];
        for (int i = 0; i < sels.length; i++) {
            final String q = sels[i];
            double[] p = pair(1000, new Runnable() {
                public void run() {
                    pj.select(q);
                }
            }, new Runnable() {
                public void run() {
                    ps.select(q);
                }
            });
            sa[i] = p[0];
            sb[i] = p[1];
        }

        StringBuilder rep = new StringBuilder();
        rep.append("# HTML real-site bench (jkit vs Jsoup 1.18.1)\n\n");
        rep.append("site=").append(HOME).append("\n");
        rep.append("size=").append(String.format(Locale.ROOT, "%.1f KB", h.length() / 1024.0)).append("\n");
        rep.append("os=").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.arch")).append(" java=")
                .append(System.getProperty("java.version")).append('\n');
        rep.append("warmup=").append(WARMUP).append(" rounds=").append(ROUNDS).append(" (中位数)\n\n");
        rep.append("正确性：").append(jp.size()).append(" 篇 × 5 字段与 Jsoup 逐条一致\n\n");
        rep.append("```\n");
        rep.append(String.format(Locale.ROOT, "%-28s %10s %10s %8s%n", "scenario", "jkit(ms)", "jsoup(ms)", "jkit快"));
        row(rep, "parse", a1, b1);
        row(rep, "parse + extract all", a2, b2);
        for (int i = 0; i < sels.length; i++) {
            row(rep, "select " + sels[i], sa[i], sb[i]);
        }
        rep.append("```\n");

        Path out = Paths.get("target", "html-real-site-bench.md");
        Files.createDirectories(out.getParent());
        BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8);
        try {
            w.write(rep.toString());
        } finally {
            w.close();
        }
        System.out.println(rep);
        System.out.println("报告已写入 " + out.toAbsolutePath());

        // 真实页面含 24 个 script，此前这里会退化到 14 倍；只卡数量级
        Assert.assertTrue("真实页面解析出现数量级退化（jkit " + ms(a1) + " ms vs jsoup " + ms(b1) + " ms）",
                a1 < b1 * 3.0 + 1.0e6);
    }

    /** 文章详情页的 {@code pre code} 代码块：命中数、class、text() 与原文都要与 Jsoup 一致。 */
    @Test
    public void articleCodeBlocksMatchJsoup() throws Exception {
        String[] urls = {
                "https://alianga.com/articles/sql-parser",
                "https://alianga.com/articles/jkit-sql-desensitization",
                "https://alianga.com/articles/modify-git-push-time"};
        int checked = 0;
        for (String u : urls) {
            String html;
            try {
                html = fetch(u);
            } catch (Exception e) {
                continue; // 单篇取不到不影响其他篇
            }
            if (html.length() < 5000) {
                continue;
            }
            Document jd = Html.parse(html);
            org.jsoup.nodes.Document sd = Jsoup.parse(html);
            Elements jc = jd.select("pre code");
            org.jsoup.select.Elements sc = sd.select("pre code");
            System.out.println(u + " → pre code: jkit " + jc.size() + " · jsoup " + sc.size());
            Assert.assertEquals(u + " 的 pre code 命中数不一致", sc.size(), jc.size());
            if (jc.isEmpty()) {
                continue;
            }
            int n = Math.min(jc.size(), 5);
            for (int i = 0; i < n; i++) {
                Assert.assertEquals(u + " 第 " + (i + 1) + " 块 class 不一致",
                        sc.get(i).attr("class"), jc.get(i).attr("class"));
                Assert.assertEquals(u + " 第 " + (i + 1) + " 块 text() 不一致",
                        sc.get(i).text(), jc.get(i).text());
                Assert.assertEquals(u + " 第 " + (i + 1) + " 块原文不一致",
                        sc.get(i).wholeText(), jc.get(i).nodeText());
            }
            checked++;
        }
        Assume.assumeTrue("所有详情页都取不到，跳过", checked > 0);
    }
}
