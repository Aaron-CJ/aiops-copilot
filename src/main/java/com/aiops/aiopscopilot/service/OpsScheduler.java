package com.aiops.aiopscopilot.service;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.aiops.aiopscopilot.tool.PrometheusTool;
import com.aiops.aiopscopilot.tool.SystemHealthTools;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 智能巡检调度器：替代传统 Alertmanager 的"无脑阈值告警"。
 * <p>
 * 核心架构：
 * <ol>
 *   <li>主动巡检（{@link Scheduled}）：每分钟拉 Prometheus 核心指标</li>
 *   <li>智能判断（qwen3:8b，关闭思考链）：把指标快照塞 Prompt，让模型判断是否异常 + 给出根因</li>
 *   <li>分级响应：normal → INFO 静默；warning/critical → 结构化告警报告</li>
 * </ol>
 * <p>
 * 与 Alertmanager 的本质差异：阈值只能回答"超没超"，这里输出的是
 * "AI 理解业务语义 + 根因分析 + 处置建议"。
 * <p>
 * 为什么巡检用 qwen3:8b（关闭思考链）而不是 deepseek-r1：
 * 巡检每分钟跑一次，单次延迟必须远小于 60 秒。deepseek-r1:8b 在 CPU 上推理 + 长思考链
 * 单次实测耗时 1.7-6.6 分钟（简单 RAG 问答约 1.7 分钟、中等 RAG 综合约 6.3 分钟、
 * 复杂故障诊断约 2.5 分钟、超复杂 SOP 决策约 6.6 分钟，随 prompt 复杂度大幅波动），
 * 远超 60 秒预算，会导致巡检任务堆积、永远赶不上调度周期。
 * 巡检场景只需"看指标→判异常→输出 JSON"，不需要深度推理，qwen3:8b 关闭思考链后
 * 直出结论、速度快数倍且足够胜任。交互问答（/api/agent/ops）同样统一用 qwen3:8b
 * （opsAgentClient）：巡检与交互共用同一模型，避免 Ollama 单模型驻留下两模型
 * 交替触发反复换载（Ollama 经验值每次约 10-30 秒，本项目未单独测）。
 */
@Component
public class OpsScheduler {

    private static final Logger log = LoggerFactory.getLogger(OpsScheduler.class);

    private final ChatClient inspectorChatClient;
    private final PrometheusTool prometheusTool;
    private final SystemHealthTools systemHealthTools;
    private final OpsAlertReporter reporter;
    private final MetricsService metricsService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpsScheduler(@Qualifier("qwenChatClient") ChatClient inspectorChatClient,
                        PrometheusTool prometheusTool,
                        SystemHealthTools systemHealthTools,
                        OpsAlertReporter reporter,
                        MetricsService metricsService) {
        this.inspectorChatClient = inspectorChatClient;
        this.prometheusTool = prometheusTool;
        this.systemHealthTools = systemHealthTools;
        this.reporter = reporter;
        this.metricsService = metricsService;
    }

