package com.alianga.jkit.http;

import com.alianga.jkit.HttpUtils;
import com.alianga.jkit.jdk.JdkUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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
import java.util.concurrent.CountDownLatch;
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
        String path = HttpUtils.getFileFromHttpDataBySyn(baseUrl + "/file", "saved.bin", workDir.getAbsolutePath());
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
    public void downloadAsyncWithCallback() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> path = new AtomicReference<String>();
        AtomicReference<IOException> error = new AtomicReference<IOException>();
        AtomicLong processed = new AtomicLong();
        HttpUtils.getFileFromHttpDataByAsyn(baseUrl + "/file", "async.bin", workDir.getAbsolutePath(),
                new HttpCallBack<String>() {
                    @Override
                    public void onFailure(HttpCall call, IOException e) {
                        error.set(e);
                        latch.countDown();
                    }

                    @Override
                    public void onProcess(long process, long total) {
                        processed.addAndGet(process);
                    }

                    @Override
                    public void onResponse(HttpCall call, HttpResponse response, String result) {
                        path.set(result);
                        latch.countDown();
                    }
                });
        assertTrue("async download timed out", latch.await(15, TimeUnit.SECONDS));
        assertNull(error.get() == null ? null : error.get().toString(), error.get());
        assertNotNull(path.get());
        assertTrue(new File(path.get()).isFile());
        assertTrue(processed.get() > 0);
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
