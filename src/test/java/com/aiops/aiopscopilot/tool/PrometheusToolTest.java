package com.aiops.aiopscopilot.tool;

import java.lang.reflect.Method;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // ==================== qpsFrom：Java 侧 QPS 减法（多目标快照的 QPS 语义） ====================

    @Test
    void qpsFrom_normalSubtraction() {
        assertEquals(4.0, PrometheusTool.qpsFrom(5.0, 1.0), 1e-9, "总速率 - actuator 基线");
    }

    @Test
    void qpsFrom_actuatorExceedsTotal_clampsToZero() {
        assertEquals(0.0, PrometheusTool.qpsFrom(0.067, 0.067), 1e-9,
                "两侧相等（只有抓取流量）→ 0，不是负数");
        assertTrue(PrometheusTool.qpsFrom(0.05, 0.067) >= 0, "结果钳到 ≥0");
    }

    @Test
    void qpsFrom_totalMissing_sentinel() {
        assertEquals(-1.0, PrometheusTool.qpsFrom(-1.0, 0.0), 1e-9,
                "总速率查询失败（-1）→ 整体 -1，AI 按'查询失败'忽略而非误读为 0 流量");
    }

    @Test
    void qpsFrom_actuatorMissing_treatedAsZero() {
        assertEquals(5.0, PrometheusTool.qpsFrom(5.0, -1.0), 1e-9,
                "基线查询失败按 0 处理（总速率侧已有值，不算整轮失败）");
    }

    @Test
    void resolvePromql_defaultTemplateSubstitutesApplication() {
        String out = PrometheusTool.resolvePromql(null,
                "process_cpu_usage{application=\"{application}\"}", "aiops-copilot");
        assertEquals("process_cpu_usage{application=\"aiops-copilot\"}", out);
    }

    @Test
    void resolvePromql_overrideWinsAndSupportsPlaceholder() {
        String out = PrometheusTool.resolvePromql(
                "sum(rate(http_requests_total{app=\"{application}\"}[1m]))",
                "process_cpu_usage{application=\"{application}\"}", "order-service");
        assertEquals("sum(rate(http_requests_total{app=\"order-service\"}[1m]))", out);
    }

    @Test
    void resolvePromql_overrideWithoutPlaceholderUsedAsIs() {
        String out = PrometheusTool.resolvePromql(
                "my_custom_metric", "process_cpu_usage{application=\"{application}\"}", "any");
        assertEquals("my_custom_metric", out);
    }

    @Test
    void jvmDefaultProfile_coversAllSemanticsExceptQps() {
        // 默认档覆盖除 qpsLast1m（Java 侧减法专属处理）外的全部语义键，
        // 且每条模板都带 {application} 占位符（多目标过滤的前提）
        for (String key : PrometheusTool.SNAPSHOT_SEMANTICS) {
            if (key.equals("qpsLast1m")) {
                assertFalse(PrometheusTool.jvmDefaultProfile().containsKey(key),
                        "qpsLast1m 走 querySnapshot 的 Java 侧减法专属处理");
                continue;
            }
            assertTrue(PrometheusTool.jvmDefaultProfile().containsKey(key), key + " 缺失");
            assertTrue(PrometheusTool.jvmDefaultProfile().get(key).contains("{application}"),
                    key + " 模板未参数化 application");
        }
    }

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
