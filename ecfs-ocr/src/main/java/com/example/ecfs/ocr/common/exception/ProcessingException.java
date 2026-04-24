package com.example.ecfs.ocr.common.exception;

import lombok.Getter;

/**
 * OCR 분석 알고리즘 및 도메인 로직 수행 중 발생하는 예외입니다.
 */
@Getter
public class ProcessingException extends RuntimeException {

    /** 기본 에러 코드 식별자 */
    public static final String DEFAULT_ERROR_CODE = "OCR_GENERAL_ERROR";

    /** 시스템 내부 추적을 위한 에러 코드 */
    private final String errorCode;

    /**
     * 기본 에러 코드를 사용하여 예외를 생성합니다.
     */
    public ProcessingException(String message) {
        this(DEFAULT_ERROR_CODE, message);
    }

    /**
     * 특정 에러 코드와 메시지를 지정하여 예외를 생성합니다.
     */
    public ProcessingException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 기본 에러 코드와 근본 원인을 포함하여 예외를 생성합니다.
     */
    public ProcessingException(String message, Throwable cause) {
        this(DEFAULT_ERROR_CODE, message, cause);
    }

    /**
     * 상세 에러 정보(코드, 메시지, 원인)를 모두 포함하여 예외를 생성합니다.
     */
    public ProcessingException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}
