package com.alianga;

import org.junit.Test;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 验证 BrotliServer 各接口的响应：Content-Encoding 正确，且响应体可按对应格式解码回原始 JSON。
 */
public class BrotliServerTest {

    private interface Decoder {
        InputStream decode(byte[] compressed) throws Exception;
    }

    @Test
    public void allEncodingsRoundTripToSameJson() throws Exception {
        byte[] expected = BrotliServer.TEST_JSON.getBytes(StandardCharsets.UTF_8);

        Map<String, String> encodings = BrotliServer.encodings();
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(0), 0);
//        BrokerServerTester.addBrForBytes??
    }
}
