package com.example.ecfs.ocr.domain.service;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import com.example.ecfs.ocr.domain.model.vision.LineClassification;
import com.example.ecfs.ocr.domain.model.vision.RoiResult;
import com.example.ecfs.ocr.common.exception.ProcessingException;
import com.example.ecfs.ocr.common.util.ImageUtils;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 시각적 윤곽선 처리, 좌표 클러스터링 및 선 분석 로직을 수행하는 엔진.
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE) 
public final class VisionProcessor {

    /** 최소 윤곽선 면적 임계치. */
    private static final double MIN_CONTOUR_AREA = 100.0;
    
    /** 이미지 대비 최대 윤곽선 비율. */
    private static final double MAX_CONTOUR_AREA_RATIO = 0.80;
    
    /** 교점 클러스터링을 위한 거리 임계치 (Pixel). */
    private static final double INTERSECTION_CLUSTERING_DISTANCE = 120.0;

    /** 상단 여백 제거 비율. */
    private static final double ROI_TOP_CROP_RATIO = 0.10;

    /** 하단 여백 제거 비율. */
    private static final double ROI_BOTTOM_CROP_RATIO = 0.10;

    /** 좌측 여백 제거 비율. */
    private static final double ROI_LEFT_CROP_RATIO = 0.05;

    /** 우측 여백 제거 비율. */
    private static final double ROI_RIGHT_CROP_RATIO = 0.05;

    /** 모폴로지 연산을 위한 커널 분할 계수. */
    private static final int KERNEL_DIVISOR = 60;

    /** 팽창 연산 커널 크기. */
    private static final int DILATE_KERNEL_SIZE = 20;

    /** 허프 변환 거리 해상도. */
    private static final double HOUGH_RHO = 1.0;

    /** 허프 변환 각도 해상도. */
    private static final double HOUGH_THETA = Math.PI / 180.0;

    /** 허프 변환 직선 판별 임계치. */
    private static final int HOUGH_THRESHOLD = 150;

    /** 최소 직선 길이. */
    private static final double HOUGH_MIN_LINE_LENGTH = 150.0;

    /** 직선 간 최대 허용 간격. */
    private static final double HOUGH_MAX_LINE_GAP = 30.0;

    /** 선 분류 시 각도 허용 오차. */
    private static final double ANGLE_TOLERANCE = 10.0;

    /** 산재된 좌표들을 거리 기반으로 군집화하여 표 후보 영역을 추출합니다. */
    public static List<List<Point>> clusterPoints(List<Point> points) {
        return clusterIntersections(points, INTERSECTION_CLUSTERING_DISTANCE);
    }

    /** BFS 알고리즘을 사용하여 인접한 교점들을 하나의 그룹으로 묶습니다. */
    private static List<List<Point>> clusterIntersections(List<Point> points, double distanceThreshold) {
        List<List<Point>> clusters = new ArrayList<>();
        
        if (points == null || points.isEmpty()) {
            return clusters;
        }

        boolean[] visited = new boolean[points.size()];
        
        for (int i = 0; i < points.size(); i++) {
            if (visited[i]) {
                continue;
            }

            List<Point> newCluster = new ArrayList<>();
            Queue<Integer> queue = new LinkedList<>();
            queue.add(i);
            visited[i] = true;

            while (!queue.isEmpty()) {
                int currentIndex = queue.poll();
                Point currentPoint = points.get(currentIndex);
                newCluster.add(currentPoint);

                for (int j = 0; j < points.size(); j++) {
                    if (!visited[j]) {
                        Point neighborPoint = points.get(j);
                        double distance = Math.sqrt(Math.pow(currentPoint.x - neighborPoint.x, 2)
                                + Math.pow(currentPoint.y - neighborPoint.y, 2));
                        
                        if (distance < distanceThreshold) {
                            visited[j] = true;
                            queue.add(j);
                        }
                    }
                }
            }
            clusters.add(newCluster);
        }
        
        log.info("Clustered {} intersection points into {} tables.", points.size(), clusters.size());
        return clusters;
    }

