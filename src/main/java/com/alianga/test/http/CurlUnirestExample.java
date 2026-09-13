package com.alianga.test.http;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;

public class CurlUnirestExample {
    public static void main(String[] args) throws Exception {
        Unirest.config()
                .followRedirects(true);

        HttpResponse<String> response = Unirest.get("https://alianga.com/api/admin/posts/latest?top=5")
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Admin-Authorization", "fdd5e0cb168b49008a74b56fd4c88fb1")
                .header("Connection", "keep-alive")
                .header("Cookie", "cna=asHbIUYCUnwBASQJjQDvo1dM; JSESSIONID=node07jjn266e4lvfhixfvlsweill354350.node0; Hm_lvt_f05e7750437761d7611ae4840f232014=1786346912,1788319581; Hm_lpvt_f05e7750437761d7611ae4840f232014=1788319581; HMACCOUNT=A1C95D9022240F49")
                .header("Referer", "https://alianga.com/admin/index.html")
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "same-origin")
                .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36")
                .header("sec-ch-ua", "\"Not=A?Brand\";v=\"99\", \"Google Chrome\";v=\"151\", \"Chromium\";v=\"151\"")
                .header("sec-ch-ua-mobile", "?0")
                .header("sec-ch-ua-platform", "\"Linux\"")
                .asString();
        System.out.println(response.getStatus());
        System.out.println(response.getBody());
        Unirest.shutDown();
    }
}
