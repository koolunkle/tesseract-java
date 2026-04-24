package com.example.ecfs.ocr.domain.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.example.ecfs.ocr.domain.model.document.PageTable;
import com.example.ecfs.ocr.domain.model.document.TableCell;
import com.example.ecfs.ocr.domain.model.analysis.CellBox;

import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Word;

/**
 * 파편화된 셀(TableCell) 데이터를 분석하여 유기적인 표(Grid) 구조로 복원하는 컴포넌트.
 */
@Slf4j
@Component
public class TableReconstructor {

    /** 행 식별을 위한 Y좌표 중첩 비율 임계치. */
    private static final double ROW_OVERLAP_THRESHOLD = 0.5;

    /** 행 묶음을 위한 수직 좌표 허용 오차 (Pixel). */
    private static final int ROW_Y_TOLERANCE = 25;

    /** 추출된 개별 셀 목록을 행/열 구조를 갖춘 테이블 모델로 변환합니다. */
    public PageTable.TableData mapToTableData(List<TableCell> cells) {
        if (cells == null || cells.isEmpty()) {
            return PageTable.TableData.builder()
                    .rows(Collections.emptyList())
                    .cellGrid(Collections.emptyList())
                    .build();
        }

        // 1. Y좌표 기준으로 전체 셀 정렬 (위 -> 아래)
        List<TableCell> sortedCells = new ArrayList<>(cells);
        sortedCells.sort(Comparator.comparingInt(c -> c.getBoundingBox().y));

        // 2. 행(Row) 단위로 그룹화
        List<List<TableCell>> rows = groupCellsByRow(sortedCells);

        // 3. 각 행 내에서 X좌표 기준으로 정렬 (왼쪽 -> 오른쪽) 및 데이터 변환
        List<List<String>> stringGrid = new ArrayList<>();
        List<List<PageTable.CellData>> cellDataGrid = new ArrayList<>();

        for (List<TableCell> rowCells : rows) {
            rowCells.sort(Comparator.comparingInt(c -> c.getBoundingBox().x));

            List<String> rowTexts = rowCells.stream()
                    .map(cell -> cell.assembleText().trim())
                    .collect(Collectors.toList());
            
            List<PageTable.CellData> rowCellData = rowCells.stream()
                    .map(this::mapToCellData)
                    .collect(Collectors.toList());

            stringGrid.add(rowTexts);
            cellDataGrid.add(rowCellData);
        }

        // 4. 구조화된 DTO 반환 
        return PageTable.TableData.builder()
                .headers(new ArrayList<>())
                .rows(stringGrid)
                .cellGrid(cellDataGrid)
                .build();
    }

    /** 도메인 셀 모델을 최종 응답을 위한 데이터 규격으로 변환합니다. */
    private PageTable.CellData mapToCellData(TableCell cell) {
        List<PageTable.TokenData> tokens = new ArrayList<>();
        
        if (cell.getDetectedWords() != null) {
            tokens = cell.getDetectedWords().stream()
                    .filter(w -> w.getText() != null && !w.getText().isBlank())
                    .sorted(Comparator.comparingInt((Word w) -> w.getBoundingBox().y)
                            .thenComparingInt(w -> w.getBoundingBox().x))
                    .map(this::mapToTokenData)
                    .collect(Collectors.toList());
        }

        return PageTable.CellData.builder()
                .text(cell.assembleText().trim())
                .box(mapToCellBox(cell.getBoundingBox().x, cell.getBoundingBox().y, 
                                  cell.getBoundingBox().width, cell.getBoundingBox().height))
                .tokens(tokens)
                .build();
    }

    /** 단어 조각 정보를 좌표를 포함한 토큰 데이터로 변환합니다. */
    private PageTable.TokenData mapToTokenData(Word word) {
        return PageTable.TokenData.builder()
                .text(word.getText())
                .box(mapToCellBox(
                        word.getBoundingBox().x, 
                        word.getBoundingBox().y,
                        word.getBoundingBox().width, 
                        word.getBoundingBox().height
                ))
                .build();
    }

    /** 이미지 상의 기하학적 좌표 정보를 담는 객체를 생성합니다. */
    private CellBox mapToCellBox(int x, int y, int width, int height) {
        return CellBox.builder()
                .x(x)
                .y(y)
                .width(width)
                .height(height)
                .build();
    }

    /** 좌표 중첩도를 계산하여 셀들을 논리적인 행 단위로 그룹화합니다. */
    private List<List<TableCell>> groupCellsByRow(List<TableCell> sortedCells) {
        List<List<TableCell>> rows = new ArrayList<>();
        
        if (sortedCells.isEmpty()) {
            return rows;
        }

        List<TableCell> currentRow = new ArrayList<>();
        currentRow.add(sortedCells.get(0));
        rows.add(currentRow);

        for (int i = 1; i < sortedCells.size(); i++) {
            TableCell cell = sortedCells.get(i);
            TableCell prevCellInRow = currentRow.get(0); // 현재 행의 기준 셀

            if (isSameRow(prevCellInRow, cell)) {
                currentRow.add(cell);
            } else {
                currentRow = new ArrayList<>();
                currentRow.add(cell);
                rows.add(currentRow);
            }
        }
        
        return rows;
    }

    /** 두 셀이 시각적으로 동일한 수직 선상에 위치하는지 판단합니다. */
    private boolean isSameRow(TableCell base, TableCell target) {
        int baseTop = base.getBoundingBox().y;
        int baseBottom = baseTop + base.getBoundingBox().height;
        int targetTop = target.getBoundingBox().y;
        int targetBottom = targetTop + target.getBoundingBox().height;

        // 1. 중심선 비교 
        int baseCenter = baseTop + (base.getBoundingBox().height / 2);
        int targetCenter = targetTop + (target.getBoundingBox().height / 2);

        if (Math.abs(baseCenter - targetCenter) <= ROW_Y_TOLERANCE) {
            return true;
        }

        // 2. 구간 겹침 비교
        int intersectionTop = Math.max(baseTop, targetTop);
        int intersectionBottom = Math.min(baseBottom, targetBottom);
        int overlapHeight = Math.max(0, intersectionBottom - intersectionTop);

        int minHeight = Math.min(base.getBoundingBox().height, target.getBoundingBox().height);

        // 겹치는 구간이 더 작은 셀 높이의 임계값 이상이면 같은 행으로 간주
        return overlapHeight >= (minHeight * ROW_OVERLAP_THRESHOLD);
    }
}
