package com.aiops.aiopscopilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AIOps-Copilot 启动入口。
 * <p>
 * {@link EnableScheduling} 必须显式开启：它负责激活 OpsScheduler 的每分钟主动巡检
 * （{@code @Scheduled} 注解在未开启调度时不会生效）。
 */
@SpringBootApplication
@EnableScheduling
public class AiopsCopilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiopsCopilotApplication.class, args);
    }

}
