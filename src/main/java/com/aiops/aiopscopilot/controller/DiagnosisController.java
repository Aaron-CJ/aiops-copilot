package com.aiops.aiopscopilot.controller;

import java.time.Instant;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aiops.aiopscopilot.common.result.Result;
import com.aiops.aiopscopilot.common.result.ResultCode;
import com.aiops.aiopscopilot.service.diagnosis.DiagnosisService;
import com.aiops.aiopscopilot.service.diagnosis.DiagnosisTask;

/**
 * 深度诊断任务接口（白皮书 4.2 的手动触发 + 结果拉取）。
 * <p>
 * POST 立即返回 taskId（r1 推理 1.7-6.6 分钟，不能走同步等待）；
 * GET 轮询拿状态与报告。自动升级由 OpsScheduler 在巡检链路内部提交，不经此接口。
 */
@RestController
@RequestMapping("/api/diagnosis")
public class DiagnosisController {

    private final DiagnosisService diagnosisService;

    public DiagnosisController(DiagnosisService diagnosisService) {
        this.diagnosisService = diagnosisService;
    }

    /**
     * 手动提交深度诊断：现场采集一份最新快照，可选附带关注点。
     *
     * @param message 运维人员想让深度模型重点分析的问题（可空）
     */
    @PostMapping
    public Result<TaskView> submit(@RequestParam(required = false, defaultValue = "") String message) {
        DiagnosisTask task = diagnosisService.submitManual(message.isBlank() ? null : message.trim());
        if (task == null) {
            return Result.fail(ResultCode.SERVICE_UNAVAILABLE, "深度诊断队列已满，请稍后再试");
        }
        return Result.success("深度诊断任务已提交，请用 taskId 轮询结果", TaskView.of(task));
    }

    /** 轮询任务状态：PENDING/RUNNING 时 report 为空，SUCCEEDED/FAILED 后可读终态 */
    @GetMapping("/{taskId}")
    public Result<TaskView> get(@PathVariable String taskId) {
        DiagnosisTask task = diagnosisService.get(taskId);
        if (task == null) {
            return Result.fail(ResultCode.NOT_FOUND, "任务不存在或已被清理（终态任务仅保留最近一批）");
        }
        return Result.success(TaskView.of(task));
    }

    /** API 视图：时间用 ISO-8601 字符串，避免不同 Jackson 配置下 Instant 序列化不一致 */
    public record TaskView(String taskId, String trigger, String fingerprint, String status,
                           String report, String error, String note,
                           String createdAt, String startedAt, String finishedAt) {

        static TaskView of(DiagnosisTask t) {
            return new TaskView(t.taskId(), t.trigger(), t.fingerprint(), t.status().name(),
                    t.report(), t.error(), t.note(),
                    iso(t.createdAt()), iso(t.startedAt()), iso(t.finishedAt()));
        }

        private static String iso(Instant instant) {
            return instant == null ? null : instant.toString();
        }
    }
}
