package com.example.ecfs.ocr.domain.model.analysis;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 문서 내 특정 섹션의 페이지 범위를 정의하는 공용 모델.
 */
public record SectionRange(
    @Schema(example = "개인회생채권자목록")
    String sectionName,
    
    @Schema(example = "4")
    int startPage,
    
    @Schema(example = "6")
    int endPage
) {
    public SectionRange {
        if (startPage > endPage) {
            throw new IllegalArgumentException(
                String.format("Invalid range: %d ~ %d", startPage, endPage)
            );
        }
    }

    public boolean contains(int page) {
        return page >= startPage && page <= endPage;
    }
}
