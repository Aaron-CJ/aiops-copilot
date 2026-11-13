package com.aiops.aiopscopilot.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.Ordered;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * API Token 鉴权装配：配置项 {@code aiops.security.token} 非空时才注册过滤器。
 * <p>
 * 开关语义而非 profile 语义：本地 / CI 只要不设 {@code AIOPS_API_TOKEN}
 * 就完全无鉴权；任何环境设置了该变量即强制鉴权，避免"忘了切 profile 导致生产裸奔"。
 * <p>
 * 用 {@link TokenConfigured} 条件而非 {@code @ConditionalOnProperty}：后者无 havingValue 时
 * 空字符串也视为匹配，会让"留空=关闭"失效；而 {@code @ConditionalOnExpression} 的 SpEL
 * 单引号字面量遇到含单引号的 token 会直接让启动失败（2026-11-13）。
 * Condition 读 Environment 原始值，token 内容再刁钻也不影响装配判定。
 */
@Configuration
public class ApiTokenSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(ApiTokenSecurityConfig.class);

    /**
     * 无鉴权状态的启动告警（2026-11-13 配套）：token 留空 = /api/** 完全开放。
     * 本地开发这是默认形态（刻意如此），但生产忘设 {@code AIOPS_API_TOKEN} 且又忘设
     * {@code SPRING_PROFILES_ACTIVE=prod} 时，{@code ProdSecurityGuard}（仅 prod 装配）
     * 不会拦截——部署在局域网的应用会带着开放的诊断/Agent 接口静默运行。
     * 这里不能 fail-fast（会破坏本地零配置体验），让状态显式可见是底线。
     */
    public ApiTokenSecurityConfig(@Value("${aiops.security.token:}") String token) {
        if (token == null || token.isBlank()) {
            log.warn("未配置 AIOPS_API_TOKEN，/api/** 处于无鉴权状态——仅限本机开发使用！"
                    + "局域网/生产部署必须设置强随机令牌（prod profile 下缺失会拒绝启动）");
        }
    }

    @Bean
    @Conditional(TokenConfigured.class)
    public FilterRegistrationBean<ApiTokenAuthFilter> apiTokenAuthFilterRegistration(
            @Value("${aiops.security.token}") String token) {
        FilterRegistrationBean<ApiTokenAuthFilter> registration =
                new FilterRegistrationBean<>(new ApiTokenAuthFilter(token));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("apiTokenAuthFilter");
        return registration;
    }

    /**
     * token 非空才装配过滤器的条件（Environment 直读，无 SpEL 引号陷阱）。
     */
    static class TokenConfigured implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String token = context.getEnvironment().getProperty("aiops.security.token");
            return token != null && !token.isBlank();
        }
    }
}
