package com.aiops.aiopscopilot.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ApiTokenAuthFilter 纯单元测试：鉴权是安全关键路径，三种 token 携带方式与拒绝分支都要覆盖。
 * <p>
 * 用 spring-test 的 MockHttpServletRequest/Response/FilterChain，不启动容器。
 * 断言依据：MockFilterChain 只有在 filterChain.doFilter 真正被调用后 getRequest() 才非 null。
 */
class ApiTokenAuthFilterTest {

    private static final String TOKEN = "secret-token-42";

    private final ApiTokenAuthFilter filter = new ApiTokenAuthFilter(TOKEN);

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest("GET", "/api/agent/ops");
    }

    private MockHttpServletResponse pass(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertNotNull(chain.getRequest(), "鉴权通过时必须放行到下游");
        return response;
    }

    private MockHttpServletResponse blocked(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertNull(chain.getRequest(), "鉴权失败时不得放行到下游");
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"code\":401"),
                "401 响应体应沿用全局 Result 结构: " + response.getContentAsString());
        return response;
    }

    /** 未携带任何凭据：401 且不放行 */
    @Test
    void missingTokenYields401() throws Exception {
        blocked(request());
    }

    /** 错误 token：401（定长比较，防计时侧信道的行为由实现保证，这里验证结果语义） */
    @Test
    void wrongTokenYields401() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader("Authorization", "Bearer wrong-token");
        blocked(request);
    }

    /** 标准携带方式：Authorization: Bearer <token> */
    @Test
    void bearerTokenPasses() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader("Authorization", "Bearer " + TOKEN);
        pass(request);
    }

    /** RFC 7235：auth-scheme 大小写不敏感，"bearer" 小写也应接受 */
    @Test
    void bearerSchemeIsCaseInsensitive() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader("Authorization", "bearer " + TOKEN);
        pass(request);
    }

    /** 调试友好方式：X-API-Key 头 */
    @Test
    void apiKeyHeaderPasses() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader("X-API-Key", TOKEN);
        pass(request);
    }

    /** 浏览器 EventSource 无法自定义请求头时的兜底：?token= 查询参数 */
    @Test
    void queryParamTokenPasses() throws Exception {
        MockHttpServletRequest request = request();
        request.setParameter("token", TOKEN);
        pass(request);
    }

    /** Bearer 值首尾空白应被容忍（手工拼头的常见失误） */
    @Test
    void bearerTokenWithSurroundingSpacesPasses() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader("Authorization", "Bearer   " + TOKEN + "  ");
        pass(request);
    }

    /** 只有 "Bearer" 前缀没有值：视为未携带，401 */
    @Test
    void emptyBearerValueYields401() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader("Authorization", "Bearer ");
        blocked(request);
    }
}
