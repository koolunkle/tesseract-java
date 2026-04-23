package com.softgram.ecfs.ocr.adapter.out.ocr;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import com.softgram.ecfs.ocr.common.exception.ProcessingException;
import com.softgram.ecfs.ocr.domain.model.analysis.CellBox;
import com.softgram.ecfs.ocr.domain.model.document.PageTable;
import com.softgram.ecfs.ocr.domain.model.document.PageTable.CellData;
import com.softgram.ecfs.ocr.domain.model.document.PageTable.TableData;
import com.softgram.ecfs.ocr.domain.service.TextCleaner;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import technology.tabula.ObjectExtractor;
import technology.tabula.Page;
import technology.tabula.Table;
import technology.tabula.extractors.BasicExtractionAlgorithm;
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm;

/**
 * PDF 텍스트 레이어를 분석하여 구조화된 표 데이터를 추출하는 어댑터.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TabulaEngine {

    /** 텍스트 레이어 확인용 최대 페이지 수 */
    private static final int MAX_CHECK_PAGES = 5;

    /** 유효 텍스트 판단을 위한 최소 문자 수 */
    private static final int MIN_TEXT_LEN_PER_PAGE = 10;

    /** 문서 전체 검색 가능 판단 최소 문자 수 */
    private static final int MIN_TOTAL_TEXT_LEN = 30;
    
    /** 하단 여백 임계값 */
    private static final float THRESHOLD_BOTTOM_MARGIN = 0.0f;

    /** 좌표 변환용 DPI */
    private static final float PDF_RENDER_DPI = 300.0f;

    /** PDF 처리용 최대 메모리 (50MB) */
    private static final long PDF_MEMORY_USAGE = 50 * 1024 * 1024L;

    /** 스케일 보정 계수 */
    private static final float SCALE_FACTOR = PDF_RENDER_DPI / 72f;

    /** 공백 제거 정규식 */
    private static final String REGEX_WHITESPACE = "\\s+";

    /**
     * PDF 내부에 텍스트 레이어가 존재하는지 확인합니다.
     */
    public boolean isSearchablePdf(File pdfFile, String originalFileName) {
        int totalTextLength = 0;
        int pagesWithText = 0;

        try (PDDocument document = PDDocument.load(pdfFile, MemoryUsageSetting.setupMixed(PDF_MEMORY_USAGE))) {            
            int numPages = document.getNumberOfPages();
            log.debug("Check text layer: file={}, pages={}", originalFileName, numPages);

            int pagesToCheck = Math.min(numPages, MAX_CHECK_PAGES);
            PDFTextStripper stripper = new PDFTextStripper();

            for (int i = 0; i < pagesToCheck; i++) {
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);

                String pageTextContent = stripper.getText(document);
                
                if (pageTextContent != null) {
                    String cleanText = pageTextContent.replaceAll(REGEX_WHITESPACE, "");
                    int textLength = cleanText.length();

                    if (textLength > MIN_TEXT_LEN_PER_PAGE) {
                        totalTextLength += textLength;
                        pagesWithText++;
                    }
                }
            }

            boolean isSearchable = pagesWithText >= 1 && totalTextLength > MIN_TOTAL_TEXT_LEN;
            
            log.info("Text Layer Detection: exists={}, pages={}, length={} - file={}",
                    isSearchable, pagesWithText, totalTextLength, originalFileName);

            return isSearchable;

        } catch (IOException e) {
            log.warn("Failed to check PDF text layer via PDFBox, File: {}", originalFileName, e);
            return false;
        }
    }

    /**
     * Tabula 엔진을 사용하여 표 데이터를 추출합니다.
     */
    public List<PageTable> extractTables(
            File file, String fileName, boolean isSearchable, Set<Integer> targetPages, Set<Integer> processedPages
    ) {
        if (!isSearchable) {
            log.warn("Skipping Tabula: PDF is not searchable, File: {}", fileName);
            return Collections.emptyList();
        }

        log.info("Starting Tabula extraction for: {}", fileName);

        try {
            List<PageTable> result = processPagesWithTabula(file, targetPages, processedPages);
            
            log.info("Tabula finished: {} pages with tables", result.size());
            return result;
            
        } catch (ProcessingException e) {
            throw e;
        } catch (Exception e) {
            log.error("Tabula extraction failed, File: {}, Error: {}", fileName, e.getMessage());
            throw new ProcessingException("Failed to extract tables using Tabula: " + e.getMessage(), e);
        }
    }

    /**
     * 대상 페이지들을 순회하며 Tabula의 격자 및 스트림 알고리즘을 적용하여 데이터를 수집합니다.
     */
    private List<PageTable> processPagesWithTabula(
            File file, Set<Integer> targetPages, Set<Integer> processedPages
    ) throws Exception {
        List<PageTable> resultData = new ArrayList<>();
        SpreadsheetExtractionAlgorithm latticeAlgorithm = new SpreadsheetExtractionAlgorithm();
        BasicExtractionAlgorithm streamAlgorithm = new BasicExtractionAlgorithm();

        try (
            PDDocument document = PDDocument.load(file, MemoryUsageSetting.setupMixed(PDF_MEMORY_USAGE));
            ObjectExtractor extractor = new ObjectExtractor(document)
        ) {
            Iterator<Page> pages = extractor.extract();
            Table prevPageLastTable = null;

            while (pages.hasNext()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new ProcessingException("Operation interrupted");
                }

                Page page = pages.next();
                int pageNumber = page.getPageNumber();

                if (shouldSkipPage(pageNumber, targetPages, processedPages)) {
                    prevPageLastTable = null; 
                    continue;
                }

                final Table finalPrevTable = prevPageLastTable;
                
                List<Table> tables;
                try {
                    tables = extractTablesFromPage(page, finalPrevTable, latticeAlgorithm, streamAlgorithm);
                } catch (Exception e) {
                    log.warn("Error processing page {} in Tabula: {}", pageNumber, e.getMessage());
                    continue;
                }

                if (tables == null) {
                    tables = Collections.emptyList();
                }

                List<TableData> pageTables = tables.stream()
                        .map(this::mapToTableData)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toCollection(ArrayList::new));

                prevPageLastTable = determinePreviousTable(tables, page);

                if (!pageTables.isEmpty()) {
                    resultData.add(PageTable.builder()
                            .pageNumber(pageNumber)
                            .tables(pageTables)
                            .build());
                }
            }
            
            return resultData;
            
        } catch (Exception e) {
            log.error("Critical error in Tabula core logic: {}", e.getMessage());
            throw e;
        }
    }

    /** 이미 처리되었거나 분석 대상에서 제외된 페이지인지 판별합니다. */
    private boolean shouldSkipPage(int pageNumber, Set<Integer> targetPages, Set<Integer> processedPages) {
        return (!targetPages.isEmpty() && !targetPages.contains(pageNumber)) 
                || processedPages.contains(pageNumber);
    }

    /** 특정 페이지에서 Tabula 알고리즘을 적용하고, 필요한 경우 이전 페이지 표와 연결합니다. */
    private List<Table> extractTablesFromPage(
            Page page, Table prevTable, 
            SpreadsheetExtractionAlgorithm lattice, BasicExtractionAlgorithm stream
    ) {
        List<Table> extracted = lattice.extract(page);
        
        // 이전 페이지 표 연결 로직 
        if (extracted.isEmpty() && prevTable != null) {
            double left = prevTable.getX();
            double width = prevTable.getWidth();
            double top = page.getMinY();
            double height = page.getHeight();

            Page targetedPage = page.getArea(
                    (float) top, 
                    (float) left, 
                    (float) (top + height), 
                    (float) (left + width)
            );

            extracted = lattice.extract(targetedPage);
            
            if (extracted.isEmpty()) {
                extracted = stream.extract(targetedPage);
            }
        }
        
        return extracted;
    }

    /** 페이지 끝부분에서 검출된 표가 다음 페이지로 연장될 가능성이 있는지 판단합니다. */
    private Table determinePreviousTable(List<Table> tables, Page page) {
        if (tables.isEmpty()) {
            return null;
        }
        
        Table lastTable = tables.get(tables.size() - 1);
        
        if (lastTable.getBottom() >= (page.getHeight() - THRESHOLD_BOTTOM_MARGIN)) {
            return lastTable;
        }
        
        return null;
    }

    /** Tabula 라이브러리의 출력 결과(Table)를 시스템 내부 도메인 모델로 변환합니다. */
    private TableData mapToTableData(Table table) {
        List<List<String>> allRows = new ArrayList<>();
        List<List<CellData>> allCellRows = new ArrayList<>();

        for (var row : table.getRows()) {
            List<String> rowData = new ArrayList<>();
            List<CellData> rowCellData = new ArrayList<>();

            for (var cell : row) {
                String text = cell.getText();
                String cleanedText = TextCleaner.clean(text);
                rowData.add(cleanedText);

                CellBox cellBox = CellBox.builder()
                        .x((int) (cell.getX() * SCALE_FACTOR))
                        .y((int) (cell.getY() * SCALE_FACTOR))
                        .width((int) (cell.getWidth() * SCALE_FACTOR))
                        .height((int) (cell.getHeight() * SCALE_FACTOR))
                        .build();

                rowCellData.add(CellData.builder()
                        .text(cleanedText)
                        .box(cellBox)
                        .build());
            }
            
            allRows.add(rowData);
            allCellRows.add(rowCellData);
        }

        if (allRows.isEmpty()) {
            return null;
        }

        return TableData.builder()
                .headers(new ArrayList<>())
                .rows(allRows)
                .cellGrid(allCellRows)
                .build();
    }
}
