package com.softgram.ecfs.ocr.adapter.out.document;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.apache.commons.io.FilenameUtils;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softgram.ecfs.barcode.service.BarcodeService;
import com.softgram.ecfs.ocr.adapter.out.ocr.PageAnalyzer;
import com.softgram.ecfs.ocr.adapter.out.ocr.TableAnalyzer;
import com.softgram.ecfs.ocr.adapter.out.ocr.TabulaEngine;
import com.softgram.ecfs.ocr.adapter.out.ocr.TesseractEngine;
import com.softgram.ecfs.ocr.adapter.out.opencv.ImagePreprocessor;
import com.softgram.ecfs.ocr.application.port.out.EnginePort;
import com.softgram.ecfs.ocr.common.constant.FileConstants;
import com.softgram.ecfs.ocr.common.constant.ProcessingConstants;
import com.softgram.ecfs.ocr.common.exception.ProcessingException;
import com.softgram.ecfs.ocr.common.util.DebugUtils;
import com.softgram.ecfs.ocr.common.util.ImageUtils;
import com.softgram.ecfs.ocr.domain.model.document.DebtListRecord;
import com.softgram.ecfs.ocr.domain.model.document.DefaultRecord;
import com.softgram.ecfs.ocr.domain.model.document.PageTable;
import com.softgram.ecfs.ocr.domain.model.document.SectionType;
import com.softgram.ecfs.ocr.domain.model.analysis.AnalysisPayload;
import com.softgram.ecfs.ocr.domain.model.analysis.CellBox;
import com.softgram.ecfs.ocr.domain.model.analysis.DiagnosticPayload;
import com.softgram.ecfs.ocr.domain.model.analysis.ExtractedData;
import com.softgram.ecfs.ocr.domain.model.analysis.Field;
import com.softgram.ecfs.ocr.domain.model.analysis.Section;
import com.softgram.ecfs.ocr.domain.model.analysis.SectionPayload;
import com.softgram.ecfs.ocr.domain.model.analysis.SectionRange;
import com.softgram.ecfs.ocr.domain.model.vision.AnalysisResult;
import com.softgram.ecfs.ocr.domain.model.vision.PreprocessingDecision;
import com.softgram.ecfs.ocr.domain.model.vision.PreprocessingDecision.PreprocessLevel;
import com.softgram.ecfs.ocr.domain.parser.ParserFactory;
import com.softgram.ecfs.ocr.domain.parser.SectionParser;
import com.softgram.ecfs.ocr.domain.service.TextCleaner;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 문서 타입별 분석 공정을 총괄하고 각 엔진을 조정하는 어댑터.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentAnalyzer implements EnginePort {

    /** PDF 렌더링 DPI */
    private static final float PDF_RENDER_DPI = 300.0f;

    /** PDF 처리용 최대 메모리 (50MB) */
    private static final long PDF_MEMORY_USAGE = 50 * 1024 * 1024L;

    /** 분석 결과 디렉터리 */
    private static final String DEBUG_DIR_PARSED = "parsed";

    /** 디버그 이미지 디렉터리 */
    private static final String DEBUG_DIR_DEBUG = "debug";

    /** 허프 변환 결과 디렉터리 */
    private static final String DEBUG_DIR_HOUGH = "hough";

    /** 디버그 접미사 */
    private static final String DEBUG_SUFFIX_DEBUG = "debug";

    /** 허프 변환 접미사 */
    private static final String DEBUG_SUFFIX_HOUGH = "hough";

    private final PdfProcessor pdfProcessor;
    private final TableExtractor tableExtractor;
    private final ImagePreprocessor imagePreprocessor;
    private final PageAnalyzer pageAnalyzer;
    private final TableAnalyzer tableAnalyzer;
    private final TabulaEngine tabulaEngine;
    private final BarcodeService barcodeService;
    private final TesseractEngine tesseractEngine;
    private final ObjectMapper objectMapper;
    private final ParserFactory parserFactory;

    /**
     * 데이터 추출 공정을 실행합니다.
     */
    @Override
    public AnalysisPayload extractData(OcrEngineRequest request) {
        return processFile(request.file(), request.originalFileName(), ProcessingConstants.OPERATION_ANALYZE_DATA,
                request.jobId(), request.processedPages(), request.previousResult(), request.existingRanges(),
                request.onProgress());
    }

    /**
     * 이미지 품질 진단 공정을 실행합니다.
     */
    @Override
    public AnalysisPayload analyzeQuality(OcrEngineRequest request) {
        return processFile(request.file(), request.originalFileName(), ProcessingConstants.OPERATION_ANALYZE_QUALITY,
                request.jobId(), request.processedPages(), request.previousResult(), request.existingRanges(),
                request.onProgress());
    }

    /**
     * 문서 구조 분석 공정을 실행합니다.
     */
    @Override
    public AnalysisPayload analyzeStructure(OcrEngineRequest request) {
        return processFile(request.file(), request.originalFileName(), ProcessingConstants.OPERATION_ANALYZE_CONTOUR,
                request.jobId(), request.processedPages(), request.previousResult(), request.existingRanges(),
                request.onProgress());
    }

    /**
     * 파일 타입에 따른 분석 프로세스를 수행합니다.
     */
    private AnalysisPayload processFile(File file, String originalFileName, String operation, String jobId,
            Set<Integer> processedPages, AnalysisPayload previousResult, List<SectionRange> existingRanges,
            Consumer<AnalysisPayload> onProgress) {
        if (file == null || !file.exists()) {
            throw new IllegalArgumentException("Target file not found: " + (file != null ? file.getName() : "null"));
        }

        log.info("Process doc: file={}, op={}, id={}, resume={}", originalFileName, operation, jobId,
                processedPages.size());

        try {
            String extension = FilenameUtils.getExtension(originalFileName);
            FileConstants fileType = FileConstants.from(extension);

            Set<Integer> currentProcessedPages = processedPages != null ? new HashSet<>(processedPages)
                    : new HashSet<>();
            List<SectionRange> currentSectionRanges = existingRanges != null ? new ArrayList<>(existingRanges)
                    : new ArrayList<>();
            Set<Integer> targetPages = new HashSet<>();

            Map<Integer, PreprocessingDecision> preprocessCache = new HashMap<>();

            AnalysisPayload rawResult = switch (fileType) {
                case PDF -> processPdf(file, originalFileName, extension, operation, jobId,
                        currentProcessedPages, previousResult, currentSectionRanges, targetPages, onProgress,
                        preprocessCache);
                case TIF, TIFF ->
                    processTiff(file, originalFileName, extension, operation, jobId, currentProcessedPages,
                            previousResult, onProgress);
                default -> processImage(file, originalFileName, extension, operation, jobId);
            };
            return rawResult;
        } catch (Exception t) {
            if (t instanceof ProcessingException pe && "Operation interrupted".equals(pe.getMessage())) {
                log.info("Processing interrupted for file: {} (JobId: {})", originalFileName, jobId);
                throw pe;
            }
            log.error("Critical error processing file: {} (JobId: {})", originalFileName, jobId, t);
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("Error processing file", t);
        }
    }

    /**
     * PDF 문서를 분석합니다.
     */
    private AnalysisPayload processPdf(File file, String fileName, String extension, String operation, String jobId,
            Set<Integer> processedPages, AnalysisPayload previousResult, List<SectionRange> sectionRanges,
            Set<Integer> targetPages, Consumer<AnalysisPayload> onProgress,
            Map<Integer, PreprocessingDecision> preprocessCache) throws Exception {

        File fileToProcess = pdfProcessor.preprocessPdfIfNecessary(file, extension);

        try (PDDocument document = PDDocument.load(fileToProcess,
                MemoryUsageSetting.setupMixed(PDF_MEMORY_USAGE))) {
            
            if (ProcessingConstants.OPERATION_ANALYZE_DATA.equals(operation)) {
                List<Section> sections = extractSectionsAndTables(document, fileToProcess, fileName, extension, operation, jobId,
                        processedPages, previousResult, sectionRanges, targetPages, onProgress, preprocessCache);
                return new SectionPayload(sections);
            } else if (ProcessingConstants.OPERATION_ANALYZE_QUALITY.equals(operation)) {
                List<Map<String, Object>> diagnostics = processPdfPages(document, fileToProcess, fileName, extension, jobId,
                        processedPages, previousResult, onProgress, this::debugImagePage);
                return new DiagnosticPayload(diagnostics);
            } else if (ProcessingConstants.OPERATION_ANALYZE_CONTOUR.equals(operation)) {
                List<Map<String, Object>> diagnostics = processPdfPages(document, fileToProcess, fileName, extension, jobId,
                        processedPages, previousResult, onProgress, this::analyzeStructurePage);
                return new DiagnosticPayload(diagnostics);
            }
            throw new IllegalArgumentException("Unsupported operation for PDF: " + operation);
        } finally {
            if (fileToProcess != file && fileToProcess.exists()) {
                try {
                    Files.deleteIfExists(fileToProcess.toPath());
                    log.info("Deleted temporary split PDF: {}", fileToProcess.getAbsolutePath());
                } catch (IOException e) {
                    log.warn("Failed to delete temporary split PDF", e);
                }
            }
        }
    }

    /**
     * 구역 식별 및 표 데이터를 추출하여 결합합니다.
     */
    private List<Section> extractSectionsAndTables(PDDocument document, File file, String fileName, String extension, String operation,
            String jobId, Set<Integer> processedPages, AnalysisPayload previousResult, List<SectionRange> sectionRanges,
            Set<Integer> targetPages, Consumer<AnalysisPayload> onProgress,
            Map<Integer, PreprocessingDecision> preprocessCache) throws Exception {
        
        boolean isSearchable = tabulaEngine.isSearchablePdf(file, fileName);
        clearStaleDebugImages(fileName, extension);

        identifySections(document, document.getNumberOfPages(), fileName, extension, sectionRanges, isSearchable,
                ranges -> {
                    if (onProgress != null) {
                        List<Section> intermediateSections = ranges.stream()
                                .map(range -> Section.builder()
                                        .section(range.sectionName())
                                        .startPage(range.startPage())
                                        .endPage(range.endPage())
                                        .build())
                                .toList();
                        onProgress.accept(new SectionPayload(intermediateSections));
                    }
                }, preprocessCache);

        Set<String> excludeSections = Set.of(ProcessingConstants.NAME_SUBMISSION, ProcessingConstants.NAME_UNKNOWN);
        for (SectionRange range : sectionRanges) {
            if (!excludeSections.contains(range.sectionName())
                    && !range.sectionName().endsWith(ProcessingConstants.NAME_COURT)) {
                for (int i = range.startPage(); i <= range.endPage(); i++) {
                    targetPages.add(i);
                }
            }
        }

        List<PageTable> pageTables = tableExtractor.executeExtraction(document, file, fileName, extension, jobId,
                processedPages, previousResult, sectionRanges, targetPages, isSearchable,
                (List<PageTable> tables) -> {                    if (onProgress != null) {
                        List<Map<String, Object>> diagnostics = tables.stream()
                                .map(table -> {
                                    Map<String, Object> map = new HashMap<>();
                                    map.put("pageTable", table);
                                    return map;
                                })
                                .collect(Collectors.toList());
                        onProgress.accept(new DiagnosticPayload(diagnostics));
                    }
                });

        List<PageTable> scannedResults = extractTablesFromScannedPdf(document, file, fileName, extension, jobId,
                processedPages, targetPages, sectionRanges, tables -> {
                    if (onProgress != null) {
                        List<Map<String, Object>> diagnostics = tables.stream()
                                .map(table -> {
                                    Map<String, Object> map = new HashMap<>();
                                    map.put("pageTable", table);
                                    return map;
                                })
                                .collect(Collectors.toList());
                        onProgress.accept(new DiagnosticPayload(diagnostics));
                    }
                }, new ArrayList<>(pageTables), preprocessCache);
        
        pageTables.addAll(scannedResults);
        pageTables.sort((a, b) -> Integer.compare(a.pageNumber(), b.pageNumber()));

        return mergeSectionsAndTables(document, file, sectionRanges, pageTables, extension, previousResult, jobId,
                isSearchable);
    }

    private void identifySections(PDDocument document, int numPages, String fileName, String extension,
            List<SectionRange> sectionRanges, boolean isSearchable, Consumer<List<SectionRange>> onProgress,
            Map<Integer, PreprocessingDecision> preprocessCache)
            throws Exception {
        String currentSectionName = null;
        int startPage = -1;
        Set<Integer> identifiedPages = sectionRanges.stream()
                .flatMap(r -> java.util.stream.IntStream.rangeClosed(r.startPage(), r.endPage()).boxed())
                .collect(Collectors.toSet());

        PDFRenderer pdfRenderer = new PDFRenderer(document);
        PDFTextStripper stripper = null;
        if (isSearchable) {
            try {
                stripper = new PDFTextStripper();
            } catch (IOException ignored) {}
        }

        for (int i = 0; i < numPages; i++) {
            if (Thread.currentThread().isInterrupted())
                throw new ProcessingException("Operation interrupted");

            int pageNum = i + 1;
            if (identifiedPages.contains(pageNum))
                continue;

            String detectedSection;
            try {
                detectedSection = detectSectionForPage(document, pdfRenderer, stripper, pageNum, fileName,
                        extension, isSearchable, preprocessCache);
            } catch (Exception e) {
                detectedSection = ProcessingConstants.NAME_UNKNOWN;
            }

            if (ProcessingConstants.NAME_SUBMISSION.equals(detectedSection)) {
                if (currentSectionName != null) {
                    sectionRanges.add(new SectionRange(currentSectionName, startPage, pageNum - 1));
                }
                currentSectionName = null;
                startPage = -1;
                continue;
            }

            if (currentSectionName == null) {
                currentSectionName = detectedSection;
                startPage = pageNum;
            } else if (!ProcessingConstants.NAME_UNKNOWN.equals(detectedSection)
                    && !detectedSection.equals(currentSectionName)) {
                sectionRanges.add(new SectionRange(currentSectionName, startPage, pageNum - 1));
                currentSectionName = detectedSection;
                startPage = pageNum;
            }
        }

        if (currentSectionName != null && startPage != -1) {
            sectionRanges.add(new SectionRange(currentSectionName, startPage, numPages));
        }

        sectionRanges.sort((a, b) -> Integer.compare(a.startPage(), b.startPage()));
        if (onProgress != null)
            onProgress.accept(new ArrayList<>(sectionRanges));
    }

    private String detectSectionForPage(PDDocument document, PDFRenderer pdfRenderer, PDFTextStripper stripper,
            int pageNum, String fileName, String extension, boolean isSearchable,
            Map<Integer, PreprocessingDecision> preprocessCache) throws IOException {
        String detected = ProcessingConstants.NAME_UNKNOWN;

        if (isSearchable && stripper != null) {
            try {
                stripper.setStartPage(pageNum);
                stripper.setEndPage(pageNum);
                String pageText = stripper.getText(document);
                if (pageText != null && !pageText.isEmpty()) {
                    detected = pageText.lines().map(String::trim).filter(l -> !l.isBlank())
                            .map(l -> l.replaceAll("\\s+", ""))
                            .map(SectionType::identify)
                            .filter(s -> !ProcessingConstants.NAME_UNKNOWN.equals(s))
                            .findFirst().orElse(ProcessingConstants.NAME_UNKNOWN);
                }
            } catch (Exception ignored) {}
        }

        if (ProcessingConstants.NAME_UNKNOWN.equals(detected)) {
            BufferedImage image = pdfRenderer.renderImageWithDPI(pageNum - 1, PDF_RENDER_DPI, ImageType.RGB);
            if (image != null) {
                try {
                    Mat mat = ImageUtils.toMat(image);
                    Mat gray = new Mat();
                    Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY);

                    PreprocessingDecision decision = preprocessCache.computeIfAbsent(pageNum, k -> imagePreprocessor.analyze(gray));
                    Mat processed = imagePreprocessor.process(gray, decision);

                    AnalysisResult ocrResult = pageAnalyzer.process(processed, mat, pageNum, fileName, extension, null);

                    if (ocrResult != null && ocrResult.text() != null) {
                        try {
                            Map<String, Object> dataMap = objectMapper.readValue(ocrResult.text(), new TypeReference<Map<String, Object>>() {});
                            detected = (String) dataMap.getOrDefault(ProcessingConstants.KEY_SECTION, ProcessingConstants.NAME_UNKNOWN);
                        } catch (Exception e) {
                            detected = SectionType.identify(ocrResult.text().replaceAll("\\s+", ""));
                        }
                    }
                    ImageUtils.release(mat, gray, processed);
                    if (ocrResult != null)
                        ImageUtils.release(ocrResult.image(), ocrResult.roiImage());
                } catch (Exception e) {
                    log.warn("Failed to detect section from image for page {}", pageNum, e);
                } finally {
                    image.flush();
                }
            }
        }
        return detected;
    }

    private List<Section> mergeSectionsAndTables(PDDocument document, File pdfFile, List<SectionRange> sectionRanges,
            List<PageTable> pageTables, String extension, AnalysisPayload previousResult, String jobId,
            boolean isSearchable) {
        List<Section> results = new ArrayList<>();
        Map<Integer, PageTable> pageTableMap = pageTables.stream()
                .collect(Collectors.toMap(PageTable::pageNumber, dto -> dto, (a, b) -> a));
        Map<Integer, List<ExtractedData>> prevPageDataMap = extractPreviousPageData(previousResult);

        for (SectionRange section : sectionRanges) {
            List<PageTable> sectionPages = new ArrayList<>();
            List<ExtractedData> recoveredData = new ArrayList<>();

            for (int pageNum = section.startPage(); pageNum <= section.endPage(); pageNum++) {
                if (pageTableMap.containsKey(pageNum)) {
                    sectionPages.add(pageTableMap.get(pageNum));
                } else if (prevPageDataMap.containsKey(pageNum)) {
                    recoveredData.addAll(prevPageDataMap.get(pageNum));
                }
            }

            SectionParser parser = parserFactory.getParser(section.sectionName());
            List<ExtractedData> parsedData = parser.parse(sectionPages);

            if (!recoveredData.isEmpty()) {
                parsedData.addAll(recoveredData);
            }

            parsedData.sort((a, b) -> Integer.compare(getPageNum(a), getPageNum(b)));

            visualizeExtractionResult(document, pdfFile, parsedData, section, extension, jobId, isSearchable);
            cleanDebugData(parsedData);

            results.add(Section.builder()
                    .section(section.sectionName())
                    .startPage(section.startPage())
                    .endPage(section.endPage())
                    .data(parsedData)
                    .build());
        }
        return results;
    }

    private Map<Integer, List<ExtractedData>> extractPreviousPageData(AnalysisPayload previousResult) {
        Map<Integer, List<ExtractedData>> map = new HashMap<>();
        if (previousResult instanceof SectionPayload sp) {
            for (Section ds : sp.sections()) {
                List<ExtractedData> data = ds.data();
                if (data != null) {
                    for (ExtractedData row : data) {
                        Object pObj = row.get(ProcessingConstants.KEY_PAGE);
                        if (pObj != null) {
                            try {
                                int p = Integer.parseInt(pObj.toString());
                                map.computeIfAbsent(p, k -> new ArrayList<>()).add(row);
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            }
        }
        return map;
    }

    private int getPageNum(ExtractedData row) {
        try {
            return Integer.parseInt(row.get(ProcessingConstants.KEY_PAGE).toString());
        } catch (Exception e) {
            return 0;
        }
    }

    private void visualizeExtractionResult(PDDocument document, File pdfFile, List<ExtractedData> data, SectionRange sectionRange,
            String extension, String jobId, boolean isSearchable) {
        if (data == null || data.isEmpty()) return;

        Map<Integer, List<ExtractedData>> pageDataMap = new HashMap<>();
        for (ExtractedData record : data) {
            Set<Integer> pagesForRecord = new HashSet<>();
            Object pObj = record.get(ProcessingConstants.KEY_PAGE);
            if (pObj != null) {
                try {
                    pagesForRecord.add(Integer.parseInt(pObj.toString()));
                } catch (NumberFormatException ignored) {}
            }

            for (Object val : record.toMap().values()) {
                if (val instanceof Field field) {
                    if (field.getPage() > 0) pagesForRecord.add(field.getPage());
                    for (CellBox box : field.getLocations()) {
                        if (box.page() > 0) pagesForRecord.add(box.page());
                    }
                }
            }
            if (pagesForRecord.isEmpty()) pagesForRecord.add(sectionRange.startPage());
            pagesForRecord.forEach(p -> pageDataMap.computeIfAbsent(p, k -> new ArrayList<>()).add(record));
        }

        try {
            PDFRenderer pdfRenderer = new PDFRenderer(document);
            Set<String> targetFields = Set.of(ProcessingConstants.KEY_CREDITOR_NO,
                    ProcessingConstants.KEY_CREDITOR_NAME, ProcessingConstants.KEY_PRINCIPAL,
                    ProcessingConstants.KEY_INTEREST);

            for (int pageNum = sectionRange.startPage(); pageNum <= sectionRange.endPage(); pageNum++) {
                Mat debugMat = null;
                boolean shouldSave = false;

                BufferedImage image = pdfRenderer.renderImageWithDPI(pageNum - 1, PDF_RENDER_DPI, ImageType.RGB);
                if (image != null) {
                    Mat mat = ImageUtils.toMat(image);
                    if (ProcessingConstants.NAME_CREDITOR_LIST.equals(sectionRange.sectionName())) {
                        try {
                            Mat gray = new Mat();
                            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY);
                            var structureResult = tableAnalyzer.analyzeTableStructure(gray, mat, new Point(0, 0),
                                    pdfFile.getName(), pageNum, extension, sectionRange.sectionName(), isSearchable);
                            ImageUtils.release(gray);

                            if (structureResult.debugImage() != null && !structureResult.debugImage().empty()) {
                                debugMat = structureResult.debugImage().clone();
                            } else {
                                debugMat = DebugUtils.createDebugImage(mat);
                            }
                            ImageUtils.release(structureResult.binaryMask(), structureResult.repairedImage(), structureResult.debugImage());
                            shouldSave = true;
                        } catch (Exception e) {
                            debugMat = DebugUtils.createDebugImage(mat);
                        }
                    } else {
                        debugMat = DebugUtils.createDebugImage(mat);
                    }
                    ImageUtils.release(mat);
                    image.flush();
                }

                if (debugMat == null || debugMat.empty()) continue;

                List<ExtractedData> pageRecords = pageDataMap.getOrDefault(pageNum, List.of());
                for (ExtractedData record : pageRecords) {
                    for (Map.Entry<String, Object> entry : record.toMap().entrySet()) {
                        if (targetFields.contains(entry.getKey()) && entry.getValue() instanceof Field field) {
                            for (CellBox box : field.getLocations()) {
                                int boxPage = box.page() == 0 ? field.getPage() : box.page();
                                if (boxPage == pageNum) {
                                    Rect rect = new Rect(box.x(), box.y(), box.width(), box.height());
                                    Imgproc.rectangle(debugMat, rect, new Scalar(0, 255, 0), 2);
                                    shouldSave = true;
                                }
                            }
                        }
                    }
                }

                if (shouldSave) {
                    DebugUtils.saveDebugImage(debugMat, pdfFile.getName(), pageNum, jobId, extension, DEBUG_DIR_PARSED, DEBUG_DIR_PARSED);
                }
                ImageUtils.release(debugMat);
            }
        } catch (Exception e) {
            log.warn("Visual debugging failed for section {}", sectionRange.sectionName(), e);
        }
    }

    private void cleanDebugData(List<ExtractedData> data) {
        if (data != null) {
            data.forEach(record -> {
                if (record instanceof DefaultRecord dr) {
                    dr.toMap().remove(ProcessingConstants.KEY_PAGE);
                } else if (record instanceof DebtListRecord dr) {
                    dr.setPage(null);
                }
            });
        }
    }

    private List<PageTable> extractTablesFromScannedPdf(PDDocument document, File file, String fileName, String extension, String jobId,
            Set<Integer> processedPages, Set<Integer> targetPages, List<SectionRange> sectionRanges,
            Consumer<List<PageTable>> onProgress, List<PageTable> accumulatedResults,
            Map<Integer, PreprocessingDecision> preprocessCache) throws Exception {
        
        List<PageTable> results = new ArrayList<>();
        PDFRenderer pdfRenderer = new PDFRenderer(document);
        
        for (int i = 0; i < document.getNumberOfPages(); i++) {
            if (Thread.currentThread().isInterrupted())
                throw new ProcessingException("Operation interrupted");
            int pageNum = i + 1;
            if (processedPages.contains(pageNum) || (!targetPages.isEmpty() && !targetPages.contains(pageNum)))
                continue;

            String currentSectionName = sectionRanges.stream()
                    .filter(r -> pageNum >= r.startPage() && pageNum <= r.endPage())
                    .map(SectionRange::sectionName).findFirst().orElse(ProcessingConstants.NAME_UNKNOWN);

            BufferedImage image = pdfRenderer.renderImageWithDPI(i, PDF_RENDER_DPI, ImageType.RGB);
            if (image != null) {
                Map<String, Object> ocrMap = extractDataPage(image, pageNum, fileName, extension,
                        currentSectionName, jobId, preprocessCache);
                String rawOcrResult = ocrMap.containsKey(ProcessingConstants.KEY_DATA)
                        ? objectMapper.writeValueAsString(ocrMap.get(ProcessingConstants.KEY_DATA))
                        : (String) ocrMap.getOrDefault(ProcessingConstants.KEY_TEXT, "");

                if (rawOcrResult != null && !rawOcrResult.isBlank()) {
                    try {
                        PageTable.TableData tableData = objectMapper.readValue(rawOcrResult, PageTable.TableData.class);
                        if (tableData.rows() != null && !tableData.rows().isEmpty()) {
                            PageTable newPage = PageTable.builder().pageNumber(pageNum).tables(new ArrayList<>(List.of(tableData))).build();
                            results.add(newPage);
                            accumulatedResults.add(newPage);
                            processedPages.add(pageNum);
                            if (onProgress != null)
                                onProgress.accept(new ArrayList<>(accumulatedResults));
                        }
                    } catch (Exception ignored) {}
                }
                image.flush();
            }
        }
        return results;
    }

    @FunctionalInterface
    private interface PageProcessor {
        Map<String, Object> process(BufferedImage image, int pageNum, String fileName, String extension, String jobId);
    }

    private List<Map<String, Object>> processPdfPages(PDDocument document, File file, String fileName, String extension, String jobId,
            Set<Integer> processedPages, AnalysisPayload previousResult, Consumer<AnalysisPayload> onProgress,
            PageProcessor processor) throws Exception {

        List<Map<String, Object>> results = new ArrayList<>();
        PDFRenderer pdfRenderer = new PDFRenderer(document);
        
        for (int i = 0; i < document.getNumberOfPages(); i++) {
            if (Thread.currentThread().isInterrupted())
                throw new ProcessingException("Operation interrupted");
            int pageNum = i + 1;
            if (processedPages.contains(pageNum))
                continue;

            BufferedImage image = pdfRenderer.renderImageWithDPI(i, PDF_RENDER_DPI, ImageType.RGB);
            if (image != null) {
                results.add(processor.process(image, pageNum, fileName, extension, jobId));
                image.flush();
                if (onProgress != null)
                    onProgress.accept(new DiagnosticPayload(results));
            }
        }
        results.sort((a, b) -> Integer.compare((Integer) a.getOrDefault(ProcessingConstants.KEY_PAGE, 0),
                (Integer) b.getOrDefault(ProcessingConstants.KEY_PAGE, 0)));
        return results;
    }

    private AnalysisPayload processTiff(File file, String fileName, String extension, String operation, String jobId,
            Set<Integer> processedPages, AnalysisPayload previousResult, Consumer<AnalysisPayload> onProgress)
            throws IOException {
        List<Map<String, Object>> results = new ArrayList<>();

        try (ImageInputStream iis = ImageIO.createImageInputStream(file)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (readers.hasNext()) {
                ImageReader reader = readers.next();
                try {
                    reader.setInput(iis);
                    for (int i = 0; i < reader.getNumImages(true); i++) {
                        if (Thread.currentThread().isInterrupted())
                            throw new ProcessingException("Operation interrupted");
                        int pageNum = i + 1;
                        if (processedPages.contains(pageNum))
                            continue;

                        BufferedImage image = reader.read(i);
                        Map<String, Object> pageResult;

                        if (ProcessingConstants.OPERATION_ANALYZE_DATA.equals(operation)) {
                            pageResult = new LinkedHashMap<>();
                            pageResult.put(ProcessingConstants.KEY_PAGE, pageNum);
                            try {
                                pageResult.put("barcode", barcodeService.decode(image));
                                Mat mat = ImageUtils.toMat(image);
                                Mat gray = new Mat();
                                if (mat.channels() > 1)
                                    Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY);
                                else
                                    mat.copyTo(gray);

                                String sanitizedText = sanitizeOcrText(tesseractEngine.extractText(gray));
                                pageResult.put(ProcessingConstants.KEY_TEXT,
                                        sanitizedText.lines().collect(Collectors.toList()));
                                pageResult.put(ProcessingConstants.KEY_MESSAGE, "Success");

                                ImageUtils.release(mat, gray);
                            } catch (Exception e) {
                                pageResult.put(ProcessingConstants.KEY_TEXT, "");
                                pageResult.put(ProcessingConstants.KEY_MESSAGE, "Extraction failed: " + e.getMessage());
                            }
                        } else if (ProcessingConstants.OPERATION_ANALYZE_QUALITY.equals(operation)) {
                            pageResult = debugImagePage(image, pageNum, fileName, extension, jobId);
                        } else if (ProcessingConstants.OPERATION_ANALYZE_CONTOUR.equals(operation)) {
                            pageResult = analyzeStructurePage(image, pageNum, fileName, extension, jobId);
                        } else {
                            pageResult = extractDataPage(image, pageNum, fileName, extension,
                                    ProcessingConstants.NAME_UNKNOWN, jobId, null);
                        }

                        results.add(pageResult);
                        image.flush();
                        if (onProgress != null)
                            onProgress.accept(new DiagnosticPayload(results));
                    }
                    results.sort((a, b) -> Integer.compare((Integer) a.getOrDefault(ProcessingConstants.KEY_PAGE, 0),
                            (Integer) b.getOrDefault(ProcessingConstants.KEY_PAGE, 0)));
                } finally {
                    reader.dispose();
                }
            }
        }
        return new DiagnosticPayload(results);
    }

    private String sanitizeOcrText(String text) {
        if (text == null || text.isBlank())
            return "";
        return text.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "").lines()
                .map(TextCleaner::clean).filter(l -> !l.isEmpty()).collect(Collectors.joining("\n"));
    }

    private AnalysisPayload processImage(File file, String fileName, String extension, String operation, String jobId)
            throws Exception {
        BufferedImage image = ImageIO.read(file);
        if (image == null)
            throw new ProcessingException("Invalid image file: " + fileName);

        Map<String, Object> result;
        if (ProcessingConstants.OPERATION_ANALYZE_QUALITY.equals(operation))
            result = debugImagePage(image, 1, fileName, extension, jobId);
        else if (ProcessingConstants.OPERATION_ANALYZE_CONTOUR.equals(operation))
            result = analyzeStructurePage(image, 1, fileName, extension, jobId);
        else
            result = extractDataPage(image, 1, fileName, extension, ProcessingConstants.NAME_UNKNOWN, jobId, null);

        return new DiagnosticPayload(List.of(result));
    }

    private Map<String, Object> debugImagePage(BufferedImage image, int pageNum, String originalFileName,
            String extension, String jobId) {
        Mat originalMat = ImageUtils.toMat(image);
        Mat grayMat = new Mat();
        try {
            Imgproc.cvtColor(originalMat, grayMat, Imgproc.COLOR_BGR2GRAY);
            PreprocessingDecision decision = imagePreprocessor.analyze(grayMat);
            return executeDebugAnalysis(decision, originalMat, originalFileName, pageNum, extension, jobId);
        } finally {
            ImageUtils.release(originalMat, grayMat);
        }
    }

    private Map<String, Object> analyzeStructurePage(BufferedImage image, int pageNum, String originalFileName,
            String extension, String jobId) {
        Mat originalMat = ImageUtils.toMat(image);
        Mat grayMat = new Mat();
        Mat processedMat = null;
        try {
            Imgproc.cvtColor(originalMat, grayMat, Imgproc.COLOR_BGR2GRAY);
            processedMat = grayMat.clone();
            AnalysisResult extractionResult = pageAnalyzer.visualizeContours(processedMat, originalMat, pageNum);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put(ProcessingConstants.KEY_PAGE, pageNum);
            if (extractionResult == null) {
                result.put(ProcessingConstants.KEY_MESSAGE, "Skipped (No table structure detected)");
            } else {
                saveDebugImages(ProcessingConstants.OPERATION_ANALYZE_CONTOUR, extractionResult, originalFileName,
                        pageNum, extension, jobId);
                result.put(ProcessingConstants.KEY_MESSAGE, extractionResult.text());
                ImageUtils.release(extractionResult.image(), extractionResult.roiImage());
            }
            return result;
        } finally {
            ImageUtils.release(originalMat, grayMat, processedMat);
        }
    }

    private Map<String, Object> extractDataPage(BufferedImage image, int pageNum, String originalFileName,
            String extension, String sectionName, String jobId,
            Map<Integer, PreprocessingDecision> preprocessCache) {
        Mat originalMat = ImageUtils.toMat(image);
        Mat grayMat = new Mat();
        Mat processedMat = null;
        try {
            Imgproc.cvtColor(originalMat, grayMat, Imgproc.COLOR_BGR2GRAY);
            PreprocessingDecision decision;
            if (preprocessCache != null) {
                decision = preprocessCache.computeIfAbsent(pageNum, k -> imagePreprocessor.analyze(grayMat));
            } else {
                decision = imagePreprocessor.analyze(grayMat);
            }
            processedMat = imagePreprocessor.process(grayMat, decision);

            AnalysisResult extractionResult = pageAnalyzer.process(processedMat, originalMat, pageNum, originalFileName,
                    extension, sectionName);
            saveDebugImages(ProcessingConstants.OPERATION_ANALYZE_DATA, extractionResult, originalFileName, pageNum,
                    extension, jobId);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put(ProcessingConstants.KEY_PAGE, pageNum);

            String extractedText = extractionResult.text();
            if (extractedText != null && !extractedText.isBlank()) {
                String trimmed = extractedText.trim();
                if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                    try {
                        result.put(ProcessingConstants.KEY_DATA,
                                objectMapper.readValue(trimmed, new TypeReference<Object>() {}));
                    } catch (Exception e) {
                        result.put(ProcessingConstants.KEY_TEXT, extractedText);
                    }
                } else {
                    result.put(ProcessingConstants.KEY_TEXT, extractedText);
                }
            } else {
                result.put(ProcessingConstants.KEY_TEXT, "");
            }
            ImageUtils.release(extractionResult.image(), extractionResult.roiImage());
            return result;
        } catch (Exception e) {
            throw new ProcessingException("OCR Extraction failed", e);
        } finally {
            ImageUtils.release(originalMat, grayMat, processedMat);
        }
    }

    private Map<String, Object> executeDebugAnalysis(PreprocessingDecision decision, Mat originalMat,
            String originalFileName, int pageNum, String outputSubDir, String jobId) {
        String decisionText = decision.level().toString();
        String debugMessage = String.format("%s (%s)", decisionText, decision.reason());
        Mat debugCanvas = originalMat.clone();

        if (debugCanvas.channels() == 1)
            Imgproc.cvtColor(debugCanvas, debugCanvas, Imgproc.COLOR_GRAY2BGR);
        Scalar textColor = (decision.level() != PreprocessLevel.NONE) ? new Scalar(0, 0, 255) : new Scalar(0, 255, 0);
        Imgproc.putText(debugCanvas, debugMessage, new Point(30, 80), Imgproc.FONT_HERSHEY_SIMPLEX, 1.2, textColor, 2);

        DebugUtils.saveDebugImage(debugCanvas, originalFileName, pageNum, jobId, outputSubDir,
                DEBUG_DIR_DEBUG, DEBUG_SUFFIX_DEBUG);
        ImageUtils.release(debugCanvas);

        Map<String, Object> resultMap = new LinkedHashMap<>();
        resultMap.put(ProcessingConstants.KEY_PAGE, pageNum);
        resultMap.put("decision", decisionText);
        resultMap.put("reason", decision.reason());
        resultMap.put(ProcessingConstants.KEY_MESSAGE, debugMessage);
        return resultMap;
    }

    private void saveDebugImages(String operation, AnalysisResult result, String fileName, int pageNum,
            String typeSubDir, String jobId) {
        if (ProcessingConstants.OPERATION_ANALYZE_CONTOUR.equals(operation)) {
            saveImage(result.image(), DEBUG_DIR_HOUGH, DEBUG_SUFFIX_HOUGH,
                    fileName, pageNum, typeSubDir, jobId);
        }
    }

    private void saveImage(Mat image, String modeSubDir, String fileSuffix, String originalFileName, int pageNum,
            String typeSubDir, String jobId) {
        if (image != null && !image.empty()) {
            DebugUtils.saveDebugImage(image, originalFileName, pageNum, jobId, typeSubDir, modeSubDir, fileSuffix);
        }
    }

    public String toPrettyJson(Object data) {
        if (data instanceof String str)
            return str;
        try {
            DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
            printer.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
            return objectMapper.writer(printer).writeValueAsString(data);
        } catch (Exception e) {
            return "[]";
        }
    }

    private void clearStaleDebugImages(String originalFileName, String extension) {
        try {
            String cleanFileName = originalFileName.replaceAll("^[0-9a-f]{8}-([0-9a-f]{4}-){3}[0-9a-f]{12}_(?:\\d+_)?",
                    "");
            String baseFileName = cleanFileName.contains(".")
                    ? cleanFileName.substring(0, cleanFileName.lastIndexOf('.'))
                    : cleanFileName;

            Path dirPath = Paths.get(System.getProperty("user.dir"), "temp", extension,
                    DEBUG_DIR_PARSED);
            if (Files.exists(dirPath)) {
                try (var files = Files.list(dirPath)) {
                    files.filter(p -> p.getFileName().toString().contains(baseFileName)).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {}
                    });
                }
            }
        } catch (Exception ignored) {}
    }
}
