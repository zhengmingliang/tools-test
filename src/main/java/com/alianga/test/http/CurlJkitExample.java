package com.alianga.test.http;

import com.alianga.jkit.HttpUtils;
import com.alianga.jkit.http.HttpRequest;
import com.alianga.jkit.http.HttpResponse;

public class CurlJkitExample {
    public static void main(String[] args) throws Exception {
        HttpRequest request = new HttpRequest("GET", "https://alianga.com/api/admin/posts/latest?top=5");
        request.header("Accept", "application/json, text/plain, */*");
        request.header("Accept-Language", "zh-CN,zh;q=0.9");
        request.header("Admin-Authorization", "fdd5e0cb168b49008a74b56fd4c88fb1");
        request.header("Connection", "keep-alive");
        request.header("Cookie", "cna=asHbIUYCUnwBASQJjQDvo1dM; JSESSIONID=node07jjn266e4lvfhixfvlsweill354350.node0; Hm_lvt_f05e7750437761d7611ae4840f232014=1786346912,1788319581; Hm_lpvt_f05e7750437761d7611ae4840f232014=1788319581; HMACCOUNT=A1C95D9022240F49");
        request.header("Referer", "https://alianga.com/admin/index.html");
        request.header("Sec-Fetch-Dest", "empty");
        request.header("Sec-Fetch-Mode", "cors");
        request.header("Sec-Fetch-Site", "same-origin");
        request.header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36");
        request.header("sec-ch-ua", "\"Not=A?Brand\";v=\"99\", \"Google Chrome\";v=\"151\", \"Chromium\";v=\"151\"");
        request.header("sec-ch-ua-mobile", "?0");
        request.header("sec-ch-ua-platform", "\"Linux\"");
        request.followRedirects(true);
        request.ignoreSsl(false);
        HttpResponse response = HttpUtils.execute(request);
        try {
            System.out.println(response.code());
            System.out.println(response.body() == null ? "" : response.body().string());
        } finally {
            response.close();
        }
    }
}

