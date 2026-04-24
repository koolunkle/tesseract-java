package com.example.ecfs.ocr.adapter.out.document;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.imgproc.Imgproc;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.ecfs.ocr.adapter.out.ocr.PageAnalyzer;
import com.example.ecfs.ocr.adapter.out.ocr.TableAnalyzer;
import com.example.ecfs.ocr.adapter.out.ocr.TabulaEngine;
import com.example.ecfs.ocr.adapter.out.opencv.ImagePreprocessor;
import com.example.ecfs.ocr.common.constant.ProcessingConstants;
import com.example.ecfs.ocr.common.exception.ProcessingException;
import com.example.ecfs.ocr.common.util.ImageUtils;
import com.example.ecfs.ocr.domain.model.document.PageTable;
import com.example.ecfs.ocr.domain.model.document.TableStructureResult;
import com.example.ecfs.ocr.domain.model.analysis.DiagnosticPayload;
import com.example.ecfs.ocr.domain.model.analysis.SectionRange;
import com.example.ecfs.ocr.domain.model.vision.AnalysisResult;
import com.example.ecfs.ocr.domain.model.vision.PreprocessingDecision;
import com.example.ecfs.ocr.domain.model.analysis.AnalysisPayload;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 텍스트 레이어와 비전 분석을 결합하여 문서 내 표 데이터를 추출하는 컴포넌트.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TableExtractor {

    /** PDF 렌더링 DPI */
    private static final float PDF_RENDER_DPI = 300.0f;

    private final TabulaEngine tabulaEngine;
    private final TableAnalyzer tableAnalyzer;
    private final ImagePreprocessor imagePreprocessor;
    private final PageAnalyzer pageAnalyzer;
    private final ObjectMapper objectMapper;

    /**
     * 텍스트 레이어(Tabula) 및 이미지 기반(OCR) 분석을 병행하여 데이터를 수집합니다.
     */
    public List<PageTable> executeExtraction(
            PDDocument document, File file, String fileName, String extension, String jobId,
            Set<Integer> processedPages, AnalysisPayload previousResult, List<SectionRange> sectionRanges,
            Set<Integer> targetPages, boolean isSearchable, Consumer<List<PageTable>> onProgress
    ) {
        List<PageTable> finalResults = loadPreviousPageTables(previousResult);
        log.info("Restored {} tables from context", finalResults.size());

        if (!isSearchable) {
            return finalResults;
        }

        log.info("Text layer found, starting Tabula");
        List<PageTable> tabulaResults = tabulaEngine.extractTables(file, fileName, true, targetPages, processedPages);

        if (tabulaResults == null || tabulaResults.isEmpty()) {
            return finalResults;
        }

        List<PageTable> validTabulaResults = new ArrayList<>();
        PDFRenderer pdfRenderer = (document != null) ? new PDFRenderer(document) : null;

        for (PageTable dto : tabulaResults) {
            if (Thread.currentThread().isInterrupted()) {
                throw new ProcessingException("Operation interrupted");
            }

            if (dto == null || (!targetPages.isEmpty() && !targetPages.contains(dto.pageNumber()))) {
                continue;
            }

            boolean isCreditorList = isCreditorListSection(dto.pageNumber(), sectionRanges);
            boolean isValid = isCreditorList ? isCreditorListValid(dto) : isValidTableContent(dto);

            if (!isValid) {
                if (!isCreditorList) {
                    log.info("Page {} Tabula result insufficient, Fallback to OCR", dto.pageNumber());
                }
                continue;
            }

            // 채권자 목록이고 데이터가 있다면 하단 보정 수행
            if (isCreditorList && dto.tables() != null && !dto.tables().isEmpty() && pdfRenderer != null) {
                repairBottomGapsWithOcr(pdfRenderer, fileName, extension, jobId, dto);
            }

            validTabulaResults.add(dto);
            processedPages.add(dto.pageNumber());

            if (onProgress != null) {
                onProgress.accept(new ArrayList<>(validTabulaResults));
            }
        }

        finalResults.addAll(validTabulaResults);
        return finalResults;
    }

    /**
     * 누락된 페이지 하단 영역을 OCR로 보완 추출합니다.
     */
    private void repairBottomGapsWithOcr(PDFRenderer pdfRenderer, String fileName, String extension, String jobId, PageTable dto) {
        int tabulaMaxY = calculateTabulaMaxY(dto);

        try {
            BufferedImage image = pdfRenderer.renderImageWithDPI(dto.pageNumber() - 1, PDF_RENDER_DPI, ImageType.RGB);
            if (image == null) return;

            Mat mat = ImageUtils.toMat(image);
            Mat gray = new Mat();
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY);

            TableStructureResult structure = tableAnalyzer.analyzeTableStructure(
                    gray, mat, new Point(0, 0), fileName, dto.pageNumber(),
                    extension, ProcessingConstants.NAME_CREDITOR_LIST, true
            );

            int repairedMaxY = calculateRepairedMaxY(structure);
            int pageHeight = mat.rows();
            boolean isBottomReached = tabulaMaxY > (pageHeight * 0.88);

            if (repairedMaxY > tabulaMaxY + 10 || isBottomReached) {
                log.info("Page {} Bottom Gap detected or Bottom Reached. Attempting OCR with Repaired Image...", dto.pageNumber());

                Mat targetImage = (structure.repairedImage() != null && !structure.repairedImage().empty())
                        ? structure.repairedImage() : mat;

                PageTable ocrBottomPage = extractTableFromMatFallback(targetImage, fileName, dto.pageNumber(), extension, jobId);

                if (ocrBottomPage != null && ocrBottomPage.tables() != null) {
                    List<PageTable.TableData> bottomTables = processBottomOcrTables(ocrBottomPage, dto, pageHeight, tabulaMaxY);
                    if (!bottomTables.isEmpty()) {
                        log.info("Page {} recovered {} tables from Bottom Gap via OCR", dto.pageNumber(), bottomTables.size());
                        try {
                            dto.tables().addAll(bottomTables);
                        } catch (UnsupportedOperationException ignored) {}
                    }
                }
            }

            ImageUtils.release(mat, gray, structure.binaryMask(), structure.repairedImage());
            image.flush();

        } catch (Exception e) {
            log.warn("Failed to check gaps for page {}", dto.pageNumber(), e);
        }
    }

    private List<PageTable.TableData> processBottomOcrTables(PageTable ocrBottomPage, PageTable originalDto, int pageHeight, int tabulaMaxY) {
        List<PageTable.TableData> bottomTables = new ArrayList<>();
        int gapThreshold = (int) (pageHeight * 0.20);
        int minGap = (int) (pageHeight * 0.03);

        for (PageTable.TableData ocrTable : ocrBottomPage.tables()) {
            if (ocrTable.cellGrid() == null) continue;

            List<List<PageTable.CellData>> newRows = new ArrayList<>();

            for (List<PageTable.CellData> row : ocrTable.cellGrid()) {
                int rowMinY = Integer.MAX_VALUE;
                int rowMaxY = Integer.MIN_VALUE;

                for (PageTable.CellData cell : row) {
                    if (cell.box() != null) {
                        rowMinY = Math.min(rowMinY, cell.box().y());
                        rowMaxY = Math.max(rowMaxY, cell.box().y() + cell.box().height());
                    }
                }

                if (rowMinY != Integer.MAX_VALUE) {
                    int rowCenterY = (rowMinY + rowMaxY) / 2;

                    if (rowCenterY > tabulaMaxY - gapThreshold || rowMinY > tabulaMaxY - minGap) {
                        String rowText = row.stream()
                                .map(c -> c.text() != null ? c.text().replaceAll("\\s+", "") : "")
                                .collect(Collectors.joining());

                        if (!rowText.isBlank() && !isDuplicateRow(originalDto, rowText)) {
                            newRows.add(row);
                        }
                    }
                }
            }

            if (!newRows.isEmpty()) {
                PageTable.TableData newTable = PageTable.TableData.builder()
                        .cellGrid(newRows)
                        .rows(newRows.stream().map(r -> r.stream().map(PageTable.CellData::text).toList()).toList())
                        .build();
                bottomTables.add(newTable);
            }
        }
        return bottomTables;
    }

    private PageTable extractTableFromMatFallback(Mat mat, String fileName, int pageNum, String extension, String jobId) {
        try {
            Mat grayMat = new Mat();
            if (mat.channels() > 1) {
                Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_BGR2GRAY);
            } else {
                mat.copyTo(grayMat);
            }

            PreprocessingDecision decision = imagePreprocessor.analyze(grayMat);
            Mat processedMat = imagePreprocessor.process(grayMat, decision);

            AnalysisResult extractionResult = pageAnalyzer.process(
                    processedMat, mat, pageNum, fileName, extension, ProcessingConstants.NAME_CREDITOR_LIST
            );

            ImageUtils.release(grayMat, processedMat);

            if (extractionResult != null && extractionResult.text() != null) {
                String rawText = extractionResult.text().trim();
                if (rawText.startsWith("{") || rawText.startsWith("[")) {
                    PageTable.TableData tableData = objectMapper.readValue(rawText, PageTable.TableData.class);
                    if (tableData.rows() != null && !tableData.rows().isEmpty()) {
                        return PageTable.builder()
                                .pageNumber(pageNum)
                                .tables(new ArrayList<>(List.of(tableData)))
                                .build();
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error extracting table from Mat fallback for page {}", pageNum, e);
        }
        return null;
    }

    private boolean isDuplicateRow(PageTable dto, String rowText) {
        if (dto.tables() == null || dto.tables().isEmpty()) return false;
        PageTable.TableData lastTable = dto.tables().get(dto.tables().size() - 1);
        if (lastTable.rows() == null || lastTable.rows().isEmpty()) return false;

        int startIdx = Math.max(0, lastTable.rows().size() - 3);
        for (int i = startIdx; i < lastTable.rows().size(); i++) {
            String existingRowText = String.join("", lastTable.rows().get(i)).replaceAll("\\s+", "");
            if (existingRowText.equals(rowText) || existingRowText.contains(rowText) || rowText.contains(existingRowText)) {
                return true;
            }
        }
        return false;
    }

    private boolean isCreditorListSection(int pageNum, List<SectionRange> sectionRanges) {
        return sectionRanges.stream()
                .anyMatch(r -> pageNum >= r.startPage() && pageNum <= r.endPage()
                        && ProcessingConstants.NAME_CREDITOR_LIST.equals(r.sectionName()));
    }

    private int calculateTabulaMaxY(PageTable dto) {
        int maxY = 0;
        for (PageTable.TableData t : dto.tables()) {
            if (t.cellGrid() != null) {
                for (List<PageTable.CellData> row : t.cellGrid()) {
                    for (PageTable.CellData cell : row) {
                        if (cell.box() != null) {
                            maxY = Math.max(maxY, cell.box().y() + cell.box().height());
                        }
                    }
                }
            }
        }
        return maxY;
    }

    private int calculateRepairedMaxY(TableStructureResult structure) {
        int maxY = 0;
        if (structure.cells() != null) {
            for (var cell : structure.cells()) {
                if (cell.getBoundingBox() != null) {
                    maxY = Math.max(maxY, cell.getBoundingBox().y + cell.getBoundingBox().height);
                }
            }
        }
        return maxY;
    }

    private boolean isCreditorListValid(PageTable dto) {
        if (dto.tables() == null || dto.tables().isEmpty()) return false;

        int totalRows = 0;
        boolean hasValidHeader = false;

        for (PageTable.TableData table : dto.tables()) {
            if (table.rows() != null) totalRows += table.rows().size();

            if (!hasValidHeader && table.headers() != null) {
                String headerStr = String.join("", table.headers()).replaceAll("\\s+", "");
                hasValidHeader = isCreditorHeaderValid(headerStr);
            }

            if (!hasValidHeader && table.rows() != null && !table.rows().isEmpty()) {
                String firstRow = String.join("", table.rows().get(0)).replaceAll("\\s+", "");
                hasValidHeader = isCreditorHeaderValid(firstRow);
            }
        }
        return totalRows >= 1;
    }

    private boolean isCreditorHeaderValid(String text) {
        return text.contains(ProcessingConstants.KEY_CREDITOR_NAME) ||
               (text.contains(ProcessingConstants.KEYWORD_PRINCIPAL) && text.contains(ProcessingConstants.KEYWORD_INTEREST));
    }

    private boolean isValidTableContent(PageTable dto) {
        if (dto.tables() == null || dto.tables().isEmpty()) return false;
        int totalRows = dto.tables().stream().filter(t -> t.rows() != null).mapToInt(t -> t.rows().size()).sum();
        return totalRows >= 2;
    }

    private List<PageTable> loadPreviousPageTables(AnalysisPayload previousResult) {
        List<PageTable> restored = new ArrayList<>();

        if (previousResult instanceof DiagnosticPayload dp) {
            List<Map<String, Object>> diagnostics = dp.getDiagnostics();
            if (diagnostics != null) {
                for (Map<String, Object> entry : diagnostics) {
                    Object obj = entry.get("pageTable");
                    if (obj == null) continue;

                    try {
                        if (obj instanceof PageTable pt) {
                            restored.add(pt);
                        } else {
                            PageTable pt = objectMapper.convertValue(obj, PageTable.class);
                            if (pt != null && pt.tables() != null) {
                                restored.add(pt);
                            }
                        }
                    } catch (Exception e) {
                        log.debug("Failed to restore PageTable from previous result", e);
                    }
                }
            }
        }

        return restored;
    }
}