    /** 이미지 내에서 비즈니스 데이터가 포함된 것으로 추정되는 윤곽선들을 추출합니다. */
    public static List<MatOfPoint> findContentBlocks(Mat preprocessedImage) {
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();

        Imgproc.findContours(preprocessedImage, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);
        ImageUtils.release(hierarchy);

        double maxArea = (preprocessedImage.width() * preprocessedImage.height()) * MAX_CONTOUR_AREA_RATIO;

        contours.removeIf(contour -> {
            double area = Imgproc.contourArea(contour);
            boolean shouldRemove = area < MIN_CONTOUR_AREA || area > maxArea;
            
            if (shouldRemove) {
                ImageUtils.release(contour);
            }
            
            return shouldRemove;
        });
        
        return contours;
    }

    /** 설정된 고정 비율에 따라 이미지의 주요 관심 영역을 잘라냅니다. */
    public static RoiResult cropToRoi(Mat image) {
        int width = image.width();
        int height = image.height();
        
        int topCrop = (int) (height * ROI_TOP_CROP_RATIO);
        int bottomCrop = (int) (height * ROI_BOTTOM_CROP_RATIO);
        int leftCrop = (int) (width * ROI_LEFT_CROP_RATIO);
        int rightCrop = (int) (width * ROI_RIGHT_CROP_RATIO);

        int roiX = leftCrop;
        int roiY = topCrop;
        int roiWidth = width - leftCrop - rightCrop;
        int roiHeight = height - topCrop - bottomCrop;

        if (roiWidth <= 0 || roiHeight <= 0 || roiX < 0 || roiY < 0) {
            log.warn("Invalid ROI dimensions: w={}, h={}. Cannot crop image.", roiWidth, roiHeight);
            throw new ProcessingException("Failed to calculate valid ROI dimensions for cropping.");
        }

        Rect roiRect = new Rect(roiX, roiY, roiWidth, roiHeight);
        Mat croppedImage = new Mat(image, roiRect).clone();
        
        return new RoiResult(croppedImage, roiRect);
    }

    /** 이미지 내 실제 내용이 담긴 영역을 계산하여 최적의 ROI를 추출합니다. */
    public static RoiResult cropToContentRoi(Mat binaryImage) {
        int preliminaryTopCrop = (int) (binaryImage.height() * 0.20);
        int preliminaryBottomCrop = (int) (binaryImage.height() * 0.20);
        int centralHeight = binaryImage.height() - preliminaryTopCrop - preliminaryBottomCrop;

        if (centralHeight <= 0) {
            preliminaryTopCrop = 0;
            centralHeight = binaryImage.height();
        }
        
        Rect centralBandRect = new Rect(0, preliminaryTopCrop, binaryImage.width(), centralHeight);
        Mat centralBand = new Mat(binaryImage, centralBandRect);

        Mat invertedCentralBand = new Mat();
        Core.bitwise_not(centralBand, invertedCentralBand);

        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();

        Imgproc.findContours(invertedCentralBand, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);
        ImageUtils.release(hierarchy);

        if (contours.isEmpty()) {
            ImageUtils.release(centralBand, invertedCentralBand);
            Rect fullRect = new Rect(0, 0, binaryImage.width(), binaryImage.height());
            return new RoiResult(binaryImage.clone(), fullRect);
        }

        // 모든 윤곽선을 포함하는 하나의 큰 사각형 계산
        Rect combinedRect = Imgproc.boundingRect(contours.get(0));
        
        for (int i = 1; i < contours.size(); i++) {
            Rect r = Imgproc.boundingRect(contours.get(i));
            int newX = Math.min(combinedRect.x, r.x);
            int newY = Math.min(combinedRect.y, r.y);
            int newWidth = Math.max(combinedRect.x + combinedRect.width, r.x + r.width) - newX;
            int newHeight = Math.max(combinedRect.y + combinedRect.height, r.y + r.height) - newY;
            
            combinedRect = new Rect(newX, newY, newWidth, newHeight);
        }

        // ROI 경계가 원본 이미지 크기를 벗어나지 않도록 Math.max/min 처리 적용
        int padding = 20;
        combinedRect.y += preliminaryTopCrop;
        
        int finalX = Math.max(0, combinedRect.x - padding);
        int finalY = Math.max(0, combinedRect.y - padding);
        int finalWidth = Math.min(binaryImage.width() - finalX, combinedRect.width + padding * 2);
        int finalHeight = Math.min(binaryImage.height() - finalY, combinedRect.height + padding * 2);
        
        Rect finalRect = new Rect(finalX, finalY, finalWidth, finalHeight);
        Mat croppedImage = new Mat(binaryImage, finalRect).clone();

        ImageUtils.release(centralBand, invertedCentralBand);
        contours.forEach(MatOfPoint::release);

        return new RoiResult(croppedImage, finalRect);
    }

