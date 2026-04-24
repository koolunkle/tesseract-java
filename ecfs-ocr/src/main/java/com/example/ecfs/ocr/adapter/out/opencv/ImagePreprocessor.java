package com.example.ecfs.ocr.adapter.out.opencv;

import java.util.ArrayList;
import java.util.List;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.photo.Photo;
import org.springframework.stereotype.Component;

import com.example.ecfs.ocr.common.util.ImageUtils;
import com.example.ecfs.ocr.domain.model.vision.PreprocessingDecision;
import com.example.ecfs.ocr.domain.model.vision.PreprocessingDecision.PreprocessLevel;

import lombok.extern.slf4j.Slf4j;

/**
 * 이미지 품질을 측정하고 최적의 전처리 공정을 수행하는 컴포넌트.
 */
@Slf4j
@Component
public class ImagePreprocessor {

    /** 밝기 편차 임계값 (Light) */
    private static final double THRESHOLD_STDDEV_LIGHT = 10.0;

    /** 조명 불균형 임계값 (Heavy) */
    private static final double THRESHOLD_STDDEV_HEAVY = 25.0;

    /** 노이즈 밀도 임계값 */
    private static final double THRESHOLD_SPECKLE_DENSITY = 0.001;

    /** 최대 픽셀 값 */
    private static final double MAX_PIXEL_VALUE = 255.0;

    /** 블러 커널 크기 */
    private static final int BLUR_KERNEL_SIZE = 65;

    /** 노이즈 간주 최대 면적 */
    private static final double MAX_SPECKLE_AREA = 10.0;
    
    /** 노이즈 제거 필터 강도 */
    private static final float DENOISE_H = 7.0f;

    /** 템플릿 윈도우 크기 */
    private static final int DENOISE_TEMPLATE_WINDOW = 7;

    /** 검색 영역 크기 */
    private static final int DENOISE_SEARCH_WINDOW = 21;

    /**
     * 이미지의 통계적 수치를 분석하여 전처리 수준을 결정합니다.
     */
    public PreprocessingDecision analyze(Mat grayImage) {
        double backgroundStdDev = calculateBackgroundStdDev(grayImage);
        double speckleDensity = calculateSpeckleDensity(grayImage);

        PreprocessLevel level;
        String reason;

        if (backgroundStdDev > THRESHOLD_STDDEV_HEAVY || speckleDensity > THRESHOLD_SPECKLE_DENSITY) {
            level = PreprocessLevel.HEAVY;
            reason = String.format("High Noise (StdDev: %.2f, Speckle: %.5f)", backgroundStdDev, speckleDensity);
            log.info("Preprocess decision: level={}, reason={}", level, reason);
        } else if (backgroundStdDev > THRESHOLD_STDDEV_LIGHT) {
            level = PreprocessLevel.LIGHT;
            reason = String.format("Moderate Noise (StdDev: %.2f) - Normalization Required", backgroundStdDev);
            log.info("Preprocess decision: level={}, reason={}", level, reason);
        } else {
            level = PreprocessLevel.NONE;
            reason = String.format("Clean Image (StdDev: %.2f)", backgroundStdDev);
            log.debug("Preprocess decision: level={}, reason={}", level, reason);
        }
        
        return new PreprocessingDecision(level, backgroundStdDev, speckleDensity, reason);
    }

    /**
     * 결정된 수준에 따라 전처리를 수행하고 이진화 결과를 반환합니다.
     */
    public Mat process(Mat grayImage, PreprocessingDecision decision) {
        Mat processedMat = new Mat();

        switch (decision.level()) {
            case HEAVY -> {
                log.trace("Applying heavy preprocessing: {}", decision.reason());
                preprocessHeavy(grayImage, processedMat);
            }
            case LIGHT -> {
                log.trace("Applying light preprocessing: {}", decision.reason());
                preprocessLight(grayImage, processedMat);
            }
            case NONE -> {
                log.trace("Applying no preprocessing (Direct binarization)");
                Imgproc.threshold(
                        grayImage, processedMat, 0, MAX_PIXEL_VALUE, 
                        Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU
                );
            }
        }

        return processedMat;
    }

