package com.example.ecfs.ocr.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 외부 구성 설정(YAML)을 객체화하여 모듈의 동작 파라미터를 관리하는 컴포넌트.
 */
@Validated
@ConfigurationProperties(prefix = "ocr")
public record AppProperties(

        /** 분석 엔진이 참조할 학습 데이터 및 리소스 경로. */
        @NotEmpty
        String dataPath,

        /** OCR 수행 시 적용할 주 언어 설정. 기본값: kor */
        @DefaultValue("kor")
        String language,

        /** 비동기 작업 실행을 위한 스레드 풀 환경 구성 정보. */
        @DefaultValue
        ExecutorConfig executor
) {

    /**
     * 비동기 스레드 풀 세부 설정값.
     */
    public record ExecutorConfig(

            /** 기본으로 유지할 상시 활성 스레드 수. */
            @Positive
            @DefaultValue("3")
            int corePoolSize,

            /** 부하 발생 시 확장 가능한 최대 스레드 수. */
            @Positive
            @DefaultValue("5")
            int maxPoolSize,

            /** 작업을 적재할 대기열 크기. */
            @PositiveOrZero
            @DefaultValue("20")
            int queueCapacity,

            /** 스레드 이름 접두사. (기본값은 ProcessingConstants.OCR_EXECUTOR_THREAD_PREFIX 참조) */
            String threadPrefix
    ) {
    }
}
