package com.softgram.ecfs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@EnableAsync
@ConfigurationPropertiesScan
@SpringBootApplication(scanBasePackages = "com.softgram.ecfs")
public class EcfsApplication {

	public static void main(String[] args) {
		SpringApplication.run(EcfsApplication.class, args);
	}
}
