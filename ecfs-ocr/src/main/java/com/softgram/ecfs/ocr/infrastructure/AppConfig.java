package com.softgram.ecfs.ocr.infrastructure;

import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.softgram.ecfs.ocr.common.constant.ProcessingConstants;

import lombok.extern.slf4j.Slf4j;

/**
 * 비동기 분석 처리를 위한 시스템 자원 및 스레드 실행 환경을 구성하는 컴포넌트.
 */
@Slf4j
@EnableAsync
@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class AppConfig {

    /**
     * 분석 공정을 병렬로 처리하기 위한 전용 비동기 스레드 풀 엔진을 생성합니다.
     */
    @Bean(name = "ocrTaskExecutor")
    ThreadPoolTaskExecutor ocrTaskExecutor(
            AppProperties properties,
            ThreadPoolTaskExecutorBuilder builder) {

        String threadPrefix = properties.executor().threadPrefix();
        if (threadPrefix == null || threadPrefix.isBlank()) {
            threadPrefix = ProcessingConstants.OCR_EXECUTOR_THREAD_PREFIX;
        }

        ThreadPoolTaskExecutor executor = builder
                .corePoolSize(properties.executor().corePoolSize())
                .maxPoolSize(properties.executor().maxPoolSize())
                .queueCapacity(properties.executor().queueCapacity())
                .threadNamePrefix(threadPrefix)
                .awaitTermination(true)
                .awaitTerminationPeriod(Duration.ofSeconds(60))
                .build();

        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        return executor;
    }
}
