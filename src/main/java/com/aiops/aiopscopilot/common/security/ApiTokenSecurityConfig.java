package com.aiops.aiopscopilot.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * API Token 鉴权装配：配置项 {@code aiops.security.token} 非空时才注册过滤器。
 * <p>
 * 开关语义而非 profile 语义：本地 / CI 只要不设 {@code AIOPS_API_TOKEN}
 * 就完全无鉴权；任何环境设置了该变量即强制鉴权，避免"忘了切 profile 导致生产裸奔"。
 * <p>
 * 用 SpEL 非空判断而非 {@code @ConditionalOnProperty}：后者无 havingValue 时，
 * 空字符串也视为匹配，会让"留空=关闭"失效、意外注册一个空密码过滤器。
 */
@Configuration
public class ApiTokenSecurityConfig {

    @Bean
    @ConditionalOnExpression("!'${aiops.security.token:}'.isEmpty()")
    public FilterRegistrationBean<ApiTokenAuthFilter> apiTokenAuthFilterRegistration(
            @Value("${aiops.security.token}") String token) {
        FilterRegistrationBean<ApiTokenAuthFilter> registration =
                new FilterRegistrationBean<>(new ApiTokenAuthFilter(token));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("apiTokenAuthFilter");
        return registration;
    }
}
