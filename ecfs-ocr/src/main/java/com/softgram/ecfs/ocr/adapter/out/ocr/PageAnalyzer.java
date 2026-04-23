package com.softgram.ecfs.ocr.adapter.out.ocr;

import java.util.List;
import java.util.Map;

import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;
import org.springframework.stereotype.Component;

import com.softgram.ecfs.ocr.common.constant.ProcessingConstants;
import com.softgram.ecfs.ocr.common.util.DebugUtils;
import com.softgram.ecfs.ocr.common.util.ImageUtils;
import com.softgram.ecfs.ocr.common.util.JsonUtils;
import com.softgram.ecfs.ocr.domain.model.document.SectionType;
import com.softgram.ecfs.ocr.domain.model.document.TableCell;
import com.softgram.ecfs.ocr.domain.model.vision.AnalysisResult;
import com.softgram.ecfs.ocr.domain.model.vision.LineClassification;
import com.softgram.ecfs.ocr.domain.service.TableReconstructor;
import com.softgram.ecfs.ocr.domain.service.VisionProcessor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.TesseractException;

/**
 * 페이지 이미지의 구역 식별 및 분석 공정을 조율하는 어댑터.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PageAnalyzer {

    /** 표 구조 분석을 위한 최소 선분 수 */
    private static final int MIN_REQUIRED_LINES = 2;

    /** 디버그용 수평선 색상 (Red) */
    private static final Scalar COLOR_RED = new Scalar(0, 0, 255);

    /** 디버그용 수직선 색상 (Blue) */
    private static final Scalar COLOR_BLUE = new Scalar(255, 0, 0);

    private final TableAnalyzer tableAnalyzer;
    private final TesseractEngine tesseractEngine;
    private final TableReconstructor tableReconstructor;

    /**
     * 페이지 이미지를 분석하여 텍스트 또는 표 데이터를 추출합니다.
     */
    public AnalysisResult process(
            Mat processedMat, 
            Mat originalMat, 
            int pageNum,
            String originalFileName, 
            String typeSubDir, 
            String sectionName
    ) throws TesseractException {
        if (isUnknownSection(sectionName)) {
            return extractHeaderAndIdentify(originalMat, pageNum);
        }

        return extractTable(processedMat, originalMat, pageNum, originalFileName, typeSubDir, sectionName);
    }

    /**
     * 이미지 내 검출된 선분을 시각화하여 구조를 분석합니다.
     */
    public AnalysisResult visualizeContours(Mat processedMat, Mat originalMat, int pageNum) {
        log.info("Page [{}]: Starting contour detection and visualization", pageNum);

        Mat lines = VisionProcessor.detectLinesWithHough(processedMat);

        try {
            if (lines.empty()) {
                log.info("Page [{}]: No lines detected", pageNum);
                return null;
            }

            LineClassification classification = VisionProcessor.classifyLines(lines);

            if (!isValidTableStructure(classification)) {
                log.info("Page [{}]: Insufficient lines for table structure (H={}, V={})",
                        pageNum, classification.horizontalLines().size(), classification.verticalLines().size());
                return null;
            }

            return drawContourDebug(originalMat, classification);
        } finally {
            ImageUtils.release(lines);
        }
    }

    /**
     * 페이지 상단 영역을 분석하여 섹션을 판별합니다.
     */
    private AnalysisResult extractHeaderAndIdentify(Mat originalMat, int pageNum) throws TesseractException {
        // 상단 1/3 영역을 헤더로 간주
        int headerHeight = originalMat.rows() / 3;
        Mat headerRoi = new Mat(originalMat, new Rect(0, 0, originalMat.cols(), headerHeight));

        String extractedText = tesseractEngine.extractText(headerRoi);
        String identifiedSection = detectSection(extractedText);

        var resultData = Map.of(
                ProcessingConstants.KEY_PAGE, pageNum,
                ProcessingConstants.KEY_SECTION, identifiedSection
        );

        return new AnalysisResult(
                JsonUtils.stringify(resultData), 
                DebugUtils.createDebugImage(originalMat), 
                headerRoi
        );
    }

    /**
     * 이미지 분석 및 OCR을 결합하여 표 데이터를 추출합니다.
     */
    private AnalysisResult extractTable(
            Mat processedMat, 
            Mat originalMat, 
            int pageNum,
            String originalFileName, 
            String typeSubDir, 
            String sectionName
    ) throws TesseractException {
        log.info("Page [{}]: Table extraction: {}", pageNum, sectionName);

        Mat debugImage = DebugUtils.createDebugImage(originalMat);
        Mat binaryMask = null;

        try {
            var structureResult = tableAnalyzer.analyzeTableStructure(
                    processedMat, originalMat, new Point(0, 0),
                    originalFileName, pageNum, typeSubDir, sectionName, false
            );

            if (structureResult.debugImage() != null && !structureResult.debugImage().empty()) {
                ImageUtils.release(debugImage);
                debugImage = structureResult.debugImage();
            }

            List<TableCell> cells = structureResult.cells();
            binaryMask = structureResult.binaryMask();

            // 셀 단위 ROI OCR 수행 
            tesseractEngine.extractTextFromCells(processedMat, cells);
            refineExtractedText(cells);

            var tableData = tableReconstructor.mapToTableData(cells);

            if (structureResult.repairedImage() != null) {
                ImageUtils.release(structureResult.repairedImage());
            }

            return new AnalysisResult(JsonUtils.prettyPrint(tableData), debugImage, null);
        } finally {
            ImageUtils.release(binaryMask);
        }
    }

    /** 섹션 명칭이 미정의 상태인지 확인합니다. */
    private boolean isUnknownSection(String sectionName) {
        return sectionName == null || ProcessingConstants.NAME_UNKNOWN.equals(sectionName);
    }

    /** 검출된 선분들이 유효한 표 구조를 형성할 수 있는지 검증합니다. */
    private boolean isValidTableStructure(LineClassification classification) {
        return classification.horizontalLines().size() >= MIN_REQUIRED_LINES 
                && classification.verticalLines().size() >= MIN_REQUIRED_LINES;
    }

    /** 텍스트 패턴을 분석하여 구역(Section)의 종류를 판별합니다. */
    private String detectSection(String text) {
        if (text == null || text.isBlank()) {
            return ProcessingConstants.NAME_UNKNOWN;
        }

        return text.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .map(line -> line.replaceAll("\\s+", ""))
                .map(SectionType::identify)
                .filter(s -> !ProcessingConstants.NAME_UNKNOWN.equals(s))
                .findFirst()
                .orElse(ProcessingConstants.NAME_UNKNOWN);
    }

    /** 추출된 모든 셀 데이터의 텍스트 형식을 일괄 정제합니다. */
    private void refineExtractedText(List<TableCell> cells) {
        cells.forEach(cell -> {
            String text = cell.getText();
            if (text != null && !text.isBlank()) {
                cell.setOverrideText(formatNumberString(text.trim()));
            }
        });
    }

    /** 텍스트 내의 금액 및 숫자 데이터 형식을 정규화합니다. */
    private String formatNumberString(String text) {
        String cleanText = text;

        if (cleanText.matches("^[0-9,]+[.]?$")) {
            cleanText = cleanText.replace(".", "");
        }
        
        if (cleanText.matches("^[0-9]{1,3}([.][0-9]{3})+$")) {
            cleanText = cleanText.replace(".", ",");
        }

        return cleanText;
    }

    /** 선분 분류 결과를 시각화하여 디버그 분석 이미지를 생성합니다. */
    private AnalysisResult drawContourDebug(Mat originalMat, LineClassification classification) {
        Mat debugImage = DebugUtils.createDebugImage(originalMat);

        classification.horizontalLines().forEach(line -> drawLine(debugImage, line, COLOR_RED));
        classification.verticalLines().forEach(line -> drawLine(debugImage, line, COLOR_BLUE));

        String resultSummary = String.format("Hough Detection: [H: %d, V: %d]",
                classification.horizontalLines().size(), classification.verticalLines().size());

        return new AnalysisResult(resultSummary, debugImage, null);
    }

    /** OpenCV를 사용하여 이미지 상에 특정 색상의 직선을 그립니다. */
    private void drawLine(Mat image, double[] lineParams, Scalar color) {
        Imgproc.line(
                image, 
                new Point(lineParams[0], lineParams[1]), 
                new Point(lineParams[2], lineParams[3]), 
                color, 
                2
        );
    }
}
