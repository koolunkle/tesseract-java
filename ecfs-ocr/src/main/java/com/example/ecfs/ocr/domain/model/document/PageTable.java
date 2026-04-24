package com.example.ecfs.ocr.domain.model.document;

import java.util.List;

import com.example.ecfs.ocr.domain.model.analysis.CellBox;

import lombok.Builder;

/**
 * 특정 페이지로부터 추출된 표 형식의 로우(Raw) 데이터 집합체.
 */
@Builder
public record PageTable(
    int pageNumber,
    List<TableData> tables
) {

    /** 표 단위의 정제된 데이터를 담는 모델 */
    @Builder
    public record TableData(
        List<String> headers,
        List<List<String>> rows,
        List<List<CellData>> cellGrid
    ) {
    }

    /** 개별 셀의 텍스트와 좌표 정보를 담는 모델 */
    @Builder
    public record CellData(
        String text,
        CellBox box,
        List<TokenData> tokens
    ) {
    }

    /** 셀 내부의 분절된 단어 단위 정보 */
    @Builder
    public record TokenData(
        String text,
        CellBox box
    ) {
    }
}
