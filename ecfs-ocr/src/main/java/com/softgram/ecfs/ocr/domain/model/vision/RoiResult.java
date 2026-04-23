package com.softgram.ecfs.ocr.domain.model.vision;

import org.opencv.core.Mat;
import org.opencv.core.Rect;

/**
 * 관심 영역(ROI, Region Of Interest) 적용 결과를 담는 레코드
 * @param croppedImage 크롭된 이미지 Mat 객체
 * @param roiRect      원본 이미지 내에서의 실제 ROI 좌표 및 크기
 */
public record RoiResult(
        Mat croppedImage, 
        Rect roiRect
) {}
