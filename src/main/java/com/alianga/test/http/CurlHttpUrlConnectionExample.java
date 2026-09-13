package com.alianga.test.http;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class CurlHttpUrlConnectionExample {
    public static void main(String[] args) throws Exception {
        java.net.URL target = new java.net.URL("https://alianga.com/api/admin/posts/latest?top=5");
        HttpURLConnection conn = (HttpURLConnection) target.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("Accept", "application/json, text/plain, */*");
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        conn.setRequestProperty("Admin-Authorization", "fdd5e0cb168b49008a74b56fd4c88fb1");
        conn.setRequestProperty("Connection", "keep-alive");
        conn.setRequestProperty("Cookie", "cna=asHbIUYCUnwBASQJjQDvo1dM; JSESSIONID=node07jjn266e4lvfhixfvlsweill354350.node0; Hm_lvt_f05e7750437761d7611ae4840f232014=1786346912,1788319581; Hm_lpvt_f05e7750437761d7611ae4840f232014=1788319581; HMACCOUNT=A1C95D9022240F49");
        conn.setRequestProperty("Referer", "https://alianga.com/admin/index.html");
        conn.setRequestProperty("Sec-Fetch-Dest", "empty");
        conn.setRequestProperty("Sec-Fetch-Mode", "cors");
        conn.setRequestProperty("Sec-Fetch-Site", "same-origin");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36");
        conn.setRequestProperty("sec-ch-ua", "\"Not=A?Brand\";v=\"99\", \"Google Chrome\";v=\"151\", \"Chromium\";v=\"151\"");
        conn.setRequestProperty("sec-ch-ua-mobile", "?0");
        conn.setRequestProperty("sec-ch-ua-platform", "\"Linux\"");
        conn.setDoInput(true);


        int code = conn.getResponseCode();
        try (InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
            System.out.println("HTTP " + code);
            System.out.println(readAll(in));
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}

