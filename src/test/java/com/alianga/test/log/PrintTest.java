package com.alianga.test.log;

import com.aayushatharva.brotli4j.common.annotations.Local;
import com.alianga.jkit.Print;

import java.util.Locale;

public class PrintTest {
    public static void main(String[] args) {
//        Locale.setDefault(Locale.ENGLISH);
        Print.normal("123");
        Print.warning("123");
        Print.error("123");
        System.out.println("-------");
        top.wys.utils.Print.normal("123");
        top.wys.utils.Print.warning("123");
        top.wys.utils.Print.error("123");
    }
}
