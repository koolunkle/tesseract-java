package com.example.ecfs.ocr.adapter.out.ocr;

import java.awt.image.BufferedImage;
import java.util.List;

import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.springframework.stereotype.Component;

import com.example.ecfs.ocr.infrastructure.AppProperties;
import com.example.ecfs.ocr.common.exception.ProcessingException;
import com.example.ecfs.ocr.common.util.ImageUtils;
import com.example.ecfs.ocr.domain.model.document.TableCell;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.ITessAPI.TessOcrEngineMode;
import net.sourceforge.tess4j.ITessAPI.TessPageIteratorLevel;
import net.sourceforge.tess4j.ITessAPI.TessPageSegMode;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import net.sourceforge.tess4j.Word;

/**
 * Tesseract OCR 엔진을 사용하여 이미지 내 텍스트를 추출하고 좌표를 매핑하는 어댑터.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TesseractEngine {

    /** Tesseract 분석 권장 DPI */
    private static final String TESS_DPI = "300";

    /** Tesseract DPI 변수명 */
    private static final String TESS_VAR_USER_DEFINED_DPI = "user_defined_dpi";

    /** Tesseract 디버그 파일 경로 */
    private static final String TESS_VAR_DEBUG_FILE = "debug_file";

    /** OS 속성 키 */
    private static final String OS_NAME_PROPERTY = "os.name";

    /** 윈도우 식별자 */
    private static final String WINDOWS_OS_PREFIX = "win";

    /** 로그 무효화 (Windows) */
    private static final String DEV_NULL_WINDOWS = "NUL";

    /** 로그 무효화 (Unix) */
    private static final String DEV_NULL_UNIX = "/dev/null";

    /** 셀 영역 확장 패딩 */
    private static final int CELL_EXPANSION_PADDING = 5;

    /** 최소 중첩 비율 */
    private static final double MIN_OVERLAP_RATIO = 0.5;

    private final AppProperties appProperties;

    /** 검출된 단어를 표 셀에 배정합니다. */
    public void mapWordsToCells(Mat originalMat, List<TableCell> cells) {
        mapWordsToCells(
                originalMat,
                cells,
                TessPageSegMode.PSM_SINGLE_BLOCK,
                TessOcrEngineMode.OEM_LSTM_ONLY
        );
    }

    /** 특정 모드로 단어를 표 셀에 매핑합니다. */
    public void mapWordsToCells(Mat originalMat, List<TableCell> cells, int psm, int oem) {
        try {
            Tesseract tesseract = createTesseract();
            tesseract.setPageSegMode(psm);
            tesseract.setOcrEngineMode(oem);

            BufferedImage bufferedImage = ImageUtils.toBufferedImage(originalMat);
            List<Word> allWords = tesseract.getWords(bufferedImage, TessPageIteratorLevel.RIL_WORD);

            mapWordsToBestMatchingCells(allWords, cells);

        } catch (Exception e) {
            throw new ProcessingException("Tesseract processing failed during cell mapping: " + e.getMessage(), e);
        }
    }

    /** 셀 영역에 대해서만 정밀 OCR을 수행합니다. */
    public void extractTextFromCells(Mat originalMat, List<TableCell> cells) {
        Tesseract tesseract = createTesseract();
        tesseract.setPageSegMode(TessPageSegMode.PSM_SINGLE_BLOCK);
        tesseract.setOcrEngineMode(TessOcrEngineMode.OEM_LSTM_ONLY);

        for (TableCell cell : cells) {
            Rect rect = cell.getBoundingBox();

            if (rect.area() <= 0) {
                continue;
            }

            Rect safeRect = createSafeCropRect(rect, originalMat.cols(), originalMat.rows());
            if (safeRect == null) {
                continue;
            }

            Mat croppedMat = new Mat(originalMat, safeRect);

            try {
                BufferedImage bufferedImage = ImageUtils.toBufferedImage(croppedMat);
                String result = tesseract.doOCR(bufferedImage);

                if (result != null && !result.isBlank()) {
                    cell.setOverrideText(result.trim());
                }
            } catch (TesseractException e) {
                log.warn("OCR failed for cell at {}: {}", safeRect, e.getMessage());
            } finally {
                croppedMat.release();
            }
        }
    }

    /** 이미지 전체에서 텍스트를 추출합니다. */
    public String extractText(Mat image) {
        try {
            Tesseract tesseract = createTesseract();
            BufferedImage bufferedImage = ImageUtils.toBufferedImage(image);

            return tesseract.doOCR(bufferedImage);
        } catch (TesseractException e) {
            throw new ProcessingException("Tesseract text extraction failed: " + e.getMessage(), e);
        }
    }

    /** 이미지 내 단어와 좌표 정보를 수집합니다. */
    public List<Word> extractWords(Mat image) {
        try {
            Tesseract tesseract = createTesseract();
            tesseract.setPageSegMode(TessPageSegMode.PSM_SINGLE_BLOCK);

            BufferedImage bufferedImage = ImageUtils.toBufferedImage(image);
            return tesseract.getWords(bufferedImage, TessPageIteratorLevel.RIL_WORD);
        } catch (Exception e) {
            throw new ProcessingException("Tesseract word extraction failed: " + e.getMessage(), e);
        }
    }

    /** 시스템 환경 및 프로퍼티 설정을 바탕으로 Tesseract 인스턴스를 초기화합니다. */
    private Tesseract createTesseract() {
        Tesseract tesseract = new Tesseract();

        tesseract.setDatapath(appProperties.dataPath());
        tesseract.setLanguage(appProperties.language());
        tesseract.setVariable(TESS_VAR_USER_DEFINED_DPI, TESS_DPI);

        String debugFilePath = isWindows() ? DEV_NULL_WINDOWS : DEV_NULL_UNIX;
        tesseract.setVariable(TESS_VAR_DEBUG_FILE, debugFilePath);

        return tesseract;
    }

    /** 개별 단어의 중심점과 중첩 비율을 계산하여 가장 적합한 표 셀에 배정합니다. */
    private void mapWordsToBestMatchingCells(List<Word> words, List<TableCell> cells) {
        for (Word word : words) {
            Rect wordRect = createRectFromWord(word);
            if (wordRect.area() <= 0) {
                continue;
            }

            TableCell bestCell = null;
            double maxOverlapRatio = 0.0;
            Point wordCenter = getCenterPoint(wordRect);

            for (TableCell cell : cells) {
                Rect expandedCell = expandRect(cell.getBoundingBox(), CELL_EXPANSION_PADDING);
                Rect intersection = getIntersection(expandedCell, wordRect);

                if (intersection != null) {
                    double overlapRatio = intersection.area() / (double) wordRect.area();
                    if (overlapRatio > maxOverlapRatio) {
                        maxOverlapRatio = overlapRatio;
                        bestCell = cell;
                    }
                }
            }

            if (bestCell != null) {
                Rect expandedBest = expandRect(bestCell.getBoundingBox(), CELL_EXPANSION_PADDING);
                boolean isCenterInside = expandedBest.contains(wordCenter);

                if (maxOverlapRatio > MIN_OVERLAP_RATIO || isCenterInside) {
                    bestCell.addWord(word);
                }
            }
        }
    }

    /** 이미지 경계를 벗어나지 않는 안전한 크롭 영역을 산출합니다. */
    private Rect createSafeCropRect(Rect target, int maxWidth, int maxHeight) {
        int x = Math.max(0, target.x);
        int y = Math.max(0, target.y);
        int w = Math.min(maxWidth - x, target.width);
        int h = Math.min(maxHeight - y, target.height);

        if (w <= 0 || h <= 0) {
            return null;
        }

        return new Rect(x, y, w, h);
    }

    /** 사각형 영역을 사방으로 일정 크기만큼 확장합니다. */
    private Rect expandRect(Rect rect, int padding) {
        return new Rect(
                Math.max(0, rect.x - padding),
                Math.max(0, rect.y - padding),
                rect.width + (padding * 2),
                rect.height + (padding * 2)
        );
    }

    /** Tesseract의 Word 객체로부터 OpenCV Rect 경계 좌표를 추출합니다. */
    private Rect createRectFromWord(Word word) {
        java.awt.Rectangle box = word.getBoundingBox();
        return new Rect(box.x, box.y, box.width, box.height);
    }

    /** 사각형 영역의 중심점 좌표를 계산합니다. */
    private Point getCenterPoint(Rect rect) {
        return new Point(rect.x + (rect.width / 2.0), rect.y + (rect.height / 2.0));
    }

    /** 두 사각형 영역이 겹치는 중첩 영역(Intersection)을 산출합니다. */
    private Rect getIntersection(Rect r1, Rect r2) {
        int x = Math.max(r1.x, r2.x);
        int y = Math.max(r1.y, r2.y);
        int w = Math.min(r1.x + r1.width, r2.x + r2.width) - x;
        int h = Math.min(r1.y + r1.height, r2.y + r2.height) - y;

        if (w > 0 && h > 0) {
            return new Rect(x, y, w, h);
        }

        return null;
    }

    /** 현재 시스템 운영체제가 윈도우인지 확인합니다. */
    private boolean isWindows() {
        return System.getProperty(OS_NAME_PROPERTY).toLowerCase().contains(WINDOWS_OS_PREFIX);
    }
}
