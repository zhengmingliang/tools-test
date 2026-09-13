package com.alianga.test.http;

import com.alianga.jkit.HttpUtils;
import com.alianga.jkit.IOUtils;
import com.alianga.jkit.http.CookieJarImpl;
import com.alianga.jkit.http.CurlRequest;
import com.alianga.jkit.http.HttpCall;
import com.alianga.jkit.http.HttpCallBack;
import com.alianga.jkit.http.HttpEngine;
import com.alianga.jkit.http.HttpEngines;
import com.alianga.jkit.http.HttpRequest;
import com.alianga.jkit.http.HttpResponse;
import com.alianga.jkit.http.SseEvent;
import com.alianga.jkit.http.SseListener;
import com.alianga.jkit.http.UploadInfo;
import com.alianga.jkit.jdk.JdkUtils;
import com.alianga.jkit.json.JSON;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.brotli.dec.BrotliInputStream;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class HttpUtilsTest {
    private static HttpServer server;
    private static String baseUrl;
    private static File workDir;

    @BeforeClass
    public static void startServer() throws IOException {
        workDir = new File("target/http-utils-test");
        workDir.mkdirs();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hello", exchange -> send(exchange, 200, "text/plain; charset=utf-8", "hello"));
        server.createContext("/query", exchange -> {
            String raw = exchange.getRequestURI().getRawQuery();
            send(exchange, 200, "text/plain; charset=utf-8", raw == null ? "" : raw);
        });
        server.createContext("/echo-headers", HttpUtilsTest::echoHeaders);
        server.createContext("/form", HttpUtilsTest::echoBody);
        server.createContext("/json", HttpUtilsTest::echoBody);
        server.createContext("/upload", HttpUtilsTest::echoUpload);
        server.createContext("/file", HttpUtilsTest::sendFile);
        server.createContext("/cookie", HttpUtilsTest::cookie);
        server.createContext("/status", HttpUtilsTest::status);
        server.createContext("/redirect", exchange -> {
            readAll(exchange.getRequestBody());
            exchange.getResponseHeaders().set("Location", "/hello");
            exchange.sendResponseHeaders(302, 0);
            exchange.close();
        });
        server.createContext("/bytes", exchange -> {
            readAll(exchange.getRequestBody());
            byte[] data = new byte[]{0, 1, 2, 3, 127, (byte) 255};
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterClass
    public static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Before
    public void reset() {
        HttpUtils.fakeIp = false;
        HttpUtils.setProxy(null);
        HttpUtils.setCookieJar(new CookieJarImpl());
        HttpUtils.defaultMediaType = "application/json; charset=utf-8";
        HttpUtils.supportHttps();
    }

    @After
    public void restoreEngine() {
        HttpUtils.setEngine(null);
    }

    @Test
    public void getHello() throws Exception {
        assertEquals("hello", HttpUtils.get(baseUrl + "/hello"));
        HttpResponse response = HttpUtils.getResponse(baseUrl + "/hello");
        try {
            assertEquals(200, response.code());
            assertTrue(response.isSuccessful());
            assertEquals("hello", response.body().string());
        } finally {
            response.close();
        }
    }

    @Test
    public void getWithQueryParams() throws Exception {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("name", "张三");
        params.put("age", 18);
        params.put("skip", null);
        params.put("ids", new Object[]{"1", "2"});
        params.put("tags", Arrays.asList("a", "b"));
        String query = HttpUtils.get(baseUrl + "/query", params);
        assertTrue(query.contains("name=" + HttpUtils.encodeValue("张三")));
        assertTrue(query.contains("age=18"));
        assertFalse(query.contains("skip="));
        assertTrue(query.contains("ids=1") && query.contains("ids=2"));
        assertTrue(query.contains("tags=a") && query.contains("tags=b"));
    }

    @Test
    public void getRequestParamString() {
        assertEquals("", HttpUtils.getRequestParamString(null));
        assertEquals("", HttpUtils.getRequestParamString(new HashMap<String, Object>()));
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("q", "a b");
        assertEquals("q=a+b", HttpUtils.getRequestParamString(params));
        assertEquals("x", HttpUtils.encodeValue("x"));
        assertEquals("", HttpUtils.encodeValue(null));
    }

    @Test
    public void getWithExistingQueryAndCustomHeaders() throws Exception {
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("X-Token", "abc");
        headers.put("User-Agent", "jkit-test");
        String body = HttpUtils.get(baseUrl + "/echo-headers?from=1",
                mapOf("k", "v"), headers);
        assertTrue(body.contains("X-Token=abc") || body.toLowerCase().contains("x-token=abc"));
        assertTrue(body.contains("jkit-test"));
        String query = HttpUtils.get(baseUrl + "/query?keep=1", mapOf("k", "v"));
        assertTrue(query.contains("keep=1"));
        assertTrue(query.contains("k=v"));
    }

    @Test
    public void nullUrlThrows() {
        try {
            HttpUtils.getResponse(null, null, null);
            fail("expected exception");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("URL"));
        } catch (IOException e) {
            fail(e.getMessage());
        }
    }

    @Test
    public void postForm() throws Exception {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("name", "Tom");
        params.put("age", 20);
        String body = HttpUtils.getStringFromPost(baseUrl + "/form", params);
        assertTrue(body.contains("application/x-www-form-urlencoded"));
        assertTrue(body.contains("name=Tom"));
        assertTrue(body.contains("age=20"));
        byte[] bytes = HttpUtils.getBytesFromPost(baseUrl + "/form", params);
        assertTrue(new String(bytes, StandardCharsets.UTF_8).contains("name=Tom"));
        InputStream stream = HttpUtils.getInputStreamFromPost(baseUrl + "/form", params);
        assertNotNull(stream);
        assertTrue(new String(readAll(stream), StandardCharsets.UTF_8).contains("name=Tom"));
    }

    @Test
    public void postJsonBody() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("name", "Tom");
        payload.put("age", 18);
        String echoed = HttpUtils.sendRequestBody(baseUrl + "/json", payload);
        assertTrue(echoed.contains("application/json"));
        assertTrue(echoed.contains("\"name\":\"Tom\""));
        assertTrue(echoed.contains("\"age\":18"));

        String raw = HttpUtils.sendRequestBody(baseUrl + "/json", "{\"x\":1}", "application/json; charset=utf-8");
        assertTrue(raw.contains("{\"x\":1}"));

        Map<String, String> headers = new HashMap<String, String>();
        headers.put("X-Trace", "1");
        String withHeader = HttpUtils.sendRequestBody(baseUrl + "/json", "<xml/>", headers, "application/xml");
        assertTrue(withHeader.contains("application/xml"));
        assertTrue(withHeader.contains("<xml/>"));
    }

    @Test
    public void uploadMultipart() throws Exception {
        File file = new File(workDir, "upload.txt");
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write("hello-upload".getBytes(StandardCharsets.UTF_8));
        }
        UploadInfo info = new UploadInfo("file", file.getAbsolutePath(), "demo.txt");
        info.setMediaType("text/plain");
        Map<String, String> extra = new HashMap<String, String>();
        extra.put("token", "secret");
        HttpResponse response = HttpUtils.upload(baseUrl + "/upload", info, extra);
        try {
            assertEquals(200, response.code());
            String body = response.body().string();
            assertTrue(body.contains("filename=\"demo.txt\""));
            assertTrue(body.contains("hello-upload"));
            assertTrue(body.contains("token"));
            assertTrue(body.contains("secret"));
        } finally {
            response.close();
        }
    }

    @Test
    public void downloadSyncAndFileName() throws Exception {
        String path = HttpUtils.getFileFromHttpDataBySyn("https://gh-proxy.org/https://github.com/zhangjh/suyan-site/releases/download/v5.1.0/suyan-5.1.0-Linux.deb", "suyan-5.1.0-Linux.deb", workDir.getAbsolutePath());
        assertNotNull(path);
        File saved = new File(path);
        assertTrue(saved.isFile());
        assertEquals("file-content", new String(Files.readAllBytes(saved.toPath()), StandardCharsets.UTF_8));

        String fromHeader = HttpUtils.getFileName(baseUrl + "/file");
        assertEquals("hello.txt", fromHeader);

        HttpResponse response = HttpUtils.getResponse(baseUrl + "/file");
        try {
            assertEquals("hello.txt", HttpUtils.getFileName(response));
        } finally {
            response.close();
        }
    }

    @Test
    public void download() throws IOException, ExecutionException, InterruptedException {
        String url = "https://github.com/sxyazi/yazi/releases/download/v26.8.15/yazi-x86_64-unknown-linux-gnu.deb";
//        String download =
//                HttpUtils.download(url);
//        System.out.println("download = " + download);
        File file = new File("/opt/softwares/yazi-x86_64-unknown-linux-gnu.deb");
        Future<String> future = HttpUtils.downloadAsync(url, file);
        HttpUtils.downloadAsync(url, file);
        String path = future.get();
        System.out.println("path = " + path);
        HttpRequest request = HttpRequest.delete(url);
    }



    @Test
    public void sseEvents() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        CopyOnWriteArrayList<SseEvent> events = new CopyOnWriteArrayList<SseEvent>();
        Map<String, String> header = new HashMap<>();
        header.put("Authorization", "Bearer sk-QxLm5xjRrSpsdcHf8IgOOR37drlo63er");
        Map<String, Object> body = new HashMap<>();
        HttpCall call = HttpUtils.sseJson("https://token.sensenova.cn/v1/chat/completions", "{\n" +
                "    \"model\": \"sensenova-6.8-flash-lite\",\n" +
                "    \"messages\": [{\"role\": \"user\", \"content\": \"Hello!\"}],\n" +
                "  \"stream\" : true\n" +
                "  }",header, new SseListener() {
            @Override
            public void onEvent(SseEvent event) {
                System.out.println("event = " + event);
                events.add(event);
            }

            @Override
            public void onOpen(HttpResponse response) {
                System.out.println("response = " + response);
            }

            @Override
            public void onError(IOException e) {
                System.out.println("e = " + e);
            }

            @Override
            public void onComment(String comment) {
                System.out.println("comment = " + comment);
            }

            @Override
            public void onClosed() {
                latch.countDown();
            }
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertTrue(call != null);
        assertTrue(events.size() >= 2);
//        assertEquals("hello", events.get(0).getData());
        assertEquals("ping", events.get(1).getEvent());
        String data = events.get(1).getData();
        System.out.println("data = " + data);
    }

    @Test
    public void curlTest() throws IOException {
        String curl = "curl 'https://api.choerodon.com.cn/cbase/choerodon/v1/captcha/send-phone-captcha?phone" +
                "=17897432573' -H 'User-Agent: EasyPostman/v4.2.9' -H 'Accept: */*' -H 'Accept-Encoding: gzip, " +
                "deflate, br' -H 'Connection: keep-alive' -H 'h-menu-id: 0' -H 'h-tenant-id: 0' -H 'pragma: no-cache' -H 'priority: u=1, i'";
        String url = "https://api.choerodon.com.cn/cbase/choerodon/v1/captcha/send-phone-captcha?phone=17897432573";
        Map<String, String> header = new HashMap<>();
        header.put("User-Agent", "EasyPostman/v4.2.9");
        header.put("Accept-Encoding", "gzip, deflate, br");
        header.put("Content-Type", "application/json");
        header.put("Accept", "*/*");
        header.put("Accept-Language", "en-US,en;q=0.5");
        header.put("Connection", "keep-alive");
        header.put("h-menu-id", "0");
        header.put("h-tenant-id", "0");
        header.put("pragma", "no-cache");
        header.put("priority", "u=1, i");

        Response response = top.wys.utils.HttpUtils.getResponse(url, null, header);
        System.out.println("response headers: "+response.headers());
        ResponseBody body = response.body();

        BrotliInputStream brInputStream = new BrotliInputStream(response.body().byteStream());
        System.out.println("result: "+ IOUtils.is2String(brInputStream));
    }
    @Test
    public void downloadAsyncWithCallback() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> path = new AtomicReference<String>();
        AtomicReference<IOException> error = new AtomicReference<IOException>();
        AtomicLong processed = new AtomicLong();
        HttpUtils.downloadAsync( "https://gh-proxy.org/https://github.com/zhangjh/suyan-site/releases/download/v5.1" +
                        ".0/suyan-5.1.0-Linux.deb", "Dworkbuddy_5.3.13_amd64.deb", workDir.getAbsolutePath(),
                new HttpCallBack<String>() {
                    @Override
                    public void onFailure(HttpCall call, IOException e) {
                        error.set(e);
                        latch.countDown();
                    }

                    @Override
                    public void onProcess(long process, long total) {
                        System.out.println("process=" + process + ", total = " + total);
                        processed.addAndGet(process);
                    }

                    @Override
                    public void onResponse(HttpCall call, HttpResponse response, String result) {
                        System.out.println(
                                "call = " + call + ", response = " + response);
                        path.set(result);
                        latch.countDown();
                    }
                });
        assertTrue("async download timed out", latch.await(15, TimeUnit.SECONDS));
        assertNull(error.get() == null ? null : error.get().toString(), error.get());
        assertNotNull(path.get());
        assertTrue(new File(path.get()).isFile());
        assertTrue(processed.get() > 0);
        TimeUnit.SECONDS.sleep(10);
    }

    @Test
    public void cookieJarRoundTrip() throws Exception {
        String first = HttpUtils.get(baseUrl + "/cookie");
        assertEquals("", first);
        String cookieHeader = HttpUtils.get(baseUrl + "/cookie");
        assertTrue(cookieHeader.contains("sid=abc"));

        HttpResponse response = HttpUtils.getResponse(baseUrl + "/cookie");
        try {
            String value = HttpUtils.getCookieValue(response);
            assertTrue(value.contains("sid=abc"));
        } finally {
            response.close();
        }
    }
    @Test
    public void cookieJarRoundTrip2() throws Exception {
        String url = "https://chat.qwen.ai/api/v2/models/";
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("Cookie",
                "cna=11cwIty3JisCAUUhDdkpBDFD; _bl_uid=wLm2UobLbFq67L2v64gqgs321ydI; qwen-theme=light; qwen-locale=zh-CN; _gcl_au=1.1.1394719267.1780554038; sca=e1daacae; cnaui=a56c06cd-8a19-4aa4-822b-3eca0774391b; aui=a56c06cd-8a19-4aa4-822b-3eca0774391b; x-ap=ap-southeast-1; acw_tc=0a03e59317878260255484212e2386c972fdd28b537447cb1029600de0ded1; token=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpZCI6ImE1NmMwNmNkLThhMTktNGFhNC04MjJiLTNlY2EwNzc0MzkxYiIsImxhc3RfcGFzc3dvcmRfY2hhbmdlIjoxNzY5NDczODYxLCJleHAiOjE3ODg0MzA4MzB9.PtEKfFYcrDiqT6Mf-kE8Y0cgIatEhy33jtG5ZQmI5lE; atpsida=eb07cc2cd6ac74c734831197_1787826031_1; xlly_s=1; tfstk=gL1j5xsvSexr4AUYXmzzORUdqMA11zPEDVTO-NhqWIdvfVsNJdLqgrX1FGSXDsRvk33J8ETtMjQx2AT9RKhTIrVRNGTx6qQxMCgJJMn9XxptC0I9MdX2bn-61NjQzkPUTZbDshEUYWyUilbJshFw68RRKjkLQkPUTZHUpEjUYORCmNLMWCLvDdQ8PeLv6AHAX3pJJeov6CITPQL2SmnxMnhJwetJXCdOXaUWS3Lv6CIOyzTifwhWS-Tdl7NwzCW7yHbvVfhOM5vXAaHZ6fCXlK1dk3grz_TXhH9s9I2O1w_OgQ6IX-QBZ6JNw6FsZI7CeQLFoSGW9ZBViIW7qYtRNNCDLCNrHBQ5BMTlMWoXrt7R83QIJXQDG91BMtZoEISfwIxA4SiRbi5VfsR_Z4xAKTSAwC0Ina9OUsvhguhX99b2gOIgLYAfWtIHKT4tEUXAV_9hsmsymX-ICHDsPpc6PHz7PADgysIK0UENpi9vrEKUPzimIKLkPnz7PAmeHUYj7zaSDE1..; isg=BBoataeQJIVPT6sQPdpuWzOMa8k8S54ltLhLBSSTw614l7rRDNxNNX9hZ2MLQxa9; ssxmod_itna2=1-Yq0xc7itG=PCqYKi70eGQG7oD=GO93eDzxC5iO7DuOxjKidqDUnPNDDwpYmqxV0_6xtFBAwAxqexD3r1bALA=DLBm84Dlg4eeEUS0c2ILySnpGWYqGDOl_=bxb10aYeItPRKkeR9xoSulLNkFifVeeKhQQ0gCoXeQl=eh0K5iLieoAQYrnhE3q2PQAddVYdPS44i6oL=3nrUilwUBj2xKcPKu3XUD62rVCUMikq2FMaciLqqUfwYSS6=CDd7m83qeptUCxbMU_vzdch9GnPq3kwgK7wuCm3FTvdNGkUt3k67=7qrjreVM5G5x3ZGDV0K/oxMhsdQG9iDD; ssxmod_itna=1-YqfxuD9DgDnD0AD2DUxeFWxKqYKGjQxryK4GHDyxW9K0C1DLxnRDGdKnqt1pWDBQckD45q7GYbmbZBxGXYe3xiNDAPq0iDCfWQKZC0q2e5ezK2gGob3pLMOXtaG7GT5TQVCtqqQOyuZCQmwtM7Rh4dO_bDGoDbqDyDAtD0qDimj5eDBde7AeqKAeD44dDtbrD3_bDixdDj4GmDGAHqpbLDB=DmqDBn64DAw2k1eDFAnaOEpbbTxDwn=wWAeDEDG3D0_R5K_bPL7ysX1ypxD3Df4GHYyfHTx1DISZT507Dz8yWCnW6EhD8CDDE0eb1LkxGuDDkbMat3qn7aYkFc4HlRHxk7DC=QGN1SdshDt2DrA5=0xoATZDNY0D=ATAhD37DZGQ2h5p0wyjemdvBmxr0eelG3mbQnh3h5TAi3cis2DTiYwxxVixLxEVYYqgTbDW0QtQBEeDWsODWp53GoKYj12xW74o=oiooshf2iUyWDD");
        String first = HttpUtils.get(url, null, headers);
        System.out.println(first);
        HttpResponse response = HttpUtils.getResponse(url,null, headers);
        try {
            String value = HttpUtils.getCookieValue(response);
            System.out.println("value = " + value);
            response = HttpUtils.getResponse(url,null, headers);
            value = HttpUtils.getCookieValue(response);
            System.out.println("value = " + value);
        } finally {
            response.close();
        }
    }
    @Test
    public void cookieJarRoundTrip3() throws Exception {
        String url = "https://chat.qwen.ai/api/v2/models/";
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("Cookie",
                "cna=11cwIty3JisCAUUhDdkpBDFD; _bl_uid=wLm2UobLbFq67L2v64gqgs321ydI; qwen-theme=light; qwen-locale=zh-CN; _gcl_au=1.1.1394719267.1780554038; sca=e1daacae; cnaui=a56c06cd-8a19-4aa4-822b-3eca0774391b; aui=a56c06cd-8a19-4aa4-822b-3eca0774391b; x-ap=ap-southeast-1; acw_tc=0a03e59317878260255484212e2386c972fdd28b537447cb1029600de0ded1; token=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpZCI6ImE1NmMwNmNkLThhMTktNGFhNC04MjJiLTNlY2EwNzc0MzkxYiIsImxhc3RfcGFzc3dvcmRfY2hhbmdlIjoxNzY5NDczODYxLCJleHAiOjE3ODg0MzA4MzB9.PtEKfFYcrDiqT6Mf-kE8Y0cgIatEhy33jtG5ZQmI5lE; atpsida=eb07cc2cd6ac74c734831197_1787826031_1; xlly_s=1; tfstk=gL1j5xsvSexr4AUYXmzzORUdqMA11zPEDVTO-NhqWIdvfVsNJdLqgrX1FGSXDsRvk33J8ETtMjQx2AT9RKhTIrVRNGTx6qQxMCgJJMn9XxptC0I9MdX2bn-61NjQzkPUTZbDshEUYWyUilbJshFw68RRKjkLQkPUTZHUpEjUYORCmNLMWCLvDdQ8PeLv6AHAX3pJJeov6CITPQL2SmnxMnhJwetJXCdOXaUWS3Lv6CIOyzTifwhWS-Tdl7NwzCW7yHbvVfhOM5vXAaHZ6fCXlK1dk3grz_TXhH9s9I2O1w_OgQ6IX-QBZ6JNw6FsZI7CeQLFoSGW9ZBViIW7qYtRNNCDLCNrHBQ5BMTlMWoXrt7R83QIJXQDG91BMtZoEISfwIxA4SiRbi5VfsR_Z4xAKTSAwC0Ina9OUsvhguhX99b2gOIgLYAfWtIHKT4tEUXAV_9hsmsymX-ICHDsPpc6PHz7PADgysIK0UENpi9vrEKUPzimIKLkPnz7PAmeHUYj7zaSDE1..; isg=BBoataeQJIVPT6sQPdpuWzOMa8k8S54ltLhLBSSTw614l7rRDNxNNX9hZ2MLQxa9; ssxmod_itna2=1-Yq0xc7itG=PCqYKi70eGQG7oD=GO93eDzxC5iO7DuOxjKidqDUnPNDDwpYmqxV0_6xtFBAwAxqexD3r1bALA=DLBm84Dlg4eeEUS0c2ILySnpGWYqGDOl_=bxb10aYeItPRKkeR9xoSulLNkFifVeeKhQQ0gCoXeQl=eh0K5iLieoAQYrnhE3q2PQAddVYdPS44i6oL=3nrUilwUBj2xKcPKu3XUD62rVCUMikq2FMaciLqqUfwYSS6=CDd7m83qeptUCxbMU_vzdch9GnPq3kwgK7wuCm3FTvdNGkUt3k67=7qrjreVM5G5x3ZGDV0K/oxMhsdQG9iDD; ssxmod_itna=1-YqfxuD9DgDnD0AD2DUxeFWxKqYKGjQxryK4GHDyxW9K0C1DLxnRDGdKnqt1pWDBQckD45q7GYbmbZBxGXYe3xiNDAPq0iDCfWQKZC0q2e5ezK2gGob3pLMOXtaG7GT5TQVCtqqQOyuZCQmwtM7Rh4dO_bDGoDbqDyDAtD0qDimj5eDBde7AeqKAeD44dDtbrD3_bDixdDj4GmDGAHqpbLDB=DmqDBn64DAw2k1eDFAnaOEpbbTxDwn=wWAeDEDG3D0_R5K_bPL7ysX1ypxD3Df4GHYyfHTx1DISZT507Dz8yWCnW6EhD8CDDE0eb1LkxGuDDkbMat3qn7aYkFc4HlRHxk7DC=QGN1SdshDt2DrA5=0xoATZDNY0D=ATAhD37DZGQ2h5p0wyjemdvBmxr0eelG3mbQnh3h5TAi3cis2DTiYwxxVixLxEVYYqgTbDW0QtQBEeDWsODWp53GoKYj12xW74o=oiooshf2iUyWDD");
        String first = top.wys.utils.HttpUtils.get(url, null, headers);
        System.out.println(first);
        Response response = top.wys.utils.HttpUtils.getResponse(url);
        try {
            String value = top.wys.utils.HttpUtils.getCookieValue(response);
            System.out.println("value = " + value);
        } finally {
            response.close();
        }
    }

    @Test
    public void fakeIpAndDefaultHeaders() throws Exception {
        Map<String, String> defaults = HttpUtils.getDefaultHeaders();
        assertTrue(defaults.containsKey("User-Agent"));
        assertNotNull(defaults.get("User-Agent"));

        HttpUtils.fakeIp = true;
        String headers = HttpUtils.get(baseUrl + "/echo-headers");
        assertTrue(headers.toUpperCase().contains("X-REAL-IP="));
        assertTrue(headers.toUpperCase().contains("X-FORWARDED-FOR="));
    }

    @Test
    public void errorStatusStillReturnsBody() throws Exception {
        HttpResponse response = HttpUtils.getResponse(baseUrl + "/status?code=404");
        try {
            assertEquals(404, response.code());
            assertFalse(response.isSuccessful());
            assertEquals("missing", response.body().string());
        } finally {
            response.close();
        }
    }

    @Test
    public void followRedirect() throws Exception {
        assertEquals("hello", HttpUtils.get(baseUrl + "/redirect"));
    }

    @Test
    public void binaryGetAndPostBytes() throws Exception {
        HttpResponse response = HttpUtils.getResponse(baseUrl + "/bytes");
        try {
            byte[] data = response.body().bytes();
            assertEquals(6, data.length);
            assertEquals((byte) 255, data[5]);
        } finally {
            response.close();
        }
    }

    @Test
    public void proxyFlags() {
        assertFalse(HttpUtils.isUseProxy());
        HttpUtils.setHttpProxy("127.0.0.1", 9);
        assertTrue(HttpUtils.isUseProxy());
        HttpUtils.setSocksProxy("127.0.0.1", 1080);
        assertTrue(HttpUtils.isUseProxy());
        HttpUtils.setProxy(Proxy.NO_PROXY);
        assertTrue(HttpUtils.isUseProxy());
        HttpUtils.setProxy(null);
        assertFalse(HttpUtils.isUseProxy());
    }

    @Test
    public void ignoreSniAndHttpsFlag() {
        String previous = System.getProperty("jsse.enableSNIExtension");
        try {
            HttpUtils.ignoreSNI();
            assertEquals("false", System.getProperty("jsse.enableSNIExtension"));
            HttpUtils.supportHttps();
        } finally {
            if (previous == null) {
                System.clearProperty("jsse.enableSNIExtension");
            } else {
                System.setProperty("jsse.enableSNIExtension", previous);
            }
        }
    }

    @Test
    public void engineSelection() throws Exception {
        String name = HttpUtils.getEngineName();
        assertTrue(name.equals(HttpEngines.URL_CONNECTION) || name.equals(HttpEngines.JDK_HTTP_CLIENT));
        if (JdkUtils.JAVA_VERSION >= 11) {
            HttpEngine jdk = HttpEngines.tryJdkHttpClient();
            assertNotNull("JDK 11+ should load java.net.http engine", jdk);
            assertEquals(HttpEngines.JDK_HTTP_CLIENT, jdk.name());
        }

        HttpEngine previous = HttpUtils.getHttpEngine();
        try {
            HttpUtils.setEngine(HttpEngines.urlConnection());
            assertEquals(HttpEngines.URL_CONNECTION, HttpUtils.getEngineName());
            assertEquals("hello", HttpUtils.get(baseUrl + "/hello"));
            String form = HttpUtils.getStringFromPost(baseUrl + "/form", mapOf("a", "1"));
            assertTrue(form.contains("a=1"));

            HttpEngine jdk = HttpEngines.tryJdkHttpClient();
            if (jdk != null) {
                HttpUtils.setEngine(jdk);
                assertEquals(HttpEngines.JDK_HTTP_CLIENT, HttpUtils.getEngineName());
                assertEquals("hello", HttpUtils.get(baseUrl + "/hello"));
                assertEquals("hello", HttpUtils.get(baseUrl + "/redirect"));
                Map<String, Object> json = new LinkedHashMap<String, Object>();
                json.put("ok", true);
                String echoed = HttpUtils.sendRequestBody(baseUrl + "/json", json);
                assertTrue(echoed.contains("\"ok\":true"));
            }
        } finally {
            HttpUtils.setEngine(previous);
        }
    }

    @Test
    public void contentDispositionRfc5987() throws Exception {
        HttpResponse response = HttpUtils.getResponse(baseUrl + "/file?star=1");
        try {
            assertEquals("测试.txt", HttpUtils.getFileName(response));
        } finally {
            response.close();
        }
    }

    private static Map<String, Object> mapOf(String k, Object v) {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put(k, v);
        return map;
    }

    private static void echoHeaders(HttpExchange exchange) throws IOException {
        readAll(exchange.getRequestBody());
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue().get(0)).append('\n');
        }
        send(exchange, 200, "text/plain; charset=utf-8", builder.toString());
    }

    private static void echoBody(HttpExchange exchange) throws IOException {
        byte[] body = readAll(exchange.getRequestBody());
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        String text = (contentType == null ? "" : contentType) + "\n"
                + new String(body, StandardCharsets.UTF_8);
        send(exchange, 200, "text/plain; charset=utf-8", text);
    }

    private static void echoUpload(HttpExchange exchange) throws IOException {
        byte[] body = readAll(exchange.getRequestBody());
        send(exchange, 200, "text/plain; charset=utf-8", new String(body, StandardCharsets.UTF_8));
    }

    private static void sendFile(HttpExchange exchange) throws IOException {
        readAll(exchange.getRequestBody());
        String query = exchange.getRequestURI().getRawQuery();
        if (query != null && query.contains("star=1")) {
            exchange.getResponseHeaders().set("Content-Disposition",
                    "attachment; filename*=UTF-8''%E6%B5%8B%E8%AF%95.txt");
        } else {
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"hello.txt\"");
        }
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }
        send(exchange, 200, "application/octet-stream", "file-content");
    }

    private static void cookie(HttpExchange exchange) throws IOException {
        readAll(exchange.getRequestBody());
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        exchange.getResponseHeaders().add("Set-Cookie", "sid=abc; Path=/");
        send(exchange, 200, "text/plain; charset=utf-8", cookie == null ? "" : cookie);
    }

    private static void status(HttpExchange exchange) throws IOException {
        readAll(exchange.getRequestBody());
        int code = 200;
        String query = exchange.getRequestURI().getRawQuery();
        if (query != null && query.startsWith("code=")) {
            code = Integer.parseInt(query.substring("code=".length()));
        }
        send(exchange, code, "text/plain; charset=utf-8", code == 404 ? "missing" : "ok");
    }

    private static void send(HttpExchange exchange, int code, String contentType, String body) throws IOException {
        send(exchange, code, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int code, String contentType, byte[] body) throws IOException {
        if (contentType != null) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
        }
        exchange.sendResponseHeaders(code, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
