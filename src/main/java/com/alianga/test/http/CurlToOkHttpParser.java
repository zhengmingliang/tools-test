package com.alianga.test.http;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * curl 命令解析器：把 curl 命令解析为结构化模型，并生成等价的 OkHttp (Java) 调用代码。
 * 纯 JDK 实现，不依赖 OkHttp 本身（产物是代码字符串）。
 *
 * 支持的 curl 选项：
 *   -X/--request、-H/--header、-d/--data/--data-raw/--data-binary/--data-ascii、
 *   --data-urlencode、-F/--form（multipart）、-u/--user（Basic 认证）、
 *   -b/--cookie、-A/--user-agent、-e/--referer、--url、-L/--location、-k/--insecure
 * 其余选项（-s -v -i -o 等）安全忽略。
 *
 * 用法：
 *   java CurlToOkHttpParser "curl -X POST 'https://api.example.com' -H 'Content-Type: application/json' -d '{\"a\":1}'"
 * 不带参数时运行内置示例。
 */
public class CurlToOkHttpParser {

    // ==================== 结构化模型 ====================

    public static class CurlCommand {
        public String method;                                  // null 表示未显式指定
        public String url;
        public final List<String[]> headers = new ArrayList<>(); // {name, value}，保序、允许重名
        public final List<String> dataParts = new ArrayList<>(); // 多个 -d 按 curl 语义用 & 拼接
        public final List<String[]> formFields = new ArrayList<>(); // -F: {name, value}，value 以 @ 开头表示文件
        public String basicAuthUser;
        public String basicAuthPass;
        public boolean followRedirects;
        public boolean insecure;
        public final List<String> warnings = new ArrayList<>(); // 解析期提示（不影响生成）
    }

    // ==================== 入口 ====================

    public static void main(String[] args) throws UnsupportedEncodingException {
        String cmd;
        if (args.length > 0) {
            cmd = String.join(" ", args);
        } else {
            cmd = "curl 'https://api.example.com/v1/chat?from=web' \\\n"
                    + "  -X POST \\\n"
                    + "  -H 'Authorization: Bearer sk-xxxx' \\\n"
                    + "  -H 'Content-Type: application/json' \\\n"
                    + "  -H 'X-Trace-Id: 9f2c' \\\n"
                    + "  -u demo:secret \\\n"
                    + "  --data-raw '{\"model\":\"gpt\",\"messages\":[{\"role\":\"user\"}]}' \\\n"
                    + "  --compressed";
        }

        CurlCommand parsed = parse(cmd);
        System.out.println(generate(parsed));
        if (!parsed.warnings.isEmpty()) {
            System.err.println("--- warnings ---");
            parsed.warnings.forEach(w -> System.err.println(" * " + w));
        }
    }

    // ==================== 解析 ====================

    /** 带值的选项：遇到时下一个 token 是它的参数 */
    private static final Set<String> OPTIONS_WITH_VALUE = new HashSet<>(Arrays.asList(
            "-X", "--request", "-H", "--header", "-d", "--data", "--data-raw",
            "--data-binary", "--data-ascii", "--data-urlencode", "-F", "--form",
            "-u", "--user", "-b", "--cookie", "-A", "--user-agent", "-e", "--referer",
            "--url", "-o", "--output", "-c", "--cookie-jar", "--connect-timeout",
            "--max-time", "-m", "--cacert", "--capath", "--cert", "--key",
            "--retry", "--retry-delay", "-w", "--write-out", "-T", "--upload-file",
            "--resolve", "--proxy", "-x", "-U", "--proxy-user"));

    /** 纯开关选项：直接忽略 */
    private static final Set<String> FLAGS = new HashSet<>(Arrays.asList(
            "-s", "--silent", "-v", "--verbose", "-i", "--include", "-k", "--insecure",
            "-L", "--location", "--compressed", "-S", "--show-error", "-f", "--fail",
            "-g", "--globoff", "-N", "--no-buffer", "--http1.1", "--http2", "-0",
            "--http1.0", "-#", "--progress-bar", "-I", "--head", "-G", "--get"));

    public static CurlCommand parse(String commandLine) throws UnsupportedEncodingException {
        CurlCommand cmd = new CurlCommand();
        List<String> tokens = tokenize(commandLine);
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("空命令");
        }

        int start = 0;
        if (tokens.get(0).equalsIgnoreCase("curl")) {
            start = 1;
        }

