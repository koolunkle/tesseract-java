package com.example.ecfs.ocr.domain.model.vision;

import org.opencv.core.Mat;

/**
 * OCR 및 이미지 처리 결과를 담는 DTO
 * @param text     추출된 텍스트 결과 (또는 JSON 문자열)
 * @param image    처리된 전체 이미지 (주로 디버그용)
 * @param roiImage 관심 영역(ROI)만 크롭된 이미지
 */
public record AnalysisResult(
        String text, 
        Mat image, 
        Mat roiImage
) {}
