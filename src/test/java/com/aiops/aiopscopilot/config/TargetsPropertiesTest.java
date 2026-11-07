package com.aiops.aiopscopilot.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TargetsProperties.resolved 的校验与 self 兜底契约——多目标装配期 fail-fast 的规则本体：
 * 坏配置在启动时拒绝（而不是运行期某个目标静默失效或指纹被拼坏）。
 */
class TargetsPropertiesTest {

    private static final String SELF = "aiops-copilot";

    private TargetsProperties propsOf(MonitoredTarget... targets) {
        TargetsProperties props = new TargetsProperties();
        props.setList(new java.util.ArrayList<>(List.of(targets)));
        return props;
    }

    /** 空配置：self 自动兜底在首位（监控者自身失效必须可见，不可删除） */
    @Test
    void emptyConfigEnsuresSelfFirst() {
        List<MonitoredTarget> out = new TargetsProperties().resolved(SELF);
        assertEquals(1, out.size());
        assertEquals(SELF, out.getFirst().name());
        assertEquals(SELF, out.getFirst().application());
    }

    /** 用户已配置 self（application 匹配）时不重复补位，且保持配置顺序 */
    @Test
    void configuredSelfNotDuplicated() {
        List<MonitoredTarget> out = propsOf(
                new MonitoredTarget("app-2", "app-2", null),
                new MonitoredTarget("本人", SELF, null)).resolved(SELF);
        assertEquals(2, out.size());
        assertEquals("app-2", out.get(0).name());
        assertEquals("本人", out.get(1).name());
    }

    /** name 重复：两条同目名目标无法在状态机里区分，fail-fast */
    @Test
    void duplicateNameRejected() {
        TargetsProperties props = propsOf(
                new MonitoredTarget("同", "a-1", null),
                new MonitoredTarget("同", "a-2", null));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> props.resolved(SELF));
        assertTrue(ex.getMessage().contains("name 重复"), ex.getMessage());
    }

    /** application 重复：两个目标指向同一标签，指标无法区分，fail-fast */
    @Test
    void duplicateApplicationRejected() {
        TargetsProperties props = propsOf(
                new MonitoredTarget("一", "app-x", null),
                new MonitoredTarget("二", "app-x", null));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> props.resolved(SELF));
        assertTrue(ex.getMessage().contains("application 重复"), ex.getMessage());
    }

    /** name 含指纹分隔符（; 或 |）：会破坏指纹结构，fail-fast */
    @Test
    void nameWithFingerprintSeparatorRejected() {
        TargetsProperties props = propsOf(new MonitoredTarget("a;b", "app-x", null));
        assertTrue(assertThrows(IllegalStateException.class, () -> props.resolved(SELF))
                .getMessage().contains("指纹分隔符"));

        TargetsProperties props2 = propsOf(new MonitoredTarget("a|b", "app-y", null));
        assertTrue(assertThrows(IllegalStateException.class, () -> props2.resolved(SELF))
                .getMessage().contains("指纹分隔符"));
    }

    /** application 含 PromQL 破坏字符（引号/反斜杠）：会拼坏标签过滤语句，fail-fast */
    @Test
    void applicationWithPromqlBreakingCharsRejected() {
        TargetsProperties props = propsOf(new MonitoredTarget("x", "a\"b", null));
        assertTrue(assertThrows(IllegalStateException.class, () -> props.resolved(SELF))
                .getMessage().contains("不能含"));

        TargetsProperties props2 = propsOf(new MonitoredTarget("y", "a\\b", null));
        assertTrue(assertThrows(IllegalStateException.class, () -> props2.resolved(SELF))
                .getMessage().contains("不能含"));
    }

    /** 空白 name/application：fail-fast */
    @Test
    void blankFieldsRejected() {
        TargetsProperties props = propsOf(new MonitoredTarget("  ", "app-x", null));
        assertTrue(assertThrows(IllegalStateException.class, () -> props.resolved(SELF))
                .getMessage().contains("name 不能为空"));

        TargetsProperties props2 = propsOf(new MonitoredTarget("x", "", null));
        assertTrue(assertThrows(IllegalStateException.class, () -> props2.resolved(SELF))
                .getMessage().contains("application 不能为空"));
    }

    /** self 缺失时自动补位，但用户配置的其它目标原样保留（含指标映射） */
    @Test
    void configuredTargetsPreservedWhenSelfEnsured() {
        MonitoredTarget custom = new MonitoredTarget("订单服务", "order-service",
                Map.of("qpsLast1m", "sum(rate(http_requests_total[1m]))"));
        List<MonitoredTarget> out = propsOf(custom).resolved(SELF);
        assertEquals(2, out.size());
        assertEquals(SELF, out.getFirst().name(), "self 自动兜底在首位");
        assertEquals(custom, out.get(1), "用户配置的目标原样保留");
    }

    /** 指标映射在构造时拷贝为不可变：外部修改原 Map 不影响目标定义 */
    @Test
    void metricsMapCopiedImmutable() {
        Map<String, String> mutable = new HashMap<>();
        mutable.put("cpuUsage", "process_cpu_usage{application=\"x\"}");
        MonitoredTarget t = new MonitoredTarget("x", "x", mutable);
        mutable.clear();
        assertEquals(Map.of("cpuUsage", "process_cpu_usage{application=\"x\"}"), t.metrics());
        assertThrows(UnsupportedOperationException.class, () -> t.metrics().put("a", "b"));
    }
}