        for (int i = start; i < tokens.size(); i++) {
            String t = tokens.get(i);

            // 处理 --opt=value 写法
            String name = t;
            String inlineValue = null;
            if (t.startsWith("--") && t.contains("=")) {
                int eq = t.indexOf('=');
                name = t.substring(0, eq);
                inlineValue = t.substring(eq + 1);
            }

            if (OPTIONS_WITH_VALUE.contains(name)) {
                String value = inlineValue != null ? inlineValue : nextToken(tokens, ++i, name);
                applyOption(cmd, name, value);
            } else if (FLAGS.contains(name)) {
                applyFlag(cmd, name);
            } else if (t.startsWith("-") && t.length() > 1) {
                cmd.warnings.add("未识别的选项已忽略: " + t);
            } else if (!t.isEmpty()) {
                if (cmd.url == null) {
                    cmd.url = t;
                } else {
                    cmd.warnings.add("多余的位置参数已忽略: " + t);
                }
            }
        }

        if (cmd.url == null) {
            throw new IllegalArgumentException("命令中没有找到 URL");
        }
        return cmd;
    }

    private static String nextToken(List<String> tokens, int i, String opt) {
        if (i >= tokens.size()) {
            throw new IllegalArgumentException("选项 " + opt + " 缺少参数值");
        }
        return tokens.get(i);
    }

    private static void applyOption(CurlCommand cmd, String opt, String value) throws UnsupportedEncodingException {
        switch (opt) {
            case "-X":
            case "--request":
                cmd.method = value.toUpperCase(Locale.ROOT);
                break;
            case "-H":
            case "--header": {
                int colon = value.indexOf(':');
                if (colon > 0) {
                    cmd.headers.add(new String[]{value.substring(0, colon).trim(),
                            value.substring(colon + 1).trim()});
                } else {
                    // "-H 'X-Token:'" 这种删除语义的写法，生成时忽略
                    cmd.warnings.add("忽略空值头: " + value);
                }
                break;
            }
            case "-d":
            case "--data":
            case "--data-raw":
            case "--data-binary":
            case "--data-ascii":
                cmd.dataParts.add(value);
                break;
            case "--data-urlencode": {
                // name=value 时只编码 value；否则整体编码（curl 语义）
                int eq = value.indexOf('=');
                if (eq >= 0) {
                    String k = value.substring(0, eq);
                    String v = URLEncoder.encode(value.substring(eq + 1), StandardCharsets.UTF_8.name());
                    cmd.dataParts.add(k + "=" + v);
                } else {
                    cmd.dataParts.add(URLEncoder.encode(value, StandardCharsets.UTF_8.name()));
                }
                break;
            }
            case "-F":
            case "--form": {
                // 形如 name=value / name=@file.png / name=value;type=image/png
                String v = value;
                int semi = v.indexOf(";type=");
                if (semi >= 0) {
                    v = v.substring(0, semi);
                }
                int eq = v.indexOf('=');
                if (eq > 0) {
                    cmd.formFields.add(new String[]{v.substring(0, eq), v.substring(eq + 1)});
                } else {
                    cmd.warnings.add("无法解析的 -F 参数: " + value);
                }
                break;
            }
            case "-u":
            case "--user": {
                int colon = value.indexOf(':');
                cmd.basicAuthUser = colon >= 0 ? value.substring(0, colon) : value;
                cmd.basicAuthPass = colon >= 0 ? value.substring(colon + 1) : "";
                break;
            }
            case "-b":
            case "--cookie":
                cmd.headers.add(new String[]{"Cookie", value});
                break;
            case "-A":
            case "--user-agent":
                cmd.headers.add(new String[]{"User-Agent", value});
                break;
            case "-e":
            case "--referer":
                cmd.headers.add(new String[]{"Referer", value});
                break;
            case "--url":
                cmd.url = value;
                break;
            default:
                cmd.warnings.add("选项已识别但不参与代码生成: " + opt);
        }
    }

    private static void applyFlag(CurlCommand cmd, String flag) {
        switch (flag) {
            case "-L":
            case "--location":
                cmd.followRedirects = true;
                break;
            case "-k":
            case "--insecure":
                cmd.insecure = true;
                break;
            case "--compressed":
                // OkHttp 在未手动设置 Accept-Encoding 时默认透明 gzip，无需处理
                break;
            case "-I":
            case "--head":
                if (cmd.method == null) {
                    cmd.method = "HEAD";
                }
                break;
            default:
                // -s -v -i 等：静默忽略
        }
    }

    // ==================== 词法切分 ====================

    /**
     * 支持：单引号（含 '\'' 转义）、双引号（含 \" \\ 转义）、反斜杠转义、
     * 行尾反斜杠续行。
     */
    static List<String> tokenize(String raw) {
        String s = raw.replace("\\\r\n", " ").replace("\\\n", " ").replace("\r\n", "\n");
        List<String> tokens = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean hasToken = false;
        boolean inSingle = false;
        boolean inDouble = false;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inSingle) {
                if (c == '\'') {
                    // 处理 bash 风格的 '\'' （结束单引号 + 转义单引号 + 重新进入单引号）
                    if (i + 2 < s.length() && s.charAt(i + 1) == '\\' && s.charAt(i + 2) == '\'') {
                        cur.append('\'');
                        i += 2;
                    } else {
                        inSingle = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (inDouble) {
                if (c == '\\' && i + 1 < s.length()) {
                    char n = s.charAt(i + 1);
                    if (n == '"' || n == '\\') { // 双引号内只有这两种转义生效
                        cur.append(n);
                        i++;
                    } else {
                        cur.append(c);
                    }
                } else if (c == '"') {
                    inDouble = false;
                } else {
                    cur.append(c);
                }
            } else {
                if (Character.isWhitespace(c)) {
                    if (hasToken) {
                        tokens.add(cur.toString());
                        cur.setLength(0);
                        hasToken = false;
                    }
                } else if (c == '\'') {
                    inSingle = true;
                    hasToken = true;
                } else if (c == '"') {
                    inDouble = true;
                    hasToken = true;
                } else if (c == '\\' && i + 1 < s.length()) {
                    cur.append(s.charAt(i + 1));
                    i++;
                    hasToken = true;
                } else {
                    cur.append(c);
                    hasToken = true;
                }
            }
        }
        if (hasToken) {
            tokens.add(cur.toString());
        }
        if (inSingle || inDouble) {
            throw new IllegalArgumentException("引号未闭合: " + raw);
        }
        return tokens;
    }

    // ==================== OkHttp 代码生成 ====================

    public static String generate(CurlCommand cmd) {
        String method = cmd.method != null ? cmd.method
                : (!cmd.dataParts.isEmpty() || !cmd.formFields.isEmpty() ? "POST" : "GET");

        // Content-Type 优先取显式头，否则按 curl 默认
        String contentType = null;
        int contentTypeIdx = -1;
        for (int i = 0; i < cmd.headers.size(); i++) {
            if (cmd.headers.get(i)[0].equalsIgnoreCase("Content-Type")) {
                contentType = cmd.headers.get(i)[1];
                contentTypeIdx = i;
                break;
            }
        }
        boolean hasBody = !cmd.dataParts.isEmpty() || !cmd.formFields.isEmpty();
        if (contentType == null && hasBody && cmd.formFields.isEmpty()) {
            contentType = "application/x-www-form-urlencoded"; // curl -d 的默认行为
        }

        StringBuilder sb = new StringBuilder();
        sb.append("import okhttp3.Credentials;\n");
        sb.append("import okhttp3.FormBody;\n");
        sb.append("import okhttp3.MediaType;\n");
        sb.append("import okhttp3.MultipartBody;\n");
        sb.append("import okhttp3.OkHttpClient;\n");
        sb.append("import okhttp3.Request;\n");
        sb.append("import okhttp3.RequestBody;\n");
        sb.append("import okhttp3.Response;\n\n");
        sb.append("public class OkHttpRequestExample {\n\n");
        sb.append("    public static void main(String[] args) throws Exception {\n");

        // client
        if (cmd.followRedirects) {
            sb.append("        OkHttpClient client = new OkHttpClient.Builder()\n");
            sb.append("                .followRedirects(true)\n");
            sb.append("                .build();\n\n");
        } else {
            sb.append("        OkHttpClient client = new OkHttpClient();\n\n");
        }
        if (cmd.insecure) {
            sb.append("        // 注意: 原命令带 -k/--insecure（跳过证书校验）。\n");
            sb.append("        // OkHttp 需自行实现信任所有证书的 X509TrustManager + SSLSocketFactory 后\n");
            sb.append("        // 通过 .sslSocketFactory(...) / .hostnameVerifier(...) 配置，此处未生成。\n\n");
        }

        // body
        String bodyVar = null;
        if (!cmd.formFields.isEmpty()) {
            bodyVar = "multipartBody";
            sb.append("        RequestBody ").append(bodyVar)
                    .append(" = new MultipartBody.Builder()\n");
            sb.append("                .setType(MultipartBody.FORM)\n");
            for (String[] f : cmd.formFields) {
                if (f[1].startsWith("@")) {
                    String path = f[1].substring(1);
                    sb.append("                .addFormDataPart(")
                            .append(javaString(f[0])).append(", ").append(javaString(fileNameOf(path)))
                            .append(", RequestBody.create(new java.io.File(").append(javaString(path))
                            .append("), MediaType.parse(\"application/octet-stream\")))\n");
                } else {
                    sb.append("                .addFormDataPart(")
                            .append(javaString(f[0])).append(", ").append(javaString(f[1])).append(")\n");
                }
            }
            sb.append("                .build();\n\n");
        } else if (!cmd.dataParts.isEmpty()) {
            String data = String.join("&", cmd.dataParts);
            if (contentType != null && contentType.toLowerCase(Locale.ROOT)
                    .startsWith("application/x-www-form-urlencoded")) {
                bodyVar = "formBody";
                sb.append("        FormBody ").append(bodyVar).append(" = new FormBody.Builder()\n");
                for (String[] pair : splitFormPairs(data)) {
                    sb.append("                .add(").append(javaString(pair[0]))
                            .append(", ").append(javaString(pair[1])).append(")\n");
                }
                sb.append("                .build();\n\n");
            } else {
                bodyVar = "body";
                sb.append("        RequestBody body = RequestBody.create(\n");
                sb.append("                ").append(javaString(data)).append(",\n");
                sb.append("                MediaType.parse(").append(javaString(contentType)).append("));\n\n");
            }
        }

        // request
        sb.append("        Request request = new Request.Builder()\n");
        sb.append("                .url(").append(javaString(cmd.url)).append(")\n");
        if ("GET".equals(method) && bodyVar == null) {
            sb.append("                .get()\n");
        } else if ("HEAD".equals(method) && bodyVar == null) {
            sb.append("                .head()\n");
        } else {
            sb.append("                .method(").append(javaString(method)).append(", ")
                    .append(bodyVar != null ? bodyVar : "null").append(")\n");
        }
        for (int i = 0; i < cmd.headers.size(); i++) {
            if (i == contentTypeIdx) {
                continue; // 已作为 body 的 MediaType 使用
            }
            sb.append("                .addHeader(").append(javaString(cmd.headers.get(i)[0]))
                    .append(", ").append(javaString(cmd.headers.get(i)[1])).append(")\n");
        }
        if (cmd.basicAuthUser != null) {
            sb.append("                .addHeader(\"Authorization\", Credentials.basic(")
                    .append(javaString(cmd.basicAuthUser)).append(", ")
                    .append(javaString(cmd.basicAuthPass)).append("))\n");
        }
        sb.append("                .build();\n\n");

        // execute
        sb.append("        try (Response response = client.newCall(request).execute()) {\n");
        sb.append("            System.out.println(response.code());\n");
        sb.append("            if (response.body() != null) {\n");
        sb.append("                System.out.println(response.body().string());\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("    }\n}\n");
        return sb.toString();
    }

    /** 把 a=1&b=2 拆成键值对（值里的 & 无法还原，按 curl 原始拼接语义尽力而为） */
    private static List<String[]> splitFormPairs(String data) {
        List<String[]> pairs = new ArrayList<>();
        for (String seg : data.split("&")) {
            int eq = seg.indexOf('=');
            if (eq >= 0) {
                pairs.add(new String[]{seg.substring(0, eq), seg.substring(eq + 1)});
            } else {
                pairs.add(new String[]{seg, ""});
            }
        }
        return pairs;
    }

    private static String fileNameOf(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** 生成合法的 Java 字符串字面量 */
    static String javaString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    // 保留给测试用的快速自检
    static void selfCheck() {
        Map<String, Integer> cases = new LinkedHashMap<>();
        cases.put("curl https://a.com", 0);
        cases.put("curl -X DELETE 'https://a.com/x' -H 'K: v'", 0);
        cases.put("curl -F 'file=@/tmp/a.png' -F 'name=hi' https://a.com/up", 0);
        cases.forEach((c, v) -> {
            try {
                parse(c);
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
