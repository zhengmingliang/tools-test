package com.alianga.test;

import com.alianga.jkit.IdCardGenerator;
import com.alianga.jkit.RandomUtils;
import com.alianga.jkit.math.NumberUtils;
import org.junit.Test;

import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Created by 郑明亮 on 2018/10/1 15:58.
 */

/**
 * @author 郑明亮
 * @version 1.0
 * @description 随机数生成工具类
 */
public class RandomUtilsTest {
    @Test
    public void test() {
        System.out.println(RandomUtils.getRandomIdCard());
        System.out.println(RandomUtils.getRandomPerson());
    }
}
