package com.example.ecfs.ocr.adapter.in.web;

import java.time.LocalDateTime;

import lombok.Builder;

/**
 * 클라이언트에게 전달되는 표준 에러 응답 데이터 모델.
 */
@Builder
public record ErrorResponse(
        int status,
        String code,
        String message,
        LocalDateTime timestamp
) {
    /**
     * 필수 정보를 받아 에러 응답 객체를 생성하는 정적 팩토리 메서드.
     */
    public static ErrorResponse of(int status, String code, String message) {
        return ErrorResponse.builder()
                .status(status)
                .code(code)
                .message(message)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
