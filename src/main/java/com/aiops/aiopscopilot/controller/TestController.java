package com.aiops.aiopscopilot.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 虚拟线程验证接口：配合 application.yml 的 spring.threads.virtual.enabled=true 使用。
 * 访问 /api/test 后根据返回的 isVirtual 字段确认 Tomcat 请求线程是否已切换为虚拟线程。
 */
@RestController
@RequestMapping("/api")
public class TestController {

    private static final Logger log = LoggerFactory.getLogger(TestController.class);

    @GetMapping("/test")
    public Map<String, Object> test() {
        Thread currentThread = Thread.currentThread();
        String threadName = currentThread.getName();
        boolean isVirtual = currentThread.isVirtual();

        log.info("当前线程名称: {}", threadName);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("threadName", threadName);
        result.put("isVirtual", isVirtual);
        return result;
    }

}
