package com.softgram.ecfs.ocr.domain.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.softgram.ecfs.ocr.domain.model.analysis.ExtractedData;
import com.softgram.ecfs.ocr.domain.model.document.DefaultRecord;
import com.softgram.ecfs.ocr.domain.model.document.PageTable;
import com.softgram.ecfs.ocr.domain.service.TextCleaner;

import lombok.RequiredArgsConstructor;

/**
 * [채권자계좌번호신고서] 서식을 분석하여 계좌 정보를 추출하는 파서.
 */
@Component
@RequiredArgsConstructor
public class AccountParser implements SectionParser {

    /**
     * 계좌 신고서 페이지들을 분석합니다.
     */
    @Override
    public List<ExtractedData> parse(List<PageTable> pages) {
        if (pages == null) {
            throw new IllegalArgumentException("Input pages cannot be null for AccountParser.");
        }

        List<ExtractedData> resultData = new ArrayList<>();

        for (PageTable page : pages) {
            if (page.tables() == null) {
                continue;
            }

            for (PageTable.TableData table : page.tables()) {
                List<List<String>> grid = combineToGrid(table);
                if (grid.isEmpty()) {
                    continue;
                }

                ExtractedData accountInfo = parseAccountTable(grid);
                if (!accountInfo.toMap().isEmpty()) {
                    resultData.add(accountInfo);
                }
            }
        }

        return resultData;
    }

    /**
     * 채권자 기본 정보를 Key-Value 형식으로 분석합니다.
     */
    private ExtractedData parseAccountTable(List<List<String>> grid) {
        DefaultRecord record = new DefaultRecord();

        for (List<String> row : grid) {
            if (row.isEmpty()) {
                continue;
            }

            String firstKey = cleanText(row.get(0));

            if (firstKey.contains("전화")) {
                parsePhoneNumbers(firstKey, row, record);
            } else {
                for (int i = 0; i < row.size() - 1; i += 2) {
                    String key = cleanText(row.get(i));
                    String val = cleanText(row.get(i + 1));

                    if (!key.isBlank()) {
                        record.put(key, val);
                    }
                }
            }
        }

        return record;
    }

    /**
     * 연락처 정보를 파싱하고 분류합니다.
     */
    private void parsePhoneNumbers(String key, List<String> row, DefaultRecord record) {
        for (int i = 1; i < row.size(); i++) {
            String rawVal = cleanText(row.get(i));
            if (rawVal.isBlank()) {
                continue;
            }

            String type = determinePhoneType(rawVal);
            String realVal = rawVal.replaceAll("\\(.*?\\)", "")
                    .replaceAll("집|직장|휴대전화", "")
                    .trim();

            String finalKey = String.format("%s(%s)", key, type);
            record.put(finalKey, realVal);
        }
    }

    /**
     * 표 데이터를 처리하기 쉬운 2차원 리스트 구조로 통합합니다.
     */
    private List<List<String>> combineToGrid(PageTable.TableData table) {
        return Stream.concat(
                Stream.ofNullable(table.headers()),
                table.rows() != null ? table.rows().stream() : Stream.empty()).collect(Collectors.toList());
    }

    /**
     * 연락처 문자열을 분석하여 전화번호의 성격(집, 직장 등)을 식별합니다.
     */
    private String determinePhoneType(String text) {
        if (text.contains("휴대"))
            return "휴대전화";
        if (text.contains("직장"))
            return "직장";
        if (text.contains("집"))
            return "집";
        return "기타";
    }

    /**
     * 텍스트의 불필요한 노이즈를 정제합니다.
     */
    private String cleanText(String text) {
        return TextCleaner.clean(text);
    }
}
