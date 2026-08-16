package com.aiops.aiopscopilot.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class TestController {

	/**
	 * 测试接口：返回当前请求线程是否为虚拟线程
	 */
	@GetMapping("/test")
	public Map<String, Object> test() {
		Thread currentThread = Thread.currentThread();
		String threadName = currentThread.getName();
		boolean isVirtual = currentThread.isVirtual();

		System.out.println("当前线程名称: " + threadName);

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("threadName", threadName);
		result.put("isVirtual", isVirtual);
		return result;
	}

}
