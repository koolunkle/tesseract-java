package com.example.ecfs.ocr.domain.parser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.example.ecfs.ocr.common.constant.ProcessingConstants;
import com.example.ecfs.ocr.domain.model.analysis.CellBox;
import com.example.ecfs.ocr.domain.model.analysis.ExtractedData;
import com.example.ecfs.ocr.domain.model.analysis.Field;
import com.example.ecfs.ocr.domain.model.document.DebtListRecord;
import com.example.ecfs.ocr.domain.model.document.DefaultRecord;
import com.example.ecfs.ocr.domain.model.document.PageTable;
import com.example.ecfs.ocr.domain.model.document.PageTable.CellData;
import com.example.ecfs.ocr.domain.service.TextCleaner;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * [개인회생채권자목록] 서식을 분석하여 데이터를 추출하는 파서.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DebtListParser implements SectionParser {

    /** 행 순번 패턴 (예: "1", "1-1") */
    private static final Pattern PATTERN_ROW_INDEX = Pattern.compile("^[1-9]\\d*(?:-\\d+)?$");

    /** 금액 패턴 (숫자, 콤마, 소수점, 원 단위) */
    private static final Pattern PATTERN_MONEY = Pattern
            .compile("^[0-9,]+(?:\\.[0-9]+)?(?:" + ProcessingConstants.SUFFIX_WON + ")?$");

    /** 순번+이름 결합 패턴 (하나의 셀에 순번과 이름이 같이 있는 경우) */
    private static final Pattern PATTERN_COMBINED_INDEX_AND_NAME = Pattern.compile("^(\\d+(?:-\\d+)?)[\\s]+(.*)$");

    /** 날짜 패턴 (YYYY-MM-DD 형식 검출) */
    private static final Pattern PATTERN_DATE = Pattern.compile(".*\\d{4}[-.\\s]+\\d{1,2}[-.\\s]+\\d{1,2}.*");

    /** 전화번호 패턴 */
    private static final Pattern PATTERN_PHONE = Pattern.compile("(?:0\\d{1,2})[-)\\s]\\d{3,4}[-\\s]\\d{4}");

    /** 팩스/전화 키워드 포함 여부 확인용 패턴 */
    private static final Pattern PATTERN_FAX = Pattern.compile("(?:팩스|전화)");

    /** 부속서류 키워드 포함 여부 확인용 패턴 */
    private static final Pattern PATTERN_ATTACHED_DOCS = Pattern.compile(".*(?:부속서류).*");

    /** 괄호 내 숫자 목록 검출 패턴 */
    private static final Pattern PATTERN_PAREN_NUM_LIST = Pattern.compile("\\([\\d,\\s]+\\)");

    /** 숫자와 콤마를 제외한 모든 문자 검출 패턴 (금액 데이터 정제용) */
    private static final Pattern PATTERN_NON_NUMERIC_COMMA = Pattern.compile("[^0-9,]");

    /** 숫자, 한글, 영문, 허용된 특수문자를 제외한 모든 문자 검출 패턴 (텍스트 정규화용) */
    private static final Pattern PATTERN_NORMALIZATION_FILTER = Pattern.compile("[^0-9가-힣a-zA-Z.,()\\s-]");

    /** 3자리 이상의 연속된 숫자 및 콤마 검출 패턴 (이름 필드 내 노이즈 필터링용) */
    private static final Pattern PATTERN_CREDITOR_NAME_NUM_FILTER = Pattern.compile("[0-9,]{3,}");

    /** 헤더에서 열 범위를 추출할 때 추가할 좌우 여백 픽셀 */
    private static final int HEADER_BOUNDS_PADDING = 15;

    /** 기존 레코드에서 열 범위를 역추적할 때 추가할 좌우 여백 픽셀 */
    private static final int INFER_BOUNDS_PADDING = 10;

    /** 좌표 중첩도 허용 임계치 (높음) - 데이터 병합 신뢰 기준 */
    private static final double THRESHOLD_OVERLAP_HIGH = 0.3;

    /** 좌표 중첩도 허용 임계치 (낮음) - 최소 겹침 판단 기준 */
    private static final double THRESHOLD_OVERLAP_LOW = 0.2;

    /** 채권 번호 순차 증가 시 허용되는 최대 건너뛰기 범위 */
    private static final int MAX_CREDITOR_NO_JUMP = 50;

    /** 채권자 이름으로 인정될 수 있는 최소 문자 길이 */
    private static final int MIN_CREDITOR_NAME_LEN = 2;

    /** 채권자 이름으로 인정될 수 있는 최대 문자 길이 */
    private static final int MAX_CREDITOR_NAME_LEN = 40;

    /**
     * 열(Column)의 기하학적 범위를 관리하는 불변 데이터 모델.
     * 
     * @param minX 열의 최소 X 좌표
     * @param maxX 열의 최대 X 좌표
     */
    private record DetectedColumnBounds(int minX, int maxX) {

        /**
         * 주어진 셀 좌표(box)가 현재 열 범위와 겹치는 비율(Score)을 계산합니다.
         * 
         * @param box 비교할 셀의 기하학적 정보
         * @return 중첩 비율 (0.0 ~ 1.0)
         */
        double calculateOverlapScore(CellBox box) {
            if (box == null)
                return 0.0;

            int overlapStart = Math.max(minX, box.x());
            int overlapEnd = Math.min(maxX, box.x() + box.width());

            if (overlapEnd <= overlapStart)
                return 0.0;

            double overlapWidth = overlapEnd - overlapStart;
            double denominator = Math.min(maxX - minX, box.width());

            return (denominator == 0) ? 0.0 : overlapWidth / denominator;
        }
    }

    /**
     * 채권자 목록 페이지들을 통합 분석합니다.
     * 
     * @param pages 추출된 원본 페이지 표 데이터 리스트
     * @return 파싱이 완료된 추출 데이터 리스트
     */
    @Override
    public List<ExtractedData> parse(List<PageTable> pages) {
        if (pages == null) {
            throw new IllegalArgumentException("Input pages cannot be null.");
        }

        List<ExtractedData> resultData = new ArrayList<>();
        DebtListRecord currentRecord = null;
        Map<String, DetectedColumnBounds> activeColumnRanges = new HashMap<>();
        int lastIndexNumber = 0;

        for (int i = 0; i < pages.size(); i++) {
            PageTable page = pages.get(i);
            if (page.tables() == null)
                continue;

            for (PageTable.TableData table : page.tables()) {
                List<List<CellData>> grid = normalizeTableGrid(table);
                if (grid.isEmpty())
                    continue;

                if (isSummaryTable(grid)) {
                    processSummaryTable(grid, page, resultData);
                    continue;
                }

                for (List<CellData> row : grid) {
                    List<CellData> validCells = getValidCells(row);
                    if (validCells.isEmpty())
                        continue;

                    List<String> validTokens = getValidTokens(validCells);

                    if (isHeaderRow(validTokens)) {
                        Map<String, DetectedColumnBounds> newRanges = detectColumnBoundsFromHeader(validCells);
                        if (!newRanges.isEmpty()) {
                            activeColumnRanges = newRanges;
                            log.info("[Page {}] Header Ranges Updated: {}", page.pageNumber(),
                                    activeColumnRanges.keySet());
                        }
                        continue;
                    }

                    String firstText = normalizeText(validCells.get(0).text());
                    boolean isPotentialNewEntry = PATTERN_ROW_INDEX.matcher(firstText).matches()
                            || PATTERN_COMBINED_INDEX_AND_NAME.matcher(firstText).matches();

                    if (isStartOfNewCreditorEntry(validCells, activeColumnRanges, lastIndexNumber)) {
                        currentRecord = createEmptyRecord(page.pageNumber());
                        processRowData(validCells, currentRecord, activeColumnRanges, page.pageNumber(), true);
                        lastIndexNumber = updateLastIndexNumber(currentRecord, lastIndexNumber);
                        resultData.add(currentRecord);
                    } else if (currentRecord != null && !isPotentialNewEntry) {
                        processRowData(validCells, currentRecord, activeColumnRanges, page.pageNumber(), false);
                    }
                }
            }
        }

        sanitizeAndFormatResults(resultData);
        return resultData;
    }

    /**
     * 합계(요약) 표 데이터를 처리하여 결과 목록에 추가합니다.
     * 
     * @param grid       분석할 표의 그리드 데이터
     * @param page       현재 페이지 정보
     * @param resultData 결과를 담을 리스트
     */
    private void processSummaryTable(List<List<CellData>> grid, PageTable page, List<ExtractedData> resultData) {
        ExtractedData sumData = parseHorizontalKeyValue(grid);
        if (!sumData.toMap().isEmpty()) {
            if (sumData instanceof DefaultRecord dr) {
                dr.put(ProcessingConstants.KEY_PAGE, String.valueOf(page.pageNumber()));
            }
            resultData.add(sumData);
        }
    }

    /**
     * 하나의 행(Row)에 대한 데이터 추출 및 병합 로직을 분기하여 실행합니다.
     * 
     * @param validCells         유효한 셀 데이터 리스트
     * @param currentRecord      데이터를 담을 채권자 레코드 객체
     * @param activeColumnRanges 현재 유효한 열 범위 맵
     * @param pageNum            현재 페이지 번호
     * @param isNewEntry         새로운 레코드의 시작 행인지 여부
     */
    private void processRowData(List<CellData> validCells, DebtListRecord currentRecord,
            Map<String, DetectedColumnBounds> activeColumnRanges, int pageNum, boolean isNewEntry) {
        boolean hasCoords = hasCoordinateInfo(validCells);

        if (isNewEntry) {
            if (!activeColumnRanges.isEmpty() && hasCoords) {
                parseRowByCoordinates(validCells, currentRecord, activeColumnRanges, pageNum);
            } else {
                parseRowBySimpleOrder(validCells, currentRecord, pageNum);
            }
        } else {
            if (!activeColumnRanges.isEmpty() && hasCoords) {
                mergeRowByCoordinates(validCells, currentRecord, activeColumnRanges, pageNum);
            } else if (hasCoords) {
                Map<String, DetectedColumnBounds> inferredRanges = deriveColumnBoundsFromRecord(currentRecord);
                if (!inferredRanges.isEmpty()) {
                    mergeRowByCoordinates(validCells, currentRecord, inferredRanges, pageNum);
                }
            } else {
                mergeRowBySimpleOrder(validCells, currentRecord, pageNum);
            }
        }
    }

    /** 신규 레코드 객체를 생성하고 내부 필드를 초기화합니다. */
    private DebtListRecord createEmptyRecord(int pageNum) {
        DebtListRecord record = new DebtListRecord();
        record.setPage(String.valueOf(pageNum));
        record.setCreditorNo(Field.builder().value("").page(pageNum).build());
        record.setCreditorName(Field.builder().value("").page(pageNum).build());
        record.setPrincipal(Field.builder().value("").page(pageNum).build());
        record.setInterest(Field.builder().value("").page(pageNum).build());
        return record;
    }

    /** 유효한 데이터가 포함된 셀만 선별합니다. */
    private List<CellData> getValidCells(List<CellData> row) {
        if (row == null)
            return List.of();
        return row.stream()
                .filter(c -> !normalizeText(c.text()).isEmpty())
                .collect(Collectors.toList());
    }

    /** 셀 리스트를 텍스트 리스트로 변환합니다. */
    private List<String> getValidTokens(List<CellData> validCells) {
        return validCells.stream()
                .map(c -> normalizeText(c.text()))
                .collect(Collectors.toList());
    }

    /** 현재 처리 중인 레코드의 순번 정보를 갱신합니다. */
    private int updateLastIndexNumber(DebtListRecord record, int lastIndexNumber) {
        String noStr = record.getCreditorNo() != null ? record.getCreditorNo().getValue() : "";
        if (noStr != null && !noStr.isEmpty()) {
            try {
                String mainNo = PATTERN_NON_NUMERIC_COMMA.matcher(noStr.split(ProcessingConstants.DELIMITER_DASH)[0])
                        .replaceAll("");
                if (!mainNo.isEmpty()) {
                    int no = Integer.parseInt(mainNo);
                    if (no > 0 && no < lastIndexNumber + MAX_CREDITOR_NO_JUMP) {
                        return no;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return lastIndexNumber;
    }

    /** 헤더 텍스트와 좌표를 분석하여 각 열의 유효 범위를 설정합니다. */
    private Map<String, DetectedColumnBounds> detectColumnBoundsFromHeader(List<CellData> row) {
        Map<String, DetectedColumnBounds> ranges = new HashMap<>();

        for (CellData cell : row) {
            String text = normalizeText(cell.text());
            CellBox cellBox = cell.box();
            if (cellBox == null)
                continue;

            DetectedColumnBounds range = new DetectedColumnBounds(
                    cellBox.x() - HEADER_BOUNDS_PADDING,
                    cellBox.x() + cellBox.width() + HEADER_BOUNDS_PADDING);

            if (text.contains(ProcessingConstants.KEY_CREDITOR_NO))
                ranges.put(ProcessingConstants.KEY_CREDITOR_NO, range);
            else if (text.contains(ProcessingConstants.KEY_CREDITOR_NAME))
                ranges.put(ProcessingConstants.KEY_CREDITOR_NAME, range);
            else if (text.contains(ProcessingConstants.KEYWORD_PRINCIPAL))
                ranges.put(ProcessingConstants.KEY_PRINCIPAL, range);
            else if (text.contains(ProcessingConstants.KEYWORD_INTEREST))
                ranges.put(ProcessingConstants.KEY_INTEREST, range);
        }
        return ranges;
    }

    /** 기존 레코드의 좌표 정보를 역추적하여 열 범위를 유추합니다. */
    private Map<String, DetectedColumnBounds> deriveColumnBoundsFromRecord(DebtListRecord record) {
        Map<String, DetectedColumnBounds> ranges = new HashMap<>();
        inferRange(record.getCreditorName(), ProcessingConstants.KEY_CREDITOR_NAME, ranges);
        inferRange(record.getPrincipal(), ProcessingConstants.KEY_PRINCIPAL, ranges);
        inferRange(record.getInterest(), ProcessingConstants.KEY_INTEREST, ranges);
        return ranges;
    }

    /** 특정 필드의 좌표 데이터를 기반으로 열 경계(DetectedColumnBounds)를 생성합니다. */
    private void inferRange(Field field, String key, Map<String, DetectedColumnBounds> ranges) {
        if (field == null || field.getLocations().isEmpty())
            return;

        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        boolean found = false;

        for (CellBox box : field.getLocations()) {
            minX = Math.min(minX, box.x());
            maxX = Math.max(maxX, box.x() + box.width());
            found = true;
        }

        if (found) {
            ranges.put(key, new DetectedColumnBounds(minX - INFER_BOUNDS_PADDING, maxX + INFER_BOUNDS_PADDING));
        }
    }

    /** 좌표 정보를 활용하여 행의 각 데이터를 적절한 필드에 배정합니다. */
    private void parseRowByCoordinates(List<CellData> cells, DebtListRecord record,
            Map<String, DetectedColumnBounds> ranges, int pageNum) {

        CellData firstCell = cells.get(0);
        String firstText = normalizeText(firstCell.text());
        Matcher splitMatcher = PATTERN_COMBINED_INDEX_AND_NAME.matcher(firstText);

        if (splitMatcher.matches()) {
            updateField(record, ProcessingConstants.KEY_CREDITOR_NO, splitMatcher.group(1), firstCell, pageNum);
            updateField(record, ProcessingConstants.KEY_CREDITOR_NAME, splitMatcher.group(2), firstCell, pageNum);
        } else {
            DetectedColumnBounds noRange = ranges.get(ProcessingConstants.KEY_CREDITOR_NO);
            boolean matchNo = (noRange != null
                    && noRange.calculateOverlapScore(firstCell.box()) > THRESHOLD_OVERLAP_HIGH)
                    || PATTERN_ROW_INDEX.matcher(firstText).matches();

            if (matchNo) {
                updateField(record, ProcessingConstants.KEY_CREDITOR_NO, firstText, firstCell, pageNum);
            }
        }

        String currentNo = record.getCreditorNo() != null ? record.getCreditorNo().getValue() : "";
        boolean hasCombinedSplit = splitMatcher.matches();

        for (CellData cell : cells) {
            if (cell == firstCell) {
                if (hasCombinedSplit)
                    continue;
                if (!currentNo.isEmpty() && normalizeText(cell.text()).equals(currentNo))
                    continue;
            }
            distributeCellTextToField(cell, record, ranges, pageNum);
        }
    }

    /** 행 내의 셀들을 순회하며 이미 정의된 열 범위에 맞춰 데이터를 병합합니다. */
    private void mergeRowByCoordinates(List<CellData> cells, DebtListRecord record,
            Map<String, DetectedColumnBounds> ranges, int pageNum) {
        String currentNo = record.getCreditorNo() != null ? record.getCreditorNo().getValue() : "";

        for (CellData cell : cells) {
            String text = normalizeText(cell.text());
            if (!currentNo.isEmpty() && text.equals(currentNo)) {
                updateField(record, ProcessingConstants.KEY_CREDITOR_NO, text, cell, pageNum);
                continue;
            }
            distributeCellTextToField(cell, record, ranges, pageNum);
        }
    }

    /** 셀 텍스트를 토큰화하고 형식 및 좌표를 분석하여 필드를 분배합니다. */
    private void distributeCellTextToField(CellData cell, DebtListRecord record,
            Map<String, DetectedColumnBounds> ranges, int pageNum) {
        String text = normalizeText(cell.text());
        if (shouldSkipCell(text))
            return;

        String[] rawTokens = text.split("\\s+");
        if (rawTokens.length > 1) {
            for (String t : rawTokens)
                classifyAndAssignField(t, cell, record, ranges, pageNum);
        } else {
            classifyAndAssignField(text, cell, record, ranges, pageNum);
        }
    }

    /** 개별 토큰의 문자열 형식과 기하학적 위치를 평가하여 레코드 필드에 할당합니다. */
    private void classifyAndAssignField(String text, CellData cell, DebtListRecord record,
            Map<String, DetectedColumnBounds> bounds, int pageNum) {

        if (findGarbage(text))
            return;

        CellBox cellBox = cell.box();
        String bestMatchKey = findBestMatchingColumn(cellBox, bounds);

        boolean isMoneyPattern = isStrictMoney(text);
        if (isMoneyPattern) {
            String cellFullText = normalizeText(cell.text());
            if (!isPureMoneyCell(cellFullText))
                isMoneyPattern = false;
        }

        boolean isAssigned = false;

        if (isMoneyPattern) {
            isAssigned = assignMoneyField(text, cellBox, record, bestMatchKey, pageNum);
        }

        if (!isAssigned && !isMoneyPattern) {
            if (ProcessingConstants.KEY_CREDITOR_NAME.equals(bestMatchKey) && isValidCreditorName(text)) {
                updateField(record, ProcessingConstants.KEY_CREDITOR_NAME, text, cellBox, pageNum);
                isAssigned = true;
            }
        }

        if (!isAssigned && isMoneyPattern) {
            if (isFieldEmpty(record.getPrincipal())) {
                updateField(record, ProcessingConstants.KEY_PRINCIPAL, text, cellBox, pageNum);
            } else if (isFieldEmpty(record.getInterest())) {
                updateField(record, ProcessingConstants.KEY_INTEREST, text, cellBox, pageNum);
            }
        }
    }

    /**
     * 주어진 셀 좌표와 가장 겹침이 많은 열(Column) 키를 찾습니다.
     * 
     * @param cellBox 대상 셀 좌표
     * @param bounds  비교할 열 범위 맵
     * @return 가장 일치하는 열의 키, 없으면 null
     */
    private String findBestMatchingColumn(CellBox cellBox, Map<String, DetectedColumnBounds> bounds) {
        if (cellBox == null || bounds.isEmpty())
            return null;

        String bestMatchKey = null;
        double maxScore = 0.0;
        String[] candidates = { ProcessingConstants.KEY_CREDITOR_NAME, ProcessingConstants.KEY_PRINCIPAL,
                ProcessingConstants.KEY_INTEREST };

        for (String key : candidates) {
            DetectedColumnBounds bound = bounds.get(key);
            if (bound != null) {
                double score = bound.calculateOverlapScore(cellBox);
                if (score > THRESHOLD_OVERLAP_HIGH && score > maxScore) {
                    maxScore = score;
                    bestMatchKey = key;
                }
            }
        }
        return bestMatchKey;
    }

    /**
     * 금액 데이터를 원금 또는 이자 필드에 논리적으로 할당합니다.
     * 
     * @param text         할당할 텍스트
     * @param cellBox      대상 셀 좌표
     * @param record       대상 레코드
     * @param bestMatchKey 좌표상 예측된 키
     * @param pageNum      페이지 번호
     * @return 할당 성공 여부
     */
    private boolean assignMoneyField(String text, CellBox cellBox, DebtListRecord record, String bestMatchKey,
            int pageNum) {
        String targetKey = null;

        if (ProcessingConstants.KEY_PRINCIPAL.equals(bestMatchKey)
                || ProcessingConstants.KEY_INTEREST.equals(bestMatchKey)) {
            targetKey = bestMatchKey;
        } else {
            if (isFieldEmpty(record.getPrincipal()))
                targetKey = ProcessingConstants.KEY_PRINCIPAL;
            else if (isFieldEmpty(record.getInterest()))
                targetKey = ProcessingConstants.KEY_INTEREST;
        }

        if (targetKey != null) {
            String existing = (ProcessingConstants.KEY_PRINCIPAL.equals(targetKey))
                    ? (record.getPrincipal() != null ? record.getPrincipal().getValue() : "")
                    : (record.getInterest() != null ? record.getInterest().getValue() : "");

            if (!existing.isEmpty()) {
                String exClean = PATTERN_NON_NUMERIC_COMMA.matcher(existing).replaceAll("");
                String newClean = PATTERN_NON_NUMERIC_COMMA.matcher(text).replaceAll("");

                if (!(exClean.endsWith(",") || newClean.startsWith(","))) {
                    if (ProcessingConstants.KEY_PRINCIPAL.equals(targetKey) && isFieldEmpty(record.getInterest())) {
                        targetKey = ProcessingConstants.KEY_INTEREST;
                    }
                }
            }
            updateField(record, targetKey, text, cellBox, pageNum);
            return true;
        }
        return false;
    }

    /** 필드가 null이거나 비어있는지 확인합니다. */
    private boolean isFieldEmpty(Field field) {
        return field == null || field.getValue() == null || field.getValue().isEmpty();
    }

    /** 텍스트가 채권자 이름으로서의 유효한 특성을 갖추었는지 판단합니다. */
    private boolean isValidCreditorName(String text) {
        if (text.length() < MIN_CREDITOR_NAME_LEN)
            return false;
        String cleaned = PATTERN_CREDITOR_NAME_NUM_FILTER.matcher(text).replaceAll("").trim();
        return !cleaned.isEmpty() && cleaned.length() < MAX_CREDITOR_NAME_LEN && !findGarbage(cleaned)
                && cleaned.matches(".*[가-힣].*");
    }

    /** 좌표 정보가 없는 경우 열 순서에 따라 레코드 데이터를 분석합니다. */
    private void parseRowBySimpleOrder(List<CellData> cells, DebtListRecord record, int pageNum) {
        List<String> tokens = cells.stream().map(c -> normalizeText(c.text())).collect(Collectors.toList());
        if (tokens.isEmpty())
            return;

        int idx = 0;
        String first = tokens.get(0);
        Matcher splitMatcher = PATTERN_COMBINED_INDEX_AND_NAME.matcher(first);

        if (splitMatcher.matches()) {
            updateField(record, ProcessingConstants.KEY_CREDITOR_NO, splitMatcher.group(1), cells.get(0), pageNum);
            updateField(record, ProcessingConstants.KEY_CREDITOR_NAME, splitMatcher.group(2), cells.get(0), pageNum);
            idx = 1;
        } else if (PATTERN_ROW_INDEX.matcher(first).matches()) {
            updateField(record, ProcessingConstants.KEY_CREDITOR_NO, first, cells.get(0), pageNum);
            idx = 1;
        } else {
            return;
        }

        if (isFieldEmpty(record.getCreditorName()) && idx < tokens.size()) {
            String candidateName = tokens.get(idx);
            if (!shouldSkipCell(candidateName) && !isStrictMoney(candidateName)) {
                updateField(record, ProcessingConstants.KEY_CREDITOR_NAME, candidateName, cells.get(idx), pageNum);
                idx++;
            }
        }

        for (int i = idx; i < tokens.size(); i++) {
            String val = tokens.get(i);
            if (isStrictMoney(val)) {
                String targetKey = isFieldEmpty(record.getPrincipal()) ? ProcessingConstants.KEY_PRINCIPAL
                        : ProcessingConstants.KEY_INTEREST;
                updateField(record, targetKey, val, cells.get(i), pageNum);
            }
        }
    }

    /** 연속된 데이터 행을 기저 레코드에 순서대로 병합합니다. */
    private void mergeRowBySimpleOrder(List<CellData> cells, DebtListRecord record, int pageNum) {
        List<String> tokens = cells.stream().map(c -> normalizeText(c.text())).collect(Collectors.toList());

        boolean principalUpdatedInRow = false;
        boolean recordHasPrincipal = !isFieldEmpty(record.getPrincipal());
        String currentNo = record.getCreditorNo() != null ? record.getCreditorNo().getValue() : "";

        for (int i = 0; i < tokens.size(); i++) {
            String val = tokens.get(i);
            CellData cell = cells.get(i);

            if (shouldSkipCell(val))
                continue;

            if (i == 0 && !currentNo.isEmpty() && val.equals(currentNo)) {
                updateField(record, ProcessingConstants.KEY_CREDITOR_NO, val, cell, pageNum);
                continue;
            }

            if (isStrictMoney(val)) {
                principalUpdatedInRow = handleMoneyInSimpleOrder(val, cell, record, pageNum, principalUpdatedInRow,
                        recordHasPrincipal);
            } else if (isValidCreditorName(val)) {
                updateField(record, ProcessingConstants.KEY_CREDITOR_NAME, val, cell, pageNum);
            }
        }
    }

    /**
     * 단순 순서 기반 병합 시 금액 데이터의 원금/이자 분배를 처리합니다.
     * 
     * @return 원금이 업데이트되었는지 여부 (principalUpdatedInRow)
     */
    private boolean handleMoneyInSimpleOrder(String val, CellData cell, DebtListRecord record, int pageNum,
            boolean principalUpdated, boolean hasPrincipal) {
        if (isFieldEmpty(record.getPrincipal())) {
            updateField(record, ProcessingConstants.KEY_PRINCIPAL, val, cell, pageNum);
            return true;
        }

        if (isFieldEmpty(record.getInterest())) {
            String currentP = record.getPrincipal().getValue();
            String pCheck = PATTERN_NON_NUMERIC_COMMA.matcher(currentP).replaceAll("");
            String vCheck = PATTERN_NON_NUMERIC_COMMA.matcher(val).replaceAll("");
            boolean looksLikeSplit = pCheck.endsWith(",") || vCheck.startsWith(",");

            if (hasPrincipal && !looksLikeSplit && !principalUpdated) {
                updateField(record, ProcessingConstants.KEY_INTEREST, val, cell, pageNum);
                return principalUpdated;
            } else {
                if (looksLikeSplit || principalUpdated) {
                    updateField(record, ProcessingConstants.KEY_PRINCIPAL, val, cell, pageNum);
                    return true;
                } else {
                    updateField(record, ProcessingConstants.KEY_INTEREST, val, cell, pageNum);
                    return principalUpdated;
                }
            }
        }

        if (!principalUpdated) {
            updateField(record, ProcessingConstants.KEY_PRINCIPAL, val, cell, pageNum);
            return true;
        } else {
            updateField(record, ProcessingConstants.KEY_INTEREST, val, cell, pageNum);
            return principalUpdated;
        }
    }

    /** 레코드의 특정 필드를 업데이트하고 발생 위치 정보를 누적합니다. */
    private void updateField(DebtListRecord record, String key, String text, CellBox targetBox, int pageNum) {
        Field field = switch (key) {
            case ProcessingConstants.KEY_CREDITOR_NO -> record.getCreditorNo();
            case ProcessingConstants.KEY_CREDITOR_NAME -> record.getCreditorName();
            case ProcessingConstants.KEY_PRINCIPAL -> record.getPrincipal();
            case ProcessingConstants.KEY_INTEREST -> record.getInterest();
            default -> (Field) record.getAdditionalFields().get(key);
        };

        if (field != null) {
            field.appendValue(text);
            if (targetBox != null)
                field.addLocation(createPageBox(targetBox, pageNum));
        } else {
            Field newField = Field.builder().value(text).page(pageNum).build();
            if (targetBox != null)
                newField.addLocation(createPageBox(targetBox, pageNum));
            record.put(key, newField);
        }
    }

    /** 원시 좌표 정보에 페이지 정보를 결합하여 신규 좌표 객체를 생성합니다. */
    private CellBox createPageBox(CellBox original, int pageNum) {
        return CellBox.builder().x(original.x()).y(original.y()).width(original.width()).height(original.height())
                .page(pageNum).build();
    }

    /** 셀 데이터를 기반으로 레코드 필드를 업데이트합니다. */
    private void updateField(DebtListRecord record, String key, String text, CellData cell, int pageNum) {
        updateField(record, key, text, (cell != null ? cell.box() : null), pageNum);
    }

    /** 추출된 채권자 데이터의 결측치를 보정하고 최종 비즈니스 형식을 적용합니다. */
    private void sanitizeAndFormatResults(List<ExtractedData> resultData) {
        for (ExtractedData data : resultData) {
            if (data instanceof DebtListRecord record
                    && !record.getAdditionalFields().containsKey(ProcessingConstants.KEY_SPECIAL_RIGHT_CONTENT)) {
                if (record.getCreditorNo() != null || record.getCreditorName() != null) {
                    if (isFieldEmpty(record.getPrincipal()))
                        updateField(record, ProcessingConstants.KEY_PRINCIPAL, ProcessingConstants.VALUE_ZERO,
                                (CellBox) null, 0);
                    if (isFieldEmpty(record.getInterest()))
                        updateField(record, ProcessingConstants.KEY_INTEREST, ProcessingConstants.VALUE_ZERO,
                                (CellBox) null, 0);

                    if (!isFieldEmpty(record.getCreditorName())) {
                        Field old = record.getCreditorName();
                        record.setCreditorName(Field.builder().value(TextCleaner.clean(old.getValue()))
                                .page(old.getPage()).locations(old.getLocations()).build());
                    }
                }
            }
        }
    }

    /** 분석 대상이 아닌 노이즈성 셀인지 판별합니다. */
    private boolean shouldSkipCell(String text) {
        if (text == null || text.isBlank())
            return true;
        if (PATTERN_DATE.matcher(text).matches() || findGarbage(text))
            return true;
        return PATTERN_DATE.matcher(text).find() && PATTERN_MONEY.matcher(text.replace(" ", "")).find();
    }

    /** 불필요한 시스템 메타데이터나 잡티 성격의 텍스트를 식별합니다. */
    private boolean findGarbage(String text) {
        return PATTERN_PHONE.matcher(text).find() || PATTERN_FAX.matcher(text).find() ||
                PATTERN_PAREN_NUM_LIST.matcher(text).find() || PATTERN_ATTACHED_DOCS.matcher(text).find();
    }

    /** 텍스트가 엄격한 금액 형식(콤마 포함 숫자 등)을 따르는지 확인합니다. */
    private boolean isStrictMoney(String text) {
        return PATTERN_MONEY.matcher(text.replace(" ", "")).matches();
    }

    /** 셀 전체 내용이 순수하게 금액 정보로만 구성되었는지 확인합니다. */
    private boolean isPureMoneyCell(String text) {
        if (text == null)
            return false;
        return text.replaceAll("[0-9, .]", "").replaceAll(ProcessingConstants.SUFFIX_WON, "").trim().isEmpty();
    }

    /** 행 내에 유효한 좌표 정보가 포함되어 있는지 확인합니다. */
    private boolean hasCoordinateInfo(List<CellData> row) {
        return row.stream().anyMatch(c -> c.box() != null);
    }

    /** 원시 표 데이터를 정형화된 그리드 리스트로 평탄화합니다. */
    private List<List<CellData>> normalizeTableGrid(PageTable.TableData table) {
        List<List<CellData>> grid = new ArrayList<>();
        if (table.headers() != null && !table.headers().isEmpty()) {
            grid.add(table.headers().stream().map(text -> CellData.builder().text(text).build())
                    .collect(Collectors.toList()));
        }
        if (table.cellGrid() != null && !table.cellGrid().isEmpty()) {
            grid.addAll(table.cellGrid());
        } else if (table.rows() != null) {
            for (List<String> strRow : table.rows()) {
                grid.add(strRow.stream().map(text -> CellData.builder().text(text).build())
                        .collect(Collectors.toList()));
            }
        }
        return grid;
    }

    /** 현재 표가 전체 합계 정보를 담고 있는 요약표인지 판별합니다. */
    private boolean isSummaryTable(List<List<CellData>> grid) {
        String text = grid.stream().limit(2).flatMap(List::stream).map(c -> normalizeText(c.text()))
                .collect(Collectors.joining(" "));
        return text.contains(ProcessingConstants.KEYWORD_TOTAL)
                && !text.contains(ProcessingConstants.KEY_CREDITOR_NAME);
    }

    /** 행이 데이터 분류 기준인 헤더 행인지 판별합니다. */
    private boolean isHeaderRow(List<String> tokens) {
        String joined = String.join(" ", tokens);
        return joined.contains(ProcessingConstants.KEY_CREDITOR_NAME) &&
                (joined.contains(ProcessingConstants.KEYWORD_PRINCIPAL)
                        || joined.contains(ProcessingConstants.KEYWORD_TOTAL));
    }

    /** 현재 행이 새로운 채권자 레코드의 시작점인지 기하학적/텍스트 분석을 통해 판별합니다. */
    private boolean isStartOfNewCreditorEntry(List<CellData> row, Map<String, DetectedColumnBounds> ranges,
            int lastBondNo) {
        if (row.isEmpty())
            return false;

        CellData firstCell = row.get(0);
        String firstText = normalizeText(firstCell.text());

        if (PATTERN_COMBINED_INDEX_AND_NAME.matcher(firstText).matches())
            return true;

        if (PATTERN_ROW_INDEX.matcher(firstText).matches() && !firstText.equals(ProcessingConstants.VALUE_ZERO)) {
            if (ranges != null && !ranges.isEmpty() && firstCell.box() != null) {
                DetectedColumnBounds pRange = ranges.get(ProcessingConstants.KEY_PRINCIPAL);
                DetectedColumnBounds iRange = ranges.get(ProcessingConstants.KEY_INTEREST);

                if (pRange != null && pRange.calculateOverlapScore(firstCell.box()) > THRESHOLD_OVERLAP_HIGH)
                    return false;
                if (iRange != null && iRange.calculateOverlapScore(firstCell.box()) > THRESHOLD_OVERLAP_HIGH)
                    return false;

                DetectedColumnBounds noRange = ranges.get(ProcessingConstants.KEY_CREDITOR_NO);
                if (noRange != null && noRange.calculateOverlapScore(firstCell.box()) < THRESHOLD_OVERLAP_LOW)
                    return false;
            }
            return true;
        }
        return false;
    }

    /** 가로 방향으로 나열된 Key-Value 형태의 데이터를 파싱합니다. */
    private ExtractedData parseHorizontalKeyValue(List<List<CellData>> grid) {
        DefaultRecord record = new DefaultRecord();
        for (List<CellData> row : grid) {
            for (int i = 0; i < row.size() - 1; i += 2) {
                String key = normalizeText(row.get(i).text());
                String val = normalizeText(row.get(i + 1).text());
                if (!key.isBlank())
                    record.put(key, val);
            }
        }
        return record;
    }

    /** 텍스트 정규화 및 클리닝을 통합 수행합니다. */
    private String normalizeText(String text) {
        if (text == null)
            return "";
        return TextCleaner.clean(PATTERN_NORMALIZATION_FILTER.matcher(text).replaceAll(""));
    }
}
