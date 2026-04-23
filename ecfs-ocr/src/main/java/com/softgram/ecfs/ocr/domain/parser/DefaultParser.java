package com.softgram.ecfs.ocr.domain.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.softgram.ecfs.ocr.domain.service.TextCleaner;
import com.softgram.ecfs.ocr.domain.model.analysis.ExtractedData;
import com.softgram.ecfs.ocr.domain.model.document.DefaultRecord;
import com.softgram.ecfs.ocr.domain.model.document.PageTable;

/**
 * 정의되지 않은 구역에 대한 기본 추출 파서.
 */
@Component
public class DefaultParser implements SectionParser {

    /**
     * 표 구조에 따라 적절한 방식으로 데이터를 추출합니다.
     */
    @Override
    public List<ExtractedData> parse(List<PageTable> pages) {
        if (pages == null) {
            throw new IllegalArgumentException("Input pages cannot be null for DefaultParser.");
        }
        
        List<ExtractedData> resultData = new ArrayList<>();

        for (PageTable page : pages) {
            if (page.tables() == null) {
                continue;
            }

            for (PageTable.TableData table : page.tables()) {
                List<List<String>> fullGrid = combineToGrid(table);

                if (fullGrid.isEmpty()) {
                    continue;
                }

                int maxCols = fullGrid.stream().mapToInt(List::size).max().orElse(0);

                if (maxCols == 2) {
                    parseAsKeyValue(fullGrid, resultData);
                } else {
                    parseAsGrid(fullGrid, resultData);
                }
            }
        }
        
        return resultData;
    }

    /**
     * 데이터를 하나의 그리드로 결합합니다.
     */
    private List<List<String>> combineToGrid(PageTable.TableData table) {
        return Stream.concat(
                Stream.ofNullable(table.headers()),
                table.rows() != null ? table.rows().stream() : Stream.empty()
        ).collect(Collectors.toList());
    }

    /**
     * 2열 표를 Key-Value 형식으로 추출합니다.
     */
    private void parseAsKeyValue(List<List<String>> grid, List<ExtractedData> resultData) {
        DefaultRecord record = new DefaultRecord();
        
        for (List<String> row : grid) {
            if (row.size() >= 2) {
                String key = cleanText(row.get(0));
                String val = cleanText(row.get(1));
                
                if (!key.isBlank()) {
                    record.put(key, val);
                }
            }
        }
        
        if (!record.toMap().isEmpty()) {
            resultData.add(record);
        }
    }

    /**
     * 3열 이상 표를 그리드 형식으로 추출합니다.
     */
    private void parseAsGrid(List<List<String>> grid, List<ExtractedData> resultData) {
        if (grid.size() < 2) {
            return;
        }

        List<String> headers = grid.get(0);

        for (int i = 1; i < grid.size(); i++) {
            List<String> row = grid.get(i);
            DefaultRecord rowRecord = new DefaultRecord();

            for (int colIdx = 0; colIdx < headers.size(); colIdx++) {
                String headerKey = cleanText(headers.get(colIdx));
                if (headerKey.isBlank()) {
                    continue;
                }

                String val = (colIdx < row.size()) ? cleanText(row.get(colIdx)) : "";
                if (!val.isBlank()) {
                    rowRecord.put(headerKey, val);
                }
            }

            if (!rowRecord.toMap().isEmpty()) {
                resultData.add(rowRecord);
            }
            }
            }
    /**
     * 텍스트 노이즈를 제거합니다.
     */
    private String cleanText(String text) {
        return TextCleaner.clean(text);
    }
}
