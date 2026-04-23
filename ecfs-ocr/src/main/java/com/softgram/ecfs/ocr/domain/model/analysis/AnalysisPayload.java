package com.softgram.ecfs.ocr.domain.model.analysis;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * OCR 엔진의 다양한 분석 결과를 담는 통합 공용 인터페이스.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
public interface AnalysisPayload {
}
