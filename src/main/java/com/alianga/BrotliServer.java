package com.alianga;

import com.aayushatharva.brotli4j.Brotli4jLoader;
import com.aayushatharva.brotli4j.encoder.Encoder;
import com.github.luben.zstd.Zstd;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 简单的测试 HTTP 服务：在多个路径分别返回、响应体编码为不同压缩格式的接口，
 * 用于对比测试客户端能否正确解码各种 Content-Encoding。
 *
 * <p>服务启动后注册如下接口（--path 指定基础路径，默认为 /data）：</p>
 * <ul>
 *   <li>{base}/br     → Content-Encoding: br     (Brotli)</li>
 *   <li>{base}/gzip   → Content-Encoding: gzip</li>
 *   <li>{base}/deflate→ Content-Encoding: deflate</li>
 *   <li>{base}/zstd   → Content-Encoding: zstd</li>
 * </ul>
 *
 * <p>所有接口返回相同的 JSON 内容（测试用写死数据 {@link #TEST_JSON}）。</p>
 */
public class BrotliServer {

    /** 测试用的 JSON 数据，暂时写死。 */
    public static final String TEST_JSON = "{\"interval\":60,\"captchaCode\":\"SMS\",\"success\":true,"
            + "\"message\":\"您的手机178*****573成功生成验证码，请在5分钟内完成校验\","
            + "\"args\":[\"178*****573\",5],"
            + "\"messageCode\":\"hzero.captcha.phone.success\","
            + "\"captcha\":null,\"captchaKey\":\"U01TQDM3MGJiZGI4YWU0YzQyYzI4YTc0Nzk0ZmQzYWJkNzY1\","
            + "\"failure\":false}";

    /** 支持的编码：路径片段 -> {Content-Encoding 值, 压缩函数}。 */
    private static final Map<String, Encoding> ENCODINGS = new LinkedHashMap<String, Encoding>();

    static {
        // 加载 Brotli 4j 本地库（首次会解压 native 库到临时目录）
        Brotli4jLoader.ensureAvailability();
        register("br", "br", BrotliServer::encodeBrotli);
        register("gzip", "gzip", BrotliServer::encodeGzip);
        register("deflate", "deflate", BrotliServer::encodeDeflate);
        register("zstd", "zstd", BrotliServer::encodeZstd);
    }

    public static void main(String[] args) throws IOException {
        int port = parsePort(args);
        String basePath = parseBasePath(args);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        registerAllRoutes(server, basePath);
        server.setExecutor(null); // 使用默认执行器（单线程）
        server.start();

        System.out.println("压缩对比测试服务已启动:");
        System.out.println("  Content-Type : application/json; charset=utf-8");
        System.out.println("  Vary         : Accept-Encoding");
        for (Map.Entry<String, Encoding> entry : ENCODINGS.entrySet()) {
            System.out.println("  GET http://127.0.0.1:" + port + basePath + "/" + entry.getKey()
                    + "  (Content-Encoding: " + entry.getValue().contentEncoding + ")");
        }
        System.out.println("按 Ctrl+C 停止服务。");
    }

    /**
     * 在指定 HttpServer 上注册所有编码接口，供 main 启动和单元测试复用。
     *
     * @param server   要注册路由的 HttpServer
     * @param basePath 基础路径（如 /data），会挂载 {base}/br 等子路径
     * @return 已注册的路径片段 -> Content-Encoding 值 列表
     */
    public static Map<String, String> registerAllRoutes(HttpServer server, String basePath) {
        String base = basePath == null ? "" : basePath;
        for (Map.Entry<String, Encoding> entry : ENCODINGS.entrySet()) {
            final String path = base + "/" + entry.getKey();
            final Encoding encoding = entry.getValue();
            server.createContext(path, exchange -> handle(exchange, encoding));
        }
        Map<String, String> routes = new LinkedHashMap<String, String>();
        for (Map.Entry<String, Encoding> entry : ENCODINGS.entrySet()) {
            routes.put(entry.getKey(), entry.getValue().contentEncoding);
        }
        return routes;
    }

    /** 返回支持的编码路径片段到 Content-Encoding 值的映射（供客户端/测试参考）。 */
    public static Map<String, String> encodings() {
        Map<String, String> routes = new LinkedHashMap<String, String>();
        for (Map.Entry<String, Encoding> entry : ENCODINGS.entrySet()) {
            routes.put(entry.getKey(), entry.getValue().contentEncoding);
        }
        return routes;
    }

    private static void register(String pathSegment, String contentEncoding, Compressor compressor) {
        ENCODINGS.put(pathSegment, new Encoding(contentEncoding, compressor));
    }

    private static void handle(HttpExchange exchange, Encoding encoding) throws IOException {
        com.sun.net.httpserver.Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Content-Encoding", encoding.contentEncoding);
        headers.set("Vary", "Accept-Encoding");

        byte[] body = encoding.compressor.compress(TEST_JSON.getBytes(StandardCharsets.UTF_8));
        exchange.sendResponseHeaders(200, body.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }

        System.out.println("[" + exchange.getRequestMethod() + " " + exchange.getRequestURI() + "] 200, "
                + encoding.contentEncoding + " body=" + body.length + " bytes");
    }

    /** ==== 压缩实现 ==== */

    /** 使用 Brotli 编码器压缩字节数组（质量 5，速度和压缩率的平衡）。 */
    static byte[] encodeBrotli(byte[] data) {
        Encoder.Parameters params = new Encoder.Parameters().setQuality(5);
        try {
            return Encoder.compress(data, params);
        } catch (IOException e) {
            throw new IllegalStateException("Brotli 压缩失败", e);
        }
    }

    /** 使用 GZIP 压缩字节数组。 */
    static byte[] encodeGzip(byte[] data) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
                gzip.write(data);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("GZIP 压缩失败", e);
        }
    }

    /** 使用 deflate（raw/zlib 头，即 HTTP 标准 deflate）压缩字节数组。 */
    static byte[] encodeDeflate(byte[] data) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (DeflaterOutputStream deflate = new DeflaterOutputStream(out)) {
                deflate.write(data);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Deflate 压缩失败", e);
        }
    }

    /** 使用 Zstandard 压缩字节数组（默认压缩级别 3）。 */
    static byte[] encodeZstd(byte[] data) {
        return Zstd.compress(data, 3);
    }

    /** 从参数解析端口，默认 8080。 */
    private static int parsePort(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--port=")) {
                return Integer.parseInt(arg.substring("--port=".length()));
            }
        }
        return 8080;
    }

    /** 从参数解析基础路径，默认 /data。 */
    private static String parseBasePath(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--path=")) {
                String p = arg.substring("--path=".length());
                return p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
            }
        }
        return "/data";
    }

    /** 压缩函数接口。 */
    private interface Compressor {
        byte[] compress(byte[] data) throws IOException;
    }

    /** 一个路由条目：Content-Encoding 值 + 压缩函数。 */
    private static final class Encoding {
        final String contentEncoding;
        final Compressor compressor;

        Encoding(String contentEncoding, Compressor compressor) {
            this.contentEncoding = contentEncoding;
            this.compressor = compressor;
        }
    }
}