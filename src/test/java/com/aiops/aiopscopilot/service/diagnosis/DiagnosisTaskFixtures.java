package com.aiops.aiopscopilot.service.diagnosis;

import java.util.Map;

/**
 * 测试夹具：DiagnosisTask 的 pending/with* 为包级私有，
 * 其他包的测试（如 DiagnosisControllerTest）通过本工厂构造真实 record 实例，
 * 避免 Mockito mock record（record 为 final 值类型，Mockito 明确拒绝）。
 * 时间戳由工厂/状态流转按真实逻辑生成，测试只断言非空与 ISO 格式。
 */
public final class DiagnosisTaskFixtures {

    private DiagnosisTaskFixtures() {
    }

    public static DiagnosisTask pending(String taskId, String trigger, String fingerprint, String note) {
        return DiagnosisTask.pending(taskId, trigger, fingerprint, Map.of(), note);
    }

    public static DiagnosisTask succeeded(String taskId, String trigger, String fingerprint,
                                          String note, String report) {
        return DiagnosisTask.pending(taskId, trigger, fingerprint, Map.of(), note)
                .withRunning().withSuccess(report);
    }

    public static DiagnosisTask failed(String taskId, String trigger, String fingerprint,
                                       String note, String error) {
        return DiagnosisTask.pending(taskId, trigger, fingerprint, Map.of(), note)
                .withRunning().withFailure(error);
    }
}
