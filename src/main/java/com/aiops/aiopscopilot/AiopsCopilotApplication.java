package com.aiops.aiopscopilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AiopsCopilotApplication {

	public static void main(String[] args) {
		SpringApplication.run(AiopsCopilotApplication.class, args);
	}

}
