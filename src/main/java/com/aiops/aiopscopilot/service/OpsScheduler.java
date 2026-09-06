package com.aiops.aiopscopilot.service;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.aiops.aiopscopilot.tool.PrometheusTool;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 智能巡检调度器：替代传统 Alertmanager 的"无脑阈值告警"。
 * <p>
 * 核心架构：
 * <ol>
 *   <li>主动巡检（{@link Scheduled}）：每分钟拉 Prometheus 核心指标</li>
 *   <li>智能判断（deepseek-r1）：把指标快照塞 Prompt，让模型判断是否异常 + 给出根因</li>
 *   <li>分级响应：normal → INFO 静默；warning/critical → 结构化告警报告</li>
 * </ol>
 * <p>
 * 与 Alertmanager 的本质差异：Alertmanager 是"硬编码阈值 + 无上下文短信"，
 * 我们是"AI 理解业务语义 + 自带根因分析 + 自带处置建议"。
 */
@Component
public class OpsScheduler {

    private static final Logger log = LoggerFactory.getLogger(OpsScheduler.class);

    private final ChatClient opsAgentClient;
    private final PrometheusTool prometheusTool;
    private final OpsAlertReporter reporter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpsScheduler(@Qualifier("opsAgentClient") ChatClient opsAgentClient,
                        PrometheusTool prometheusTool,
                        OpsAlertReporter reporter) {
        this.opsAgentClient = opsAgentClient;
        this.prometheusTool = prometheusTool;
        this.reporter = reporter;
    }

    /**
     * 每 60 秒主动巡检一次，应用启动 30 秒后首次执行（给 Prometheus 留够抓取周期）。
     * 用 fixedDelay 而非 fixedRate：巡检本身可能耗时 5-10 秒（deepseek-r1 推理），
     * fixedDelay 保证两次巡检"结束→开始"间隔恰好 60 秒，不会任务堆积。
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void runInspection() {
        log.info("[OpsScheduler] 开始巡检...");
        try {
            // 1. 预拉指标快照（不让模型自主查，省 token + 时延）
            Map<String, Object> snapshot = prometheusTool.queryFixedMetrics();

            // 2. 构造结构化 Prompt 让模型判断
            String prompt = buildInspectionPrompt(snapshot);
            String reply = opsAgentClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            // 3. 解析模型输出，分级响应
            handleInspectionResult(reply, snapshot);
        } catch (Exception e) {
            // 巡检自身失败不能让调度器崩——下个周期继续跑
            log.error("[OpsScheduler] 巡检失败: {}", e.getMessage(), e);
        }
        log.info("[OpsScheduler] 巡检周期结束");
    }

    /**
     * 构造巡检 Prompt：把指标快照塞进去，要求模型严格输出 JSON。
     * 判断维度对应 6 条核心指标的异常模式，让模型有据可依。
     */
    private String buildInspectionPrompt(Map<String, Object> snapshot) {
        return "以下是当前系统的核心指标快照（JSON 格式）：\n"
                + toJson(snapshot) + "\n\n"
                + "请基于这些指标判断系统是否异常。考虑：\n"
                + "1) CPU 使用率过高（>0.8 警告，>0.95 严重）\n"
                + "2) 堆内存是否接近 OOM（注意持续增长比绝对值更重要）\n"
                + "3) QPS 是否异常下跌（端口还在但 QPS=0 是应用假死信号）\n"
                + "4) BLOCKED 线程数 > 0 是死锁的强信号\n"
                + "5) 5 分钟内 GC 次数过频（>10 次可能是内存泄漏）\n"
                + "6) 指标值 = -1 表示查询失败，不应判为异常\n\n"
                + "仅输出严格的 JSON（不要 markdown 代码块、不要任何解释文字），格式：\n"
                + "{\"status\":\"normal|warning|critical\","
                + "\"summary\":\"一句话异常摘要（正常时填'各项指标正常'）\","
                + "\"rootCause\":\"根因分析（无异常填 N/A）\","
                + "\"suggestion\":\"处置建议（无异常填 N/A）\"}";
    }

    /**
     * 解析模型输出并路由到 reporter。
     * <p>
     * 健壮性处理：
     * <ul>
     *   <li>剥离 deepseek-r1 可能的 {@code <think>...</think>} 包裹（即使开启思考也能容错）</li>
     *   <li>剥离 markdown 代码块包裹</li>
     *   <li>JSON 解析失败时原文走 ERROR 日志，不让模型胡言乱语搞崩巡检链路</li>
     * </ul>
     */
    private void handleInspectionResult(String reply, Map<String, Object> snapshot) {
        String json = extractJson(reply);
        try {
            JsonNode node = objectMapper.readTree(json);
            String status = node.path("status").asText("unknown");
            String summary = node.path("summary").asText("无摘要");
            String rootCause = node.path("rootCause").asText("N/A");
            String suggestion = node.path("suggestion").asText("N/A");

            if ("normal".equalsIgnoreCase(status)) {
                reporter.logNormal(snapshot);
            } else {
                reporter.report(status, summary, rootCause, suggestion, snapshot, reply);
            }
        } catch (Exception e) {
            // 模型输出不是合法 JSON：原文走 ERROR 日志，便于后续调 prompt
            // 不抛异常——巡检调度器必须比模型更稳定
            log.error("[OpsScheduler] 巡检结果解析失败，模型原始输出：\n{}", reply);
        }
    }

    /** 剥离 {@code <think>} 思考段和 markdown 代码块包裹，提取纯 JSON */
    private String extractJson(String reply) {
        if (reply == null) return "";
        String s = reply.trim();
        // 去除 deepseek-r1 思考过程（即使配置关闭了，加这层兜底更稳）
        int thinkEnd = s.indexOf("</think>");
        if (thinkEnd >= 0) {
            s = s.substring(thinkEnd + "</think>".length()).trim();
        }
        // 去除 markdown 代码块包裹
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
