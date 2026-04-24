package com.example.ecfs.ocr.application.port.out;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import com.example.ecfs.ocr.domain.model.analysis.AnalysisPayload;
import com.example.ecfs.ocr.domain.model.analysis.SectionRange;

/**
 * 실제 문서 분석 및 OCR 엔진을 호출하기 위한 추상 출력(Outbound) 포트.
 */
public interface EnginePort {

    /**
     * 문서 내의 비즈니스 데이터(표, 텍스트)를 추출합니다.
     */
    AnalysisPayload extractData(OcrEngineRequest request);

    /**
     * 이미지 상태 및 전처리 필요 여부를 정밀 분석합니다.
     */
    AnalysisPayload analyzeQuality(OcrEngineRequest request);

    /**
     * 이미지 내의 선 및 윤곽선 등 물리적 구조를 식별합니다.
     */
    AnalysisPayload analyzeStructure(OcrEngineRequest request);

    /**
     * 포트 호출에 필요한 파라미터들의 묶음을 담당하는 요청 데이터 모델.
     */
    record OcrEngineRequest(
            File file,
            String originalFileName,
            String jobId,
            Set<Integer> processedPages,
            AnalysisPayload previousResult,
            List<SectionRange> existingRanges,
            Consumer<AnalysisPayload> onProgress
    ) {}
}
