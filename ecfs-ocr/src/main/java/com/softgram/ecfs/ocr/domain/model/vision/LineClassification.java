package com.softgram.ecfs.ocr.domain.model.vision;

import java.util.List;

/**
 * 선(Line) 분류 결과를 담는 DTO
 * @param horizontalLines 수평선 좌표 목록 ([x1, y1, x2, y2])
 * @param verticalLines   수직선 좌표 목록 ([x1, y1, x2, y2])
 */
public record LineClassification(
        List<double[]> horizontalLines, 
        List<double[]> verticalLines
) {}
