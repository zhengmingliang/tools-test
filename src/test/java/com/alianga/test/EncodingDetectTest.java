package com.alianga.test;

import com.alianga.jkit.EncodingDetect;
import com.alianga.jkit.FileUtils;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class EncodingDetectTest {
    private static final String CN = "中文编码检测：你好，世界！文件读写与配置项。";
    private static final String TW = "這是一段繁體中文測試，包含臺灣香港常用字：的了是不我們。";
    private static final String JP = "こんにちは、世界。日本語のShift_JISとEUC-JPの判定テストです。";
    private static final String KR = "안녕하세요. 한국어 EUC-KR 인코딩 검출 테스트입니다.";
    private static final String ASCII = "hello encoding detect 123";


    @Test
    public void encode() {
        String javaEncode =
                EncodingDetect.getJavaEncode("/home/zml/下载/20260826_001_00003_202607--_销售合同收款记录表.csv");
        System.out.println("javaEncode = " + javaEncode);
        javaEncode =   EncodingDetect.getJavaEncode("/home/zml/文档/20260826_001_销售合同基本信息表_template (副本).csv");
        System.out.println("javaEncode = " + javaEncode);
    }
}