    /**
     * 가우시안 블러를 이용해 배경을 추정하고, 나눗셈(Division) 연산으로 조명 불균형을 보정합니다.
     */
    private void preprocessLight(Mat source, Mat destination) {
        Mat background = null;
        Mat normalized = null;

        try {
            background = new Mat();
            normalized = new Mat();

            // 1. 배경 마스크 생성 (광범위 블러)
            Imgproc.GaussianBlur(source, background, new Size(BLUR_KERNEL_SIZE, BLUR_KERNEL_SIZE), 0);
            
            // 2. 원본과 배경의 차이를 이용한 정규화 (나눗셈 연산)
            Core.divide(source, background, normalized, MAX_PIXEL_VALUE);
            
            // 3. 보정된 이미지 이진화
            Imgproc.threshold(
                    normalized, destination, 0, MAX_PIXEL_VALUE, 
                    Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU
            );
            
        } finally {
            ImageUtils.release(background, normalized);
        }
    }

    /**
     * 비국소 평균(Non-local Means) 알고리즘으로 강력하게 노이즈를 제거한 후 정규화를 진행합니다.
     */
    private void preprocessHeavy(Mat source, Mat destination) {
        Mat denoised = null;
        
        try {
            denoised = new Mat();

            // 이미지의 디테일을 최대한 유지하며 노이즈 성분만 제거
            Photo.fastNlMeansDenoising(
                    source, denoised, DENOISE_H, DENOISE_TEMPLATE_WINDOW, DENOISE_SEARCH_WINDOW
            );
            
            // 제거된 결과물에 대해 2차 조명 보정 수행
            preprocessLight(denoised, destination);
            
        } finally {
            ImageUtils.release(denoised);
        }
    }

    /**
     * 이미지 전체의 밝기 분포를 분석하여 조명 균일도(표준편차)를 계산합니다.
     */
    private double calculateBackgroundStdDev(Mat grayImage) {
        Mat blurred = null;
        MatOfDouble mean = null;
        MatOfDouble stddev = null;

        try {
            blurred = new Mat();
            mean = new MatOfDouble();
            stddev = new MatOfDouble();

            // 중간값 블러로 디테일을 제거하고 거시적인 밝기 흐름 파악
            Imgproc.medianBlur(grayImage, blurred, BLUR_KERNEL_SIZE);
            Core.meanStdDev(blurred, mean, stddev);

            return stddev.get(0, 0)[0];
            
        } finally {
            ImageUtils.release(blurred, mean, stddev);
        }
    }

    /**
     * 이미지 내 작은 반점(Speckle)의 밀도를 측정하여 지저분한 정도를 수치화합니다.
     */
    private double calculateSpeckleDensity(Mat grayImage) {
        Mat binary = null;
        Mat hierarchy = null;
        List<MatOfPoint> contours = new ArrayList<>();

        try {
            binary = new Mat();
            hierarchy = new Mat();

            // 반전 이진화를 통해 노이즈 후보군 추출
            Imgproc.threshold(
                    grayImage, binary, 0, MAX_PIXEL_VALUE, 
                    Imgproc.THRESH_BINARY_INV | Imgproc.THRESH_OTSU
            );
            
            // 독립된 영역(Contour) 탐색
            Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);

            // 특정 면적(MAX_SPECKLE_AREA) 미만의 작은 개체만 필터링하여 카운트
            long speckleCount = contours.stream()
                    .mapToDouble(Imgproc::contourArea)
                    .filter(area -> area > 0 && area < MAX_SPECKLE_AREA)
                    .count();

            double totalPixels = (double) grayImage.rows() * grayImage.cols();
            
            // 전체 픽셀 대비 반점의 개수 비율 반환
            return (totalPixels > 0) ? (speckleCount / totalPixels) : 0.0;
            
        } finally {
            ImageUtils.release(binary, hierarchy);
            contours.forEach(ImageUtils::release); // 각 컨투어 객체 메모리 해제
        }
    }
}
