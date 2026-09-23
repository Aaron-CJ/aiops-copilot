package com.aiops.aiopscopilot.service;

import java.lang.reflect.Method;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.aiops.aiopscopilot.common.audit.AuditLogger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpsScheduler.extractJson 私有方法的反射单测：模型经常不严格遵守"只输出 JSON"的指令
 * （思考段残留、markdown 代码块包裹），剥离逻辑是解析成功与否的第一道关口，边界必须锁死。
 * <p>
 * 与 IncidentStoreTest 同一手法：测试在同包内，刻意不把生产方法改成包级可见
 * （这是项目既定取舍——不为测试扩大生产 API 面；方法改名时测试会立即红，噪音可接受）。
 * extractJson 不触碰任何实例字段，构造器依赖全传 null（audit 传真实实例保持与其他测试一致）。
 */
class OpsSchedulerExtractJsonTest {

    private static Method extractJson;

    @BeforeAll
    static void setUp() throws Exception {
        extractJson = OpsScheduler.class.getDeclaredMethod("extractJson", String.class);
        extractJson.setAccessible(true);
        // Method 对象可跨实例复用；extractJson 是纯函数字符串处理，不触碰任何实例字段
    }

    private static String extract(String reply) {
        try {
            OpsScheduler scheduler = new OpsScheduler(null, null, null, null, null, new AuditLogger(), null);
            return (String) extractJson.invoke(scheduler, reply);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 纯 JSON：trim 后原样返回 */
    @Test
    void plainJsonPassesThrough() {
        String json = "{\"status\":\"normal\"}";
        assertEquals(json, extract("  " + json + "\n"));
    }

    /** ```json 代码块包裹：剥掉围栏与语言标记 */
    @Test
    void fencedJsonWithLanguageIsUnwrapped() {
        String reply = "```json\n{\"status\":\"warning\"}\n```";
        assertEquals("{\"status\":\"warning\"}", extract(reply));
    }

    /** 无语言标记的 ``` 围栏同样剥掉 */
    @Test
    void fencedJsonWithoutLanguageIsUnwrapped() {
        String reply = "```\n{\"status\":\"critical\"}\n```";
        assertEquals("{\"status\":\"critical\"}", extract(reply));
    }

    /** 只有开头围栏没有结尾围栏：剥掉首行围栏，正文保留（部分模型的残缺输出也要尽力解析） */
    @Test
    void openingFenceWithoutClosingStillStripsHeader() {
        String reply = "```json\n{\"status\":\"warning\"}";
        assertEquals("{\"status\":\"warning\"}", extract(reply));
    }

    /** {@code </think>} 思考段残留：取闭合标签之后的内容 */
    @Test
    void thinkTagRemainderIsStripped() {
        String reply = "模型思考了很多\n</think>\n{\"status\":\"normal\"}";
        assertEquals("{\"status\":\"normal\"}", extract(reply));
    }

    /** 思考段 + 代码块组合：两道剥离都要生效 */
    @Test
    void thinkTagAndFenceCombination() {
        String reply = "<think>思考...</think>\n```json\n{\"status\":\"critical\"}\n```";
        assertEquals("{\"status\":\"critical\"}", extract(reply));
    }

    /** null 入参：返回空串（交给 Jackson 报 parse_error，而不是 NPE） */
    @Test
    void nullReturnsEmptyString() {
        assertEquals("", extract(null));
    }

    /**
     * 普通散文（无思考标签、无围栏）：原样返回——该方法刻意不做"花括号扫描"，
     * 从散文里猜 JSON 反而会吞掉模型原文，parse_error 分支需要完整原文落日志。
     */
    @Test
    void proseWithoutMarkersReturnedAsIs() {
        String prose = "我认为系统目前一切正常。";
        assertEquals(prose, extract(prose));
        assertFalse(extract(prose).startsWith("{"), "不得伪装成 JSON 抽取成功");
    }

    /** 围栏内允许嵌套 ``` 之外的内容；结尾三个反引号之外的空白被 trim */
    @Test
    void trailingWhitespaceAfterFenceIsTrimmed() {
        String reply = "```json\n{\"status\":\"normal\"}\n```\n\n  ";
        assertEquals("{\"status\":\"normal\"}", extract(reply));
        assertTrue(extract(reply).endsWith("}"));
    }
}
