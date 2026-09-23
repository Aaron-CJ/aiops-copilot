package com.aiops.aiopscopilot.common.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 轻量 API Token 鉴权过滤器（不引入 Spring Security）。
 * <p>
 * 仅在配置了 {@code aiops.security.token}（环境变量 {@code AIOPS_API_TOKEN}）时
 * 由 {@link ApiTokenSecurityConfig} 注册，拦截 {@code /api/*}；
 * 未配置 token 时本过滤器根本不装配，本地开发保持零鉴权裸奔。
 * <p>
 * 放行范围：{@code /actuator/**} 不在拦截 URL 内，Prometheus 抓取免 token。
 * Token 支持三种携带方式：
 * <ol>
 *   <li>{@code Authorization: Bearer <token>}（标准）</li>
 *   <li>{@code X-API-Key: <token>}（curl 调试方便）</li>
 *   <li>查询参数 {@code ?token=<token>}（浏览器 EventSource 无法自定义请求头，供 SSE 接口使用）</li>
 * </ol>
 */
public class ApiTokenAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiTokenAuthFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String HEADER_API_KEY = "X-API-Key";
    private static final String PARAM_TOKEN = "token";

    private final byte[] expectedToken;

    public ApiTokenAuthFilter(String expectedToken) {
        this.expectedToken = expectedToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String presented = extractToken(request);
        if (presented != null && matches(presented)) {
            filterChain.doFilter(request, response);
            return;
        }
        writeUnauthorized(request, response);
    }

    /** 依次从 Bearer 头、X-API-Key 头、token 查询参数提取，均未提供返回 null。 */
    private String extractToken(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        // RFC 7235 规定 auth-scheme 大小写不敏感（"bearer"/"Bearer" 均应接受）
        if (auth != null && auth.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return auth.substring(BEARER_PREFIX.length()).trim();
        }
        String apiKey = request.getHeader(HEADER_API_KEY);
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey.trim();
        }
        return request.getParameter(PARAM_TOKEN);
    }

    /** 定长比较，避免计时侧信道。 */
    private boolean matches(String presented) {
        return MessageDigest.isEqual(expectedToken, presented.getBytes(StandardCharsets.UTF_8));
    }

    /** 401 响应体沿用全局 Result 结构，便于前端统一解析。 */
    private void writeUnauthorized(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("""
                {"code":401,"message":"未授权：缺少或无效的 API Token","data":null,"success":false}""");
        log.warn("拒绝未授权访问：{} {}", request.getMethod(), request.getRequestURI());
    }
}
