package com.softgram.ecfs.ocr.domain.model.vision;

/**
 * 이미지 품질 수치를 바탕으로 결정된 전처리 전략 모델.
 */
public record PreprocessingDecision(
        /** 결정된 처리 강도 */ PreprocessLevel level,
        /** 조명 불균일 수치 */ double backgroundStdDev,
        /** 노이즈 밀도 수치 */ double speckleDensity,
        /** 결정 사유 */ String reason
) {
    /**
     * 전처리 알고리즘의 실행 강도 정의.
     */
    public enum PreprocessLevel {
        /** 단순 이진화 */ NONE,
        /** 배경 정규화 */ LIGHT,
        /** 노이즈 제거 및 정규화 */ HEAVY
    }
}
