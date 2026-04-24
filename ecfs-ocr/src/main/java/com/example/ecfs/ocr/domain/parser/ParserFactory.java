package com.example.ecfs.ocr.domain.parser;

import org.springframework.stereotype.Component;

import com.example.ecfs.ocr.common.constant.ProcessingConstants;

import lombok.RequiredArgsConstructor;

/**
 * 섹션 유형에 적합한 파서를 제공하는 팩토리.
 */
@Component
@RequiredArgsConstructor
public class ParserFactory {

    private final DefaultParser defaultParser;
    private final DebtListParser debtListParser;
    private final AccountParser accountParser;

    /**
     * 섹션 명칭에 매핑되는 파서를 반환합니다.
     */
    public SectionParser getParser(String sectionName) {
        if (sectionName == null) {
            return defaultParser;
        }

        return switch (sectionName) {
            case ProcessingConstants.NAME_CREDITOR_LIST -> debtListParser;
            case ProcessingConstants.NAME_ACCOUNT_REPORT -> accountParser;
            default -> defaultParser; 
        };
    }
}
