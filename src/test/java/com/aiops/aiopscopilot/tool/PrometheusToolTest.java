package com.aiops.aiopscopilot.tool;

import java.lang.reflect.Method;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PrometheusTool.parseValue 私有方法的反射单测。
 * <p>
 * 为什么单独锁这个 10 行的方法：它的 -1 哨兵是全系统的契约——
 * 巡检 Prompt 第 7 条"指标值 = -1 表示查询失败，不应判为异常"、
 * fallbackByThreshold/applyThresholdBackstop 的 -1 规避、指纹的稳定性都建立在
 * "拿不到值就稳定返回 -1"之上；NaN/空串/脏字符串一旦漏成异常或 0，
 * 会分别导致巡检中断或把"无数据"误判成故障。
 * <p>
 * queryRaw 的 URL 编码方案依赖真实 Prometheus（属集成验证），不在纯单测范围，
 * 其双编码踩坑记录见方法 Javadoc 与学习文档。
 */
class PrometheusToolTest {

    /** 解析失败哨兵，必须与 queryScalar 的失败返回值一致 */
    private static final double SENTINEL = -1.0d;

    private static Method parseValue;
    private static PrometheusTool tool;

    @BeforeAll
    static void setUp() throws Exception {
        // 构造只构建 RestClient，不发起任何网络请求
        tool = new PrometheusTool("http://localhost:9090");
        parseValue = PrometheusTool.class.getDeclaredMethod("parseValue", String.class);
        parseValue.setAccessible(true);
    }

    private static double parse(String s) {
        try {
            return (double) parseValue.invoke(tool, s);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void decimalParsed() {
        assertEquals(0.9375d, parse("0.9375"), 1e-12);
    }

    @Test
    void integerStringParsed() {
        assertEquals(12.0d, parse("12"), 0d);
    }

    /** Prometheus 大计数器以科学计数法返回（如 http 请求总量 7.5497472E7） */
    @Test
    void scientificNotationParsed() {
        assertEquals(7.5497472E7d, parse("7.5497472E7"), 1d);
    }

    @Test
    void nanReturnsSentinel() {
        assertEquals(SENTINEL, parse("NaN"), 0d);
        assertEquals(SENTINEL, parse("nan"), 0d, "NaN 大小写都要认");
    }

    @Test
    void emptyStringReturnsSentinel() {
        assertEquals(SENTINEL, parse(""), 0d);
    }

    @Test
    void garbageReturnsSentinel() {
        assertEquals(SENTINEL, parse("undefined"), 0d);
        assertEquals(SENTINEL, parse("12abc"), 0d);
    }

    @Test
    void nullReturnsSentinel() {
        assertEquals(SENTINEL, parse(null), 0d);
    }
}
