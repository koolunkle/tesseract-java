package com.softgram.ecfs.ocr.domain.model.analysis;

import java.util.List;
import java.util.Map;

/**
 * 이미지 품질 진단이나 물리적 구조 분석 결과를 담는 페이로드.
 */
public record DiagnosticPayload(List<Map<String, Object>> diagnostics) implements AnalysisPayload {
    public List<Map<String, Object>> getDiagnostics() {
        return diagnostics;
    }
}
