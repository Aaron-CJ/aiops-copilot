package com.aiops.aiopscopilot.config;

import java.util.Map;

/**
 * 被纳管的监控目标。
 *
 * @param name        目标显示名：进指纹 APP= 前缀与告警报告行，是跨目标事件隔离的依据；
 *                    不得含指纹分隔符（; 和 |，装配期校验）
 * @param application Prometheus 的 application 标签值，用于按目标过滤指标序列。
 *                    目标应用需自行配置 management.metrics.tags.application=<同值>
 *                    （Spring Boot 一行配置，见 README）；缺失时无法从共享的
 *                    Prometheus 中区分该目标的指标
 * @param metrics     可选的"语义 → PromQL"覆盖映射（键 = 快照语义名，见
 *                    PrometheusTool.SNAPSHOT_SEMANTICS；值 = 完整 PromQL）。
 *                    缺省用 JVM/Spring 默认档（Spring Boot 目标零配置兼容）；
 *                    值为空白表示该目标无此语义（快照记 -1 哨兵，AI 按"查询失败"忽略）——
 *                    这是非 JVM 目标（Go/Python/...）接入的方式：配什么查询得什么值，
 *                    没有的语义留空，语言无关
 */
public record MonitoredTarget(String name, String application, Map<String, String> metrics) {

    public MonitoredTarget {
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
    }
}
