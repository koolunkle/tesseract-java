package com.softgram.ecfs.ocr.domain.model.analysis;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Builder;

/**
 * 문서의 논리적 구역별 분석 결과물을 담는 공용 모델.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record Section(
    String section,
    int startPage,
    int endPage,
    List<ExtractedData> data
) {
}
