package com.aiops.aiopscopilot.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aiops.aiopscopilot.common.result.Result;
import com.aiops.aiopscopilot.service.diagnosis.DiagnosisService;
import com.aiops.aiopscopilot.service.diagnosis.DiagnosisTask;
import com.aiops.aiopscopilot.service.diagnosis.DiagnosisTask.Trigger;
import com.aiops.aiopscopilot.service.diagnosis.DiagnosisTaskFixtures;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DiagnosisController 纯单测（不起容器）：200/503/404 与 message 归一化。
 */
class DiagnosisControllerTest {

    private DiagnosisService service;
    private DiagnosisController controller;

    @BeforeEach
    void setUp() {
        service = mock(DiagnosisService.class);
        controller = new DiagnosisController(service);
    }

    private DiagnosisTask task(String id, DiagnosisTask.Status status) {
        return switch (status) {
            case PENDING -> DiagnosisTaskFixtures.pending(id, Trigger.PERSISTENT,
                    "CRITICAL|BLOCKED=2;", "关注 CPU");
            case SUCCEEDED -> DiagnosisTaskFixtures.succeeded(id, Trigger.PERSISTENT,
                    "CRITICAL|BLOCKED=2;", "关注 CPU", "# 报告");
            case FAILED -> DiagnosisTaskFixtures.failed(id, Trigger.PERSISTENT,
                    "CRITICAL|BLOCKED=2;", "关注 CPU", "boom");
            case RUNNING -> throw new AssertionError("本控制器测试不构造 RUNNING 中间态");
        };
    }

    @Test
    void submitReturnsTaskViewWith200() {
        when(service.submitManual("查一下内存")).thenReturn(
                task("abc123", DiagnosisTask.Status.PENDING));

        Result<DiagnosisController.TaskView> result = controller.submit("查一下内存");

        assertTrue(result.isSuccess());
        DiagnosisController.TaskView view = result.getData();
        assertEquals("abc123", view.taskId());
        assertEquals("PENDING", view.status());
        assertEquals("persistent", view.trigger());
        assertNotNull(view.createdAt());
        assertNull(view.report());
        assertNull(view.startedAt());
    }

    /** 空串/纯空白 message 归一化为 null，避免把空白当关注点传给 r1 */
    @Test
    void blankMessageIsNormalizedToNull() {
        when(service.submitManual(null)).thenReturn(task("t1", DiagnosisTask.Status.PENDING));

        controller.submit("");
        controller.submit("   ");

        verify(service, times(2)).submitManual(null);
    }

    /** 非空白 message 会被 trim */
    @Test
    void messageIsTrimmed() {
        when(service.submitManual("紧急分析")).thenReturn(task("t2", DiagnosisTask.Status.PENDING));

        controller.submit("  紧急分析  ");

        verify(service).submitManual("紧急分析");
    }

    /** 队列满：service 返回 null → 业务 503 */
    @Test
    void submitWhenQueueFullReturns503() {
        when(service.submitManual(any())).thenReturn(null);

        Result<DiagnosisController.TaskView> result = controller.submit("紧急分析");

        assertFalse(result.isSuccess());
        assertEquals(503, result.getCode());
        assertNull(result.getData());
    }

    @Test
    void getExistingTaskReturnsMappedView() {
        when(service.get("abc")).thenReturn(task("abc", DiagnosisTask.Status.SUCCEEDED));

        Result<DiagnosisController.TaskView> result = controller.get("abc");

        assertTrue(result.isSuccess());
        DiagnosisController.TaskView view = result.getData();
        assertEquals("SUCCEEDED", view.status());
        assertEquals("# 报告", view.report());
        assertNotNull(view.finishedAt());
        assertNotNull(view.startedAt());
    }

    @Test
    void getMissingTaskReturns404() {
        when(service.get("nope")).thenReturn(null);

        Result<DiagnosisController.TaskView> result = controller.get("nope");

        assertFalse(result.isSuccess());
        assertEquals(404, result.getCode());
    }

    /** FAILED 视图：error 有值、report 为 null */
    @Test
    void failedTaskViewCarriesError() {
        when(service.get("f1")).thenReturn(task("f1", DiagnosisTask.Status.FAILED));

        DiagnosisController.TaskView view = controller.get("f1").getData();
        assertEquals("FAILED", view.status());
        assertEquals("boom", view.error());
        assertNull(view.report());
        assertNotNull(view.createdAt());
    }
}
