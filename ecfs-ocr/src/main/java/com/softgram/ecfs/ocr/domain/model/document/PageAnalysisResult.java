package com.softgram.ecfs.ocr.domain.model.document;

import lombok.Builder;

import java.util.Map;

/**
 * 단일 페이지 분석 결과를 명확한 타입으로 관리하기 위한 내부 DTO.
 */
@Builder
public record PageAnalysisResult(
    int pageNumber,
    String message,
    Object data,
    Map<String, Object> additionalInfo
) {
    public static PageAnalysisResult success(int pageNum, Object data) {
        return PageAnalysisResult.builder()
                .pageNumber(pageNum)
                .data(data)
                .message("Success")
                .build();
    }

    public static PageAnalysisResult fail(int pageNum, String message) {
        return PageAnalysisResult.builder()
                .pageNumber(pageNum)
                .message(message)
                .build();
    }
}
