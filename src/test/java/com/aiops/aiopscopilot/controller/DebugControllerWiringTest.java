package com.aiops.aiopscopilot.controller;

import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DebugController 双重门控的装配行为测试（2026-11-13）。
 * <p>
 * {@code @Profile("dev") + @ConditionalOnProperty(name="aiops.debug.enabled", havingValue="true")}
 * 必须同时满足才装配——默认 profile 是 dev，单靠 profile 防不住忘设环境变量的部署
 * 意外暴露 OOM/死锁/CPU 注入端点。用 ApplicationContextRunner 起最小上下文验证四种组合，
 * 不起完整 Spring Boot（无需 Milvus/Ollama）。
 * <p>
 * DebugController 依赖 RequestMappingHandlerMapping（@RestController 的RequestMapping
 * 注册不在此验证——那是 MVC 基建），本类用极简代理配置提供该 Bean。
 */
class DebugControllerWiringTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(MvcSkeletonConfig.class, DebugController.class);

    /** dev + 显式开启：装配（评估演练形态） */
    @Test
    void devProfileAndExplicitSwitchAssemblesController() {
        runner.withPropertyValues("aiops.debug.enabled=true")
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("dev"))
                .run(context -> assertThat(context).hasSingleBean(DebugController.class));
    }

    /** dev 但开关未开（默认形态）：不装配——防"忘设环境变量"暴露的关键场景 */
    @Test
    void devProfileWithoutSwitchDoesNotAssemble() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("dev"))
                .run(context -> assertThat(context).doesNotHaveBean(DebugController.class));
    }

    /** prod + 误开开关：仍不装配（profile 是更外层的红线） */
    @Test
    void prodProfileWithSwitchStillDoesNotAssemble() {
        runner.withPropertyValues("aiops.debug.enabled=true")
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .run(context -> assertThat(context).doesNotHaveBean(DebugController.class));
    }

    /** 无 profile（冷上下文）+ 开关开：仍不装配——profile 条件独立生效 */
    @Test
    void noProfileWithSwitchDoesNotAssemble() {
        runner.withPropertyValues("aiops.debug.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(DebugController.class));
    }

    /** MVC 骨架：仅提供 @RestController 类加载所需的 RequestMappingHandlerMapping 依赖 */
    @Configuration(proxyBeanMethods = false)
    static class MvcSkeletonConfig {
        @Bean
        RequestMappingHandlerMapping requestMappingHandlerMapping() {
            return new RequestMappingHandlerMapping();
        }
    }
}
