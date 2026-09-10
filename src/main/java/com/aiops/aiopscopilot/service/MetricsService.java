package com.aiops.aiopscopilot.service;

import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 自定义业务指标注册器：让 AIOps 系统监控自身的 AI 运行状况。
 * <p>
 * 所有指标通过 {@link MeterRegistry} 注册，自动经 /actuator/prometheus 端点暴露，
 * Prometheus 已在抓取该端点——无需新增任何组件，AI 巡检 Agent 自己也能查这些指标。
 * <p>
 * 指标命名统一以 {@code aiops_} 前缀，与 JVM/HTTP 等框架指标区分。
 */
@Component
public class MetricsService {

    private final MeterRegistry meterRegistry;

    public MetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 记录一次智能巡检结果。
     *
     * @param status     巡检状态：normal / warning / critical / unknown
     * @param durationMs 巡检耗时（毫秒）
     */
    public void recordInspection(String status, long durationMs) {
        meterRegistry.counter("aiops_inspection_total", "status", status).increment();
        meterRegistry.timer("aiops_inspection_duration_seconds")
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 记录一次 AI 模型调用。
     *
     * @param model      模型名（如 qwen3:8b、deepseek-r1:8b）
     * @param endpoint   调用来源（如 /api/agent/ops、/api/chat、/rag）
     * @param durationMs 调用耗时（毫秒）
     */
    public void recordAIRequest(String model, String endpoint, long durationMs) {
        meterRegistry.counter("aiops_ai_request_total",
                "model", model, "endpoint", endpoint).increment();
        meterRegistry.timer("aiops_ai_response_seconds")
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 记录一次 RAG 知识库检索。
     *
     * @param hit        是否命中（检索到相关片段）
     * @param chunkCount 返回的文本片段数
     * @param durationMs 检索耗时（毫秒）
     */
    public void recordRAGRetrieval(boolean hit, int chunkCount, long durationMs) {
        meterRegistry.counter("aiops_rag_retrieval_total",
                "result", hit ? "hit" : "miss").increment();
        meterRegistry.summary("aiops_rag_retrieval_chunks").record(chunkCount);
        meterRegistry.timer("aiops_rag_retrieval_seconds")
                .record(durationMs, TimeUnit.MILLISECONDS);
    }
}
