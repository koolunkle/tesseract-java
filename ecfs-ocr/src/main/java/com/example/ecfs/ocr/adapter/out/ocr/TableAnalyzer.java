package com.example.ecfs.ocr.adapter.out.ocr;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.springframework.stereotype.Component;

import com.example.ecfs.ocr.common.util.ImageUtils;
import com.example.ecfs.ocr.domain.model.document.TableCell;
import com.example.ecfs.ocr.domain.model.document.TableStructureResult;
import com.example.ecfs.ocr.common.constant.ProcessingConstants;
import com.example.ecfs.ocr.common.util.DebugUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 이미지 내 표의 격자 구조 및 셀 경계를 분석하는 전문 엔진.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TableAnalyzer {

    /** 마스크 확장을 위한 커널 크기 */
    private static final Size MASK_DILATE_SIZE = new Size(3, 2);

    /** 수직선 보강을 위한 커널 크기 */
    private static final Size VERTICAL_DILATE_SIZE = new Size(2, 3);

    /** 적응형 이진화 블록 크기 */
    private static final int ADAPTIVE_THRESHOLD_BLOCK_SIZE = 21;

    /** 적응형 이진화 보정 상수 */
    private static final int ADAPTIVE_THRESHOLD_C = 2;

    /** 이진화 최대 픽셀 값 */
    private static final double MAX_PIXEL_VALUE = 255.0;

    /** 컨테이너 판별을 위한 자식 영역 비율 */
    private static final double CONTAINER_CHILDREN_AREA_RATIO = 0.7;

    /** 최소 선 성분 커널 크기 */
    private static final int MIN_KERNEL_SIZE = 15;

    /** 표 인식을 위한 최소 교차점 수 */
    private static final int MIN_JUNCTION_COUNT = 4;

    /** 경계 탐색 최대 반복 횟수 */
    private static final int MAX_SEARCH_ITERATIONS = 100;

    /** 유효 데이터 판단을 위한 최소 픽셀 수 */
    private static final int MIN_NOISE_PIXELS = 5;

    /** 수평선 유효성 판단 비율 */
    private static final double MIN_LINE_WIDTH_RATIO = 0.3;

    /** 이미지 하단 절단 기준 비율 */
    private static final double BOTTOM_CUTOFF_RATIO = 0.9;

    /** 수직선 유효성 판단 비율 */
    private static final double MIN_VLINE_HEIGHT_RATIO = 0.02;

    /** 수평선 추출을 위한 커널 크기 결정 계수 (너비 / N) */
    private static final int HORIZONTAL_KERNEL_DIVISOR = 60;

    /** 수직선 추출을 위한 커널 크기 결정 계수 (높이 / N) */
    private static final int VERTICAL_KERNEL_DIVISOR = 80;

    /** 유효한 셀로 인정하기 위한 최소 너비 (픽셀) */
    private static final int MIN_CELL_WIDTH = 5;

    /** 유효한 셀로 인정하기 위한 최소 높이 (픽셀) */
    private static final int MIN_CELL_HEIGHT = 5;

    /** 페이지 대비 표가 차지할 수 있는 최대 비율 */
    private static final double MAX_PAGE_RATIO = 0.9;

    /** 경계 복구 시 사용할 기본 선 두께 */
    private static final int BOUNDARY_REPAIR_THICKNESS = 2;

    /** 수평선 근접 판단 임계값 (픽셀) */
    private static final int HORIZONTAL_LINE_NEAR_TOLERANCE = 10;

    /** 수직 중첩 판단 임계값 (픽셀) */
    private static final int VERTICAL_OVERLAP_THRESHOLD = 2;

    /** 디버그 드로잉 색상 (White) */
    private static final Scalar COLOR_WHITE = new Scalar(255);

    /** 디버그 드로잉 색상 (Black) */
    private static final Scalar COLOR_BLACK = new Scalar(0, 0, 0);

    /** 디버그 드로잉 색상 (Red) */
    private static final Scalar COLOR_RED = new Scalar(0, 0, 255);

    /**
     * 이미지 분석을 통해 표의 격자 구조를 파악하고 셀 영역을 도출합니다.
     */
    public TableStructureResult analyzeTableStructure(
            Mat sourceImage, Mat originalPage, Point roiOffset,
            String originalFileName, int pageNum, String typeSubDir, String sectionName, boolean isSearchable
    ) {
        return analyzeTableStructure(
                sourceImage, originalPage, roiOffset, null,
                originalFileName, pageNum, typeSubDir, sectionName, isSearchable
        );
    }

    /**
     * 지정된 영역 내에서 표의 구조를 분석하여 셀 좌표 목록을 생성합니다.
     */
    public TableStructureResult analyzeTableStructure(
            Mat sourceImage, Mat originalPage, Point roiOffset, Rect searchArea,
            String originalFileName, int pageNum, String typeSubDir, String sectionName, boolean isSearchable
    ) {
        Mat gray = new Mat();
        Mat binary = new Mat();
        Mat horizontal = new Mat();
        Mat vertical = new Mat();
        Mat tableMask = new Mat();
        Mat hierarchy = new Mat();
        Mat workingImage = null;

        int absoluteOffsetX = (int) (roiOffset != null ? roiOffset.x : 0);
        int absoluteOffsetY = (int) (roiOffset != null ? roiOffset.y : 0);

        if (searchArea != null) {
            absoluteOffsetX += searchArea.x;
            absoluteOffsetY += searchArea.y;
        }

        try {
            workingImage = (searchArea != null)
                    ? new Mat(sourceImage, clipToImage(searchArea, sourceImage.size()))
                    : sourceImage;

            if (workingImage.channels() > 1) {
                Imgproc.cvtColor(workingImage, gray, Imgproc.COLOR_BGR2GRAY);
            } else {
                workingImage.copyTo(gray);
            }

            Imgproc.adaptiveThreshold(
                    gray, binary, MAX_PIXEL_VALUE, Imgproc.ADAPTIVE_THRESH_MEAN_C,
                    Imgproc.THRESH_BINARY_INV, ADAPTIVE_THRESHOLD_BLOCK_SIZE, ADAPTIVE_THRESHOLD_C
            );

            detectLines(binary, horizontal, vertical);

            Mat repairedImage = null;
            Mat debugImage = DebugUtils.createDebugImage(workingImage);

            if (ProcessingConstants.NAME_CREDITOR_LIST.equals(sectionName)) {
                repairedImage = workingImage.clone();
                repairTableBoundaries(binary, vertical, horizontal, repairedImage, debugImage, new Point(0, 0));
            }

            if (!hasValidJunctions(horizontal, vertical, pageNum)) {
                ImageUtils.release(debugImage);
                if (searchArea != null) {
                    ImageUtils.release(workingImage);
                }
                return new TableStructureResult(new ArrayList<>(), binary, repairedImage, null);
            }

            List<TableCell> allCells = extractCells(
                    workingImage, horizontal, vertical, hierarchy, absoluteOffsetX, absoluteOffsetY
            );

            if (searchArea != null) {
                ImageUtils.release(workingImage);
            }

            return new TableStructureResult(allCells, binary, repairedImage, debugImage);

        } finally {
            ImageUtils.release(gray, horizontal, vertical, tableMask, hierarchy);
        }
    }

    /**
     * 손상된 표의 외곽 경계선을 복구합니다.
     */
    private void repairTableBoundaries(
            Mat binary, Mat vertical, Mat horizontal, Mat repairedImage, Mat debugImage, Point roiOffset
    ) {
        int imgHeight = vertical.rows();
        List<Rect> vLines = detectVerticalLines(vertical, imgHeight);

        if (vLines.isEmpty()) {
            return;
        }

        List<Rect> targetGroup = selectTargetTableGroup(vLines);
        Rect targetRect = getGroupBoundingBox(targetGroup);

        // 상단 경계 복구
        int topY = findSafeCutY(binary, targetRect.y, targetRect.x, targetRect.width, false, BOUNDARY_REPAIR_THICKNESS);

        if (!hasHorizontalLineNear(horizontal, topY, targetRect.x, targetRect.width, HORIZONTAL_LINE_NEAR_TOLERANCE)) {
            drawHorizontalLine(horizontal, debugImage, topY, targetRect.x, targetRect.width, (int) roiOffset.x, (int) roiOffset.y, BOUNDARY_REPAIR_THICKNESS, "Top");
            drawHorizontalLine(repairedImage, null, topY, targetRect.x, targetRect.width, (int) roiOffset.x, (int) roiOffset.y, BOUNDARY_REPAIR_THICKNESS, null);
            extendVerticalLines(vertical, targetGroup, topY, true);
        }

        // 하단 경계 복구
        int cutoffY = (int) (imgHeight * BOTTOM_CUTOFF_RATIO);

        if (isTableReachingBottom(targetGroup, targetRect, cutoffY)) {
            int bottomY = findSafeCutY(binary, targetRect.y + targetRect.height, targetRect.x, targetRect.width, true, BOUNDARY_REPAIR_THICKNESS);

            drawHorizontalLine(horizontal, debugImage, bottomY, targetRect.x, targetRect.width, (int) roiOffset.x, (int) roiOffset.y, BOUNDARY_REPAIR_THICKNESS, "Bottom");
            drawHorizontalLine(repairedImage, null, bottomY, targetRect.x, targetRect.width, (int) roiOffset.x, (int) roiOffset.y, BOUNDARY_REPAIR_THICKNESS, null);
            extendVerticalLines(vertical, targetGroup, bottomY, false);
        }
    }

    /**
     * 이미지 내의 수평 및 수직 직선을 검출합니다.
     */
    private void detectLines(Mat binary, Mat horizontal, Mat vertical) {
        int hSize = Math.max(MIN_KERNEL_SIZE, binary.cols() / HORIZONTAL_KERNEL_DIVISOR);
        Mat hKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(hSize, 1));

        binary.copyTo(horizontal);
        Imgproc.morphologyEx(horizontal, horizontal, Imgproc.MORPH_OPEN, hKernel);
        Imgproc.dilate(horizontal, horizontal, hKernel);
        hKernel.release();

        int vSize = Math.max(MIN_KERNEL_SIZE, binary.rows() / VERTICAL_KERNEL_DIVISOR);
        Mat vKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(1, vSize));

        binary.copyTo(vertical);
        Imgproc.morphologyEx(vertical, vertical, Imgproc.MORPH_OPEN, vKernel);

        Mat vDilateKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, VERTICAL_DILATE_SIZE);
        Imgproc.dilate(vertical, vertical, vDilateKernel);

        vKernel.release();
        vDilateKernel.release();
    }

    /** 교차점 분석을 통해 표 구조를 확인합니다. */
    private boolean hasValidJunctions(Mat horizontal, Mat vertical, int pageNum) {
        Mat intersections = new Mat();
        List<MatOfPoint> junctionContours = new ArrayList<>();

        Core.bitwise_and(horizontal, vertical, intersections);
        Imgproc.findContours(intersections, junctionContours, new Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        int count = junctionContours.size();

        intersections.release();
        junctionContours.forEach(ImageUtils::release);

        if (count < MIN_JUNCTION_COUNT) {
            log.info("Page [{}]: No table structure detected (Junctions: {})", pageNum, count);
            return false;
        }

        return true;
    }

    /** 검출된 선분 구조로부터 개별 데이터 셀의 좌표 목록을 추출합니다. */
    private List<TableCell> extractCells(
            Mat workingImage, Mat horizontal, Mat vertical, Mat hierarchy, int offsetX, int offsetY
    ) {
        Mat tableMask = new Mat();
        Core.add(horizontal, vertical, tableMask);

        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, MASK_DILATE_SIZE);
        Imgproc.dilate(tableMask, tableMask, kernel);

        List<MatOfPoint> contours = new ArrayList<>();
        Imgproc.findContours(tableMask, contours, hierarchy, Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE);

        List<TableCell> cells = new ArrayList<>();
        double pageArea = workingImage.rows() * workingImage.cols();
        int[] hierarchyData = new int[4];

        for (int i = 0; i < contours.size(); i++) {
            Rect rect = Imgproc.boundingRect(contours.get(i));

            boolean isValidSize = rect.width > MIN_CELL_WIDTH
                    && rect.height > MIN_CELL_HEIGHT
                    && rect.area() < pageArea * MAX_PAGE_RATIO;

            if (isValidSize) {
                if (!isContainer(i, contours, hierarchy, hierarchyData, rect.area())) {
                    Rect absoluteRect = new Rect(rect.x + offsetX, rect.y + offsetY, rect.width, rect.height);
                    cells.add(new TableCell(absoluteRect));
                }
            }
            ImageUtils.release(contours.get(i));
        }

        kernel.release();
        tableMask.release();

        return cells;
    }

    /** 해당 영역이 다른 셀들을 포함하는 외곽 컨테이너인지 판별합니다. */
    private boolean isContainer(int index, List<MatOfPoint> contours, Mat hierarchy, int[] hierarchyData, double parentArea) {
        hierarchy.get(0, index, hierarchyData);
        int firstChild = hierarchyData[2];

        if (firstChild == -1) {
            return false;
        }

        double childrenArea = 0;
        int childIdx = firstChild;

        while (childIdx != -1) {
            childrenArea += Imgproc.boundingRect(contours.get(childIdx)).area();
            hierarchy.get(0, childIdx, hierarchyData);
            childIdx = hierarchyData[0];
        }

        return childrenArea > parentArea * CONTAINER_CHILDREN_AREA_RATIO;
    }

    /** 이미지 내에서 수직 방향의 선 성분들을 검출합니다. */
    private List<Rect> detectVerticalLines(Mat vertical, int imgHeight) {
        List<MatOfPoint> contours = new ArrayList<>();
        Imgproc.findContours(vertical, contours, new Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        List<Rect> vLines = new ArrayList<>();
        int minHeight = (int) (imgHeight * MIN_VLINE_HEIGHT_RATIO);

        for (MatOfPoint contour : contours) {
            Rect rect = Imgproc.boundingRect(contour);
            if (rect.height > minHeight) {
                vLines.add(rect);
            }
            ImageUtils.release(contour);
        }

        return vLines;
    }

    /** 검출된 선분들 중 가장 신뢰도가 높은 표 후보 그룹을 선정합니다. */
    private List<Rect> selectTargetTableGroup(List<Rect> vLines) {
        vLines.sort(Comparator.comparingInt(r -> r.x));
        List<List<Rect>> groups = new ArrayList<>();

        for (Rect line : vLines) {
            boolean isAdded = false;

            for (List<Rect> group : groups) {
                if (isVerticallyOverlapping(getGroupBoundingBox(group), line, VERTICAL_OVERLAP_THRESHOLD)) {
                    group.add(line);
                    isAdded = true;
                    break;
                }
            }

            if (!isAdded) {
                List<Rect> newGroup = new ArrayList<>();
                newGroup.add(line);
                groups.add(newGroup);
            }
        }

        return groups.stream()
                .max(Comparator.comparingDouble(g -> getGroupBoundingBox(g).area()))
                .orElse(vLines);
    }

    /** 선분 그룹 전체를 포함하는 최소 사각형 경계를 계산합니다. */
    private Rect getGroupBoundingBox(List<Rect> group) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;

        for (Rect rect : group) {
            minX = Math.min(minX, rect.x);
            minY = Math.min(minY, rect.y);
            maxX = Math.max(maxX, rect.x + rect.width);
            maxY = Math.max(maxY, rect.y + rect.height);
        }

        return new Rect(minX, minY, maxX - minX, maxY - minY);
    }

    /** 두 영역이 수직 방향에서 서로 중첩되는지 확인합니다. */
    private boolean isVerticallyOverlapping(Rect r1, Rect r2, int threshold) {
        int overlap = Math.min(r1.y + r1.height, r2.y + r2.height) - Math.max(r1.y, r2.y);

        return overlap > threshold;
    }

    /** 노이즈가 없는 안전한 Y축 절단 지점을 탐색합니다. */
    private int findSafeCutY(Mat binary, int startY, int x, int width, boolean isDownward, int gap) {
        int step = isDownward ? 1 : -1;
        int currentY = Math.max(0, Math.min(binary.rows() - 1, startY));

        for (int i = 0; i < MAX_SEARCH_ITERATIONS; i++) {
            int nextY = currentY + step;
            if (nextY < 0 || nextY >= binary.rows()) {
                break;
            }

            Mat row = binary.submat(currentY, currentY + 1, x, Math.min(binary.cols(), x + width));
            int nonZeroPixels = Core.countNonZero(row);
            row.release();

            if (nonZeroPixels < MIN_NOISE_PIXELS) {
                return currentY;
            }
            currentY = nextY;
        }

        return startY;
    }

    /** 특정 좌표 인근에 이미 존재하는 수평선이 있는지 확인합니다. */
    private boolean hasHorizontalLineNear(Mat horizontal, int targetY, int x, int width, int tolerance) {
        int y1 = Math.max(0, targetY - tolerance);
        int y2 = Math.min(horizontal.rows(), targetY + tolerance);

        Mat roi = horizontal.submat(y1, y2, x, Math.min(horizontal.cols(), x + width));
        List<MatOfPoint> contours = new ArrayList<>();
        Imgproc.findContours(roi, contours, new Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        boolean isFound = contours.stream()
                .anyMatch(c -> Imgproc.boundingRect(c).width > width * MIN_LINE_WIDTH_RATIO);

        roi.release();
        contours.forEach(ImageUtils::release);

        return isFound;
    }

    /** 누락된 경계를 보정하기 위해 기존 수직 선분들을 특정 좌표까지 확장합니다. */
    private void extendVerticalLines(Mat vertical, List<Rect> vLines, int targetY, boolean isTop) {
        for (Rect line : vLines) {
            Point p1 = new Point(line.x + line.width / 2.0, isTop ? line.y : line.y + line.height);
            Point p2 = new Point(line.x + line.width / 2.0, targetY);

            Imgproc.line(vertical, p1, p2, COLOR_WHITE, 3);
        }
    }

    /** 지정된 캔버스에 수평 경계선을 그리고 로그를 기록합니다. */
    private void drawHorizontalLine(
            Mat target, Mat debug, int y, int x, int width, int offX, int offY, int thickness, String label
    ) {
        Scalar color = (target.channels() == 1) ? new Scalar(0) : COLOR_BLACK;
        Imgproc.line(target, new Point(x, y), new Point(x + width, y), color, thickness);

        if (debug != null) {
            Imgproc.line(
                    debug,
                    new Point(x + offX, y + offY),
                    new Point(x + width + offX, y + offY),
                    COLOR_RED, thickness
            );
            if (label != null) {
                log.info("Boundary recovered: {} at Y={}", label, y);
            }
        }
    }

    /** 좌표 정보가 이미지 크기 경계를 벗어나지 않도록 보정합니다. */
    private Rect clipToImage(Rect rect, Size imageSize) {
        int x = Math.max(0, rect.x);
        int y = Math.max(0, rect.y);
        int width = Math.min(rect.width, (int) imageSize.width - x);
        int height = Math.min(rect.height, (int) imageSize.height - y);

        return new Rect(x, y, width, height);
    }

    /** 표의 하단부가 이미지의 끝부분까지 이어져 있는지 확인합니다. */
    private boolean isTableReachingBottom(List<Rect> group, Rect targetRect, int cutoffY) {
        long linesReachingBottom = group.stream()
                .filter(line -> line.y + line.height >= cutoffY)
                .count();

        return linesReachingBottom >= 2 || (targetRect.y + targetRect.height) > cutoffY;
    }
}
