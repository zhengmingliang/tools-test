package com.alianga.test.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class CurlJdkExample {
    public static void main(String[] args) throws Exception {
        HttpClient.Builder clientBuilder = HttpClient.newBuilder();
        clientBuilder.followRedirects(HttpClient.Redirect.NORMAL);
        HttpClient client = clientBuilder.build();
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create("https://wx.mail.qq.com/list/folderlist"))
                .header("accept", "*/*")
                .header("accept-language", "zh-CN,zh;q=0.9")
                .header("content-type", "application/x-www-form-urlencoded")
                .header("Cookie", "pgv_pvid=7023543030; a_pk__04=98e5ac423f14c6d23fa66dae09000004d19b11; _qimei_fingerprint=ae194b7c8a5144b5ad23ad6c0dabe749; a_sk__05=01543531656d603663376c356165606036613530666735306237643035363163606d; pgv_info=ssid=s9197570480; a_sk__10=015465606564636732626260666463303263353530676337306766626d67616c633661656464; a_sk__07__0WEB071JJOW4VSP7=015465636c626166676c6261; qlogin_uid=d4c064b0d0de4eb76d1bb6ffa2d3d569; qq_domain_video_guid_verify=ac54e888c59b6180; _qimei_q36=; _qimei_h38=98e5ac423f14c6d23fa66dae09000004d19b11; qm_device_id=yyTVjkcDll2m12FIyDYKRgLr733ZiaahiRVctbdCso8TbZMZUVso65MaxizL312f; xm_uin=13102662097181836; qm_logintype=qq; _qimei_q32=; _qimei_i_2=22f948c5cc1d; _qimei_i_1=52fc708b9d0f558a9293fc330d8577b3f1baa6f2440a0584e0de7d582f93206c616333c13980b0ddd7b4fdf1; a_sk__07__15d45fa36b498329=015465636c6c666762616165; xm_envid=456_PFl9irQNF6U5pojOZrH5M3znQfqWe4H/yo4rfThUgmv4Y9FQCNJ0tLUpZdbquiZLO6418D0ZSbpSPiJoKjpnZreRyGY+9EFDhrt4PG8Q3pp/MJVoL5EgEABw+MieABmy3A5C4O3L74rDi5pbZ3bAPgHIFDUMKq4/TVYibA==; xm_pcache=13102662097181836&V2@iuFoE7RuQFi73B4TM7ZdrQAA@0; xm_device_id=c35ad4ee; xm_sid=zYw1aYwcVGsu6jlBAD9BdQAA; xm_muti_sid=13102662097181836&zYw1aYwcVGsu6jlBAD9BdQAA; xm_skey=13102662097181836&0d308e60838f93f72c499ee2fa37f35c; xm_ws=13102662097181836&e9defa478d076d0afcee26f4bfee880d; xm_data_ticket=13102662097181836&CAESIGqXdhYALozNP-ocjGhvhJBRF0vnOBfGS5EmyNuv7w5p")
                .header("origin", "https://wx.mail.qq.com")
                .header("priority", "u=1, i")
                .header("referer", "https://wx.mail.qq.com/")
                .header("sec-ch-ua", "\"Not=A?Brand\";v=\"99\", \"Google Chrome\";v=\"151\", \"Chromium\";v=\"151\"")
                .header("sec-ch-ua-mobile", "?0")
                .header("sec-ch-ua-platform", "\"Linux\"")
                .header("sec-fetch-dest", "empty")
                .header("sec-fetch-mode", "cors")
                .header("sec-fetch-site", "same-origin")
                .header("user-agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36")
                .POST(HttpRequest.BodyPublishers.ofString("language=zh&r=59115214538391788311085915&sid=zYw1aYwcVGsu6jlBAD9BdQAA"));
        HttpRequest httpRequest = req.build();

        HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        System.out.println(response.statusCode());
        System.out.println(response.body());
    }
}
