package com.aiops.aiopscopilot.common.security;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ApiTokenSecurityConfig.TokenConfigured} 装配条件的单测。
 * <p>
 * 背景（2026-11-13）：旧实现用 {@code @ConditionalOnExpression} 的 SpEL 判断
 * {@code aiops.security.token} 非空，token 值含单引号（如 {@code it's}）时 SpEL
 * 字面量解析直接让应用启动失败。Condition 直读 Environment 原始值，无引号陷阱。
 */
class ApiTokenSecurityConfigTest {

    private final ApiTokenSecurityConfig.TokenConfigured condition =
            new ApiTokenSecurityConfig.TokenConfigured();

    /** 未配置 token：不装配过滤器（本地零配置裸奔形态保持不变） */
    @Test
    void missingTokenDoesNotMatch() {
        StandardEnvironment env = new StandardEnvironment();
        assertFalse(condition.matches(context(env), null), "未配置 token 不得装配");
    }

    /** 纯空白 token 视同未配置（与"留空=关闭"的开关语义一致） */
    @Test
    void blankTokenDoesNotMatch() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(
                new MapPropertySource("t", Map.of("aiops.security.token", "   ")));
        assertFalse(condition.matches(context(env), null), "空白 token 视同未配置");
    }

    /** 含单引号等 SpEL 炸弹字符的 token 必须正常装配（修复目标场景） */
    @Test
    void tokenWithQuoteCharactersMatches() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("t",
                Map.of("aiops.security.token", "it's a 'quoted' token")));
        assertTrue(condition.matches(context(env), null),
                "含单引号的 token 必须正常装配（SpEL 单引号陷阱）");
    }

    /** 正常 token 照常装配 */
    @Test
    void normalTokenMatches() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(
                new MapPropertySource("t", Map.of("aiops.security.token", "strong-token-1")));
        assertTrue(condition.matches(context(env), null));
    }

    /**
     * 最小 ConditionContext：本条件只读 Environment；用 Mockito mock 提供，
     * 避免手写桩为 @NullMarked 契约里非 null 的 getRegistry/getResourceLoader 返回 null。
     */
    private static ConditionContext context(StandardEnvironment env) {
        ConditionContext context = mock(ConditionContext.class);
        when(context.getEnvironment()).thenReturn(env);
        return context;
    }
}