    /**
     * 每 60 秒主动巡检一次，应用启动 30 秒后首次执行（给 Prometheus 留够抓取周期）。
     * 用 fixedDelay 而非 fixedRate：巡检含 qwen3:8b 关闭思考链的推理耗时，
     * fixedDelay 保证两次巡检"结束→开始"间隔恰好 60 秒，不会任务堆积。
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void runInspection() {
        log.info("[OpsScheduler] 开始巡检...");
        long start = System.currentTimeMillis();
        try {
            // 1. 预拉指标快照（不让模型自主查，省 token + 时延）
            Map<String, Object> snapshot = prometheusTool.queryFixedMetrics();

            // 2. 二级诊断：当 Prometheus 显示 BLOCKED 线程数 > 0 时，主动调用 ThreadMXBean 检测死锁。
            //    Prometheus 只能告诉我们"有几个阻塞线程"，ThreadMXBean 才能告诉我们
            //    "是否真死锁 + 谁在等谁的锁 + 阻塞在哪个方法"——
            //    这套组合 = Prometheus 是触角，ThreadMXBean 是显微镜。
            Object blocked = snapshot.get("blockedThreads");
            if (blocked instanceof Double && (Double) blocked > 0) {
                snapshot.put("deadlockDiagnosis", systemHealthTools.detectDeadlock());
            }

            // 3. 构造结构化 Prompt 让模型判断
            String prompt = buildInspectionPrompt(snapshot);
            String reply = inspectorChatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            // 4. 解析模型输出，分级响应
            handleInspectionResult(reply, snapshot, start);
        } catch (Exception e) {
            // 巡检自身失败不能让调度器崩——下个周期继续跑
            metricsService.recordInspection("error", System.currentTimeMillis() - start);
            log.error("[OpsScheduler] 巡检失败: {}", e.getMessage(), e);
        }
        log.info("[OpsScheduler] 巡检周期结束");
    }

    /** 把指标快照与 6 条判断维度、死锁诊断要求、严格 JSON 输出格式组装成巡检 Prompt。 */
    private String buildInspectionPrompt(Map<String, Object> snapshot) {
        return "以下是当前系统的核心指标快照（JSON 格式）：\n"
                + toJson(snapshot) + "\n\n"
                + "请基于这些指标判断系统是否异常。考虑：\n"
                + "1) CPU 使用率过高（>0.8 警告，>0.95 严重）\n"
                + "2) 堆内存是否接近 OOM（注意持续增长比绝对值更重要）\n"
                + "3) QPS 是否异常下跌（端口还在但 QPS=0 是应用假死信号）\n"
                + "4) BLOCKED 线程数 > 0 是死锁的强信号；若快照含 deadlockDiagnosis 字段，"
                + "deadlockDetected=true 即确认为死锁，应明确在 rootCause 中写出'存在死锁'，"
                + "deadlockedThreads 列出了死锁线程的名称、状态、等待的锁、锁持有者和栈帧，"
                + "应据此定位到具体方法并给出处置建议（如重启应用、修复某 Controller 的锁顺序）\n"
                + "5) 5 分钟内 GC 次数过频（>10 次可能是内存泄漏）\n"
                + "6) 指标值 = -1 表示查询失败，不应判为异常\n\n"
                + "仅输出严格的 JSON（不要 markdown 代码块、不要任何解释文字），格式：\n"
                + "{\"status\":\"normal|warning|critical\","
                + "\"summary\":\"一句话异常摘要（正常时填'各项指标正常'）\","
                + "\"rootCause\":\"根因分析（无异常填 N/A）\","
                + "\"suggestion\":\"处置建议（无异常填 N/A）\"}";
    }

    /**
     * 解析模型输出（先经 {@link #extractJson} 剥离思考段/markdown 包裹）并按级别路由。
     * JSON 非法时不抛异常——巡检调度器必须比模型更稳定，原文落 ERROR 日志便于调 prompt。
     */
    private void handleInspectionResult(String reply, Map<String, Object> snapshot, long start) {
        long durationMs = System.currentTimeMillis() - start;
        String json = extractJson(reply);
        try {
            JsonNode node = objectMapper.readTree(json);
            String status = node.path("status").asString("unknown");
            String summary = node.path("summary").asString("无摘要");
            String rootCause = node.path("rootCause").asString("N/A");
            String suggestion = node.path("suggestion").asString("N/A");

            metricsService.recordInspection(status, durationMs);

            if ("normal".equalsIgnoreCase(status)) {
                reporter.logNormal(snapshot);
            } else {
                reporter.report(status, summary, rootCause, suggestion, snapshot, reply);
            }
        } catch (Exception e) {
            metricsService.recordInspection("parse_error", durationMs);
            log.error("[OpsScheduler] 巡检结果解析失败，模型原始输出：\n{}", reply);
        }
    }

    /** 剥离 {@code <think>} 思考段和 markdown 代码块包裹，提取纯 JSON */
    private String extractJson(String reply) {
        if (reply == null) return "";
        String s = reply.trim();
        int thinkEnd = s.indexOf("</think>");
        if (thinkEnd >= 0) {
            s = s.substring(thinkEnd + "</think>".length()).trim();
        }
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline > 0) {
                s = s.substring(firstNewline + 1);
            }
            if (s.endsWith("```")) {
                s = s.substring(0, s.length() - 3);
            }
        }
        return s.trim();
    }

    private String toJson(Map<String, Object> snapshot) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot);
        } catch (Exception e) {
            return snapshot.toString();
        }
    }
}
