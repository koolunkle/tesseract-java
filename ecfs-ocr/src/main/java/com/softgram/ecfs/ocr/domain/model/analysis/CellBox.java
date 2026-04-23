package com.softgram.ecfs.ocr.domain.model.analysis;

import lombok.Builder;

/**
 * 이미지 상의 물리적 영역 좌표 정의 공용 모델.
 */
@Builder
public record CellBox(
        int x,
        int y,
        int width,
        int height,
        int page) {
    public CellBox {
    }

    public static class CellBoxBuilder {
        private int page = 0;
    }
}
