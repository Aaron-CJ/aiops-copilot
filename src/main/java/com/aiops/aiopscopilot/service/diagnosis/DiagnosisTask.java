package com.aiops.aiopscopilot.service.diagnosis;

import java.time.Instant;
import java.util.Map;

/**
 * 深度诊断任务（不可变，状态流转通过 with* 派生新实例，与 IncidentStore.Incident 同款并发模型）。
 *
 * @param taskId      任务 ID（UUID，提交即返回，供 GET /api/diagnosis/{taskId} 轮询）
 * @param trigger     升级触发原因，见 {@link Trigger}
 * @param fingerprint 关联的巡检事件指纹；手动触发时为 "manual"
 * @param snapshot    提交时刻的 7 条指标快照（含可能的 deadlockDiagnosis）
 * @param note        附加说明（手动触发时为用户的追问；自动触发时为快通道结论摘要）
 * @param status      任务状态
 * @param report      深度模型输出的完整报告（SUCCEEDED 时非空）
 * @param error       失败原因（FAILED 时非空）
 * @param createdAt   入队时间
 * @param startedAt   worker 开始处理时间
 * @param finishedAt  终态时间
 */
public record DiagnosisTask(
        String taskId,
        String trigger,
        String fingerprint,
        Map<String, Object> snapshot,
        String note,
        Status status,
        String report,
        String error,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt) {

    /** PENDING → RUNNING → SUCCEEDED / FAILED */
    public enum Status { PENDING, RUNNING, SUCCEEDED, FAILED }

    /** 4.3 定义的升级触发条件 + 人工手动 */
    public static final class Trigger {
        /** 运维通过 POST /api/diagnosis 手动发起 */
        public static final String MANUAL = "manual";
        /** 快通道 JSON 连续解析失败 */
        public static final String PARSE_FAIL = "parse_fail";
        /** 快通道自报 confidence=low */
        public static final String LOW_CONFIDENCE = "low_confidence";
        /** 同一事件指纹持续超过阈值仍未消除 */
        public static final String PERSISTENT = "persistent";
        /** critical 且处置建议涉及写操作（重启/回滚等） */
        public static final String CRITICAL_WRITE = "critical_write";

        private Trigger() {
        }
    }

    static DiagnosisTask pending(String taskId, String trigger, String fingerprint,
                                 Map<String, Object> snapshot, String note) {
        return new DiagnosisTask(taskId, trigger, fingerprint, snapshot, note,
                Status.PENDING, null, null, Instant.now(), null, null);
    }

    DiagnosisTask withRunning() {
        return new DiagnosisTask(taskId, trigger, fingerprint, snapshot, note,
                Status.RUNNING, null, null, createdAt, Instant.now(), null);
    }

    DiagnosisTask withSuccess(String report) {
        return new DiagnosisTask(taskId, trigger, fingerprint, snapshot, note,
                Status.SUCCEEDED, report, null, createdAt, startedAt, Instant.now());
    }

    DiagnosisTask withFailure(String error) {
        return new DiagnosisTask(taskId, trigger, fingerprint, snapshot, note,
                Status.FAILED, null, error, createdAt, startedAt, Instant.now());
    }
}