    /** 허프 변환 알고리즘을 적용하여 이미지 내의 유효한 직선들을 검출합니다. */
    public static Mat detectLinesWithHough(Mat binaryImage) {
        Mat lines = new Mat();
        Mat invertedImage = new Mat();
        Core.bitwise_not(binaryImage, invertedImage);

        int horizontalKernelSize = Math.max(1, invertedImage.width() / KERNEL_DIVISOR);
        int verticalKernelSize = Math.max(1, invertedImage.height() / KERNEL_DIVISOR);

        // 1. 수평선
        Mat horizontalKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(horizontalKernelSize, 1));
        Mat horizontalLinesMat = new Mat();
        Imgproc.morphologyEx(invertedImage, horizontalLinesMat, Imgproc.MORPH_OPEN, horizontalKernel, new Point(-1, -1), 1);

        Mat horizontalDilateKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(DILATE_KERNEL_SIZE, 1));
        Imgproc.dilate(horizontalLinesMat, horizontalLinesMat, horizontalDilateKernel);

        // 2. 수직선
        Mat verticalKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(1, verticalKernelSize));
        Mat verticalLinesMat = new Mat();
        Imgproc.morphologyEx(invertedImage, verticalLinesMat, Imgproc.MORPH_OPEN, verticalKernel, new Point(-1, -1), 1);

        Mat verticalDilateKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(1, DILATE_KERNEL_SIZE));
        Imgproc.dilate(verticalLinesMat, verticalLinesMat, verticalDilateKernel);

        Mat lineMask = new Mat();
        Core.add(horizontalLinesMat, verticalLinesMat, lineMask);

        Imgproc.HoughLinesP(
                lineMask, lines, HOUGH_RHO, HOUGH_THETA, HOUGH_THRESHOLD, 
                HOUGH_MIN_LINE_LENGTH, HOUGH_MAX_LINE_GAP
        );

        ImageUtils.release(
                invertedImage, horizontalKernel, horizontalLinesMat, horizontalDilateKernel,
                verticalKernel, verticalLinesMat, verticalDilateKernel, lineMask
        );

        return lines;
    }

    /** 검출된 직선들의 기울기를 분석하여 수평선과 수직선으로 분류합니다. */
    public static LineClassification classifyLines(Mat lines) {
        List<double[]> horizontalLines = new ArrayList<>();
        List<double[]> verticalLines = new ArrayList<>();

        for (int i = 0; i < lines.rows(); i++) {
            double[] line = lines.get(i, 0);
            double x1 = line[0], y1 = line[1], x2 = line[2], y2 = line[3];
            
            double angle = Math.abs(Math.toDegrees(Math.atan2(y2 - y1, x2 - x1)));

            if (angle < ANGLE_TOLERANCE || angle > (180.0 - ANGLE_TOLERANCE)) {
                horizontalLines.add(line);
            } else if (Math.abs(angle - 90.0) < ANGLE_TOLERANCE) {
                verticalLines.add(line);
            }
        }
        
        log.info("Classified lines: {} horizontal, {} vertical", horizontalLines.size(), verticalLines.size());
        return new LineClassification(horizontalLines, verticalLines);
    }

    /** OpenCV 윤곽선 리스트를 처리하기 쉬운 사각형 경계 목록으로 변환합니다. */
    public static List<Rect> contoursToRects(List<MatOfPoint> contours) {
        List<Rect> rects = new ArrayList<>();
        
        for (MatOfPoint contour : contours) {
            rects.add(Imgproc.boundingRect(contour));
        }
        
        return rects;
    }
}
