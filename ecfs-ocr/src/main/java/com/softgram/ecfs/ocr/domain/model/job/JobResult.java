package com.softgram.ecfs.ocr.domain.model.job;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.softgram.ecfs.ocr.domain.model.analysis.AnalysisPayload;
import com.softgram.ecfs.ocr.domain.model.analysis.SectionRange;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * OCR 분석 작업의 실행 상태 및 최종 결과물을 관리하는 불변 도메인 모델.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record JobResult(
        @Schema(example = "e70b0652-93f1-4a2b-bff8-fc3beec30731")
        String jobId,
        
        @Schema(example = "document.pdf")
        String fileName,
        
        @Schema(example = "analyze_data")
        String operation,
        
        @Schema(example = "COMPLETED")
        OcrJobStatus status,
        
        LocalDateTime submittedAt,
        LocalDateTime completedAt,
        AnalysisPayload resultData,
        String errorMessage,
        
        @Schema(example = "[1, 2, 3]")
        Set<Integer> processedPages,
        
        List<SectionRange> sectionRanges
) {
    /**
     * 작업의 현재 진행 상태를 정의하는 열거형.
     */
    public enum OcrJobStatus {
        QUEUED,
        PROCESSING,
        COMPLETED,
        FAILED
    }

    /**
     * 신규 작업을 초기 상태로 생성합니다.
     */
    public static JobResult created(String jobId, String fileName, String operation) {
        return new JobResult(
                jobId,
                fileName,
                operation,
                OcrJobStatus.QUEUED,
                LocalDateTime.now(),
                null,
                null,
                null,
                Set.of(),
                List.of()
        );
    }
}
