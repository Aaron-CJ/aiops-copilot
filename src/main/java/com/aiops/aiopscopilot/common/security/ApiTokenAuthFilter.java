package com.aiops.aiopscopilot.common.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import com.aiops.aiopscopilot.common.result.Result;
import com.aiops.aiopscopilot.common.result.ResultCode;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * 轻量 API Token 鉴权过滤器（不引入 Spring Security）。
 * <p>
 * 仅在配置了 {@code aiops.security.token}（环境变量 {@code AIOPS_API_TOKEN}）时
 * 由 {@link ApiTokenSecurityConfig} 注册，拦截 {@code /api/*}；
 * 未配置 token 时本过滤器根本不装配，本地开发保持零鉴权裸奔。
 * <p>
 * 放行范围：{@code /actuator/**} 不在拦截 URL 内，Prometheus 抓取免 token。
 * Token 支持两种携带方式：
 * <ol>
 *   <li>{@code Authorization: Bearer <token>}（标准）</li>
 *   <li>{@code X-API-Key: <token>}（curl 调试方便）</li>
 * </ol>
 * 查询参数 {@code ?token=<token>} 仅对两个 SSE 端点放行（浏览器 EventSource 无法自定义
 * 请求头）：token 出现在 URL 中会进入网关/访问日志与浏览器历史，属于降级携带方式，
 * 最小权限原则下不应对全部 /api/* 开放。
 */
public class ApiTokenAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiTokenAuthFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String HEADER_API_KEY = "X-API-Key";
    private static final String PARAM_TOKEN = "token";

    /** 允许 ?token= 查询参数的 SSE 端点（EventSource 无法设置请求头的唯一理由） */
    private static final Set<String> SSE_QUERY_TOKEN_PATHS = Set.of("/api/ai/chat", "/api/ai/rag/stream");

    /** Jackson 3 ObjectMapper 线程安全；过滤器手动 new（非 bean），用静态实例序列化 401 响应体 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    /**
     * 依次从 Bearer 头、X-API-Key 头提取；?token= 查询参数仅对 SSE 端点放行，
     * 其余端点返回 null（防止 token 经 URL 泄漏到访问日志/浏览器历史/Referer）。
     */
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
        if (SSE_QUERY_TOKEN_PATHS.contains(request.getRequestURI())) {
            return request.getParameter(PARAM_TOKEN);
        }
        return null;
    }

    /** 定长比较，避免计时侧信道。 */
    private boolean matches(String presented) {
        return MessageDigest.isEqual(expectedToken, presented.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 401 响应体用 ObjectMapper 序列化 {@link Result}——结构与全局响应对象单一事实来源，
     * 避免 Result 演进后手写 JSON 与之漂移。
     */
    private void writeUnauthorized(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(MAPPER.writeValueAsString(
                Result.fail(ResultCode.UNAUTHORIZED.getCode(), "未授权：缺少或无效的 API Token")));
        log.warn("拒绝未授权访问：{} {}", request.getMethod(), request.getRequestURI());
    }
}
