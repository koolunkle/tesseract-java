package com.example.ecfs.ocr.domain.model.analysis;

import java.util.List;

/**
 * 문서의 논리적 구역별 분석 결과(Section) 목록을 담는 페이로드.
 */
public record SectionPayload(List<Section> sections) implements AnalysisPayload {
    public List<Section> getSections() {
        return sections;
    }
}
