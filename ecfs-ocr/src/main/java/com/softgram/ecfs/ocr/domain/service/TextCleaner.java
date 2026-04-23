package com.softgram.ecfs.ocr.domain.service;

import java.util.regex.Pattern;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 추출된 텍스트의 잡음을 제거하고 비즈니스 형식(화폐, 오타 등)을 정제하는 처리기.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TextCleaner {

    /** 연속된 공백 패턴 */
    private static final Pattern PATTERN_WHITESPACE = Pattern.compile("\\s+");

    /** 금액 정규화용 패턴 (숫자, 콤마, 마침표 위주) */
    private static final Pattern PATTERN_CURRENCY_DOT = Pattern.compile(".*\\d+\\.\\d{2,}.*");
    
    /** 천 단위 구분자로 쓰인 마침표 탐지 */
    private static final Pattern PATTERN_THOUSANDS_DOT = Pattern.compile(".*\\d{1,3}\\.\\d{3}.*");

    /**
     * 추출된 원시 텍스트의 잡음을 제거하고 비즈니스 규격에 맞게 정제합니다.
     */
    public static String clean(final String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String cleaned = text.trim();

        // 1. 공통 노이즈 제거 (연속된 공백 등)
        cleaned = PATTERN_WHITESPACE.matcher(cleaned).replaceAll(" ");

        // 2. 숫자 영역 오인식 교정 (금액 데이터로 추정되는 경우)
        cleaned = fixConfusedCharacters(cleaned);

        // 3. 금액 형식 보정
        cleaned = fixCurrencyFormat(cleaned);
        cleaned = fixThousandsSeparator(cleaned);

        return cleaned;
    }

    /**
     * 숫자 영역에서 오인식되기 쉬운 유사 영문자를 숫자로 교정합니다.
     */
    private static String fixConfusedCharacters(final String text) {
        if (text == null) return null;
        
        // 금액이나 숫자가 포함된 것으로 보이는 경우에만 교정 수행
        if (text.matches(".*[0-9].*") || text.contains(",") || text.contains("원")) {
            return text.replace("O", "0")
                       .replace("o", "0")
                       .replace("I", "1")
                       .replace("l", "1")
                       .replace("S", "5")
                       .replace("s", "5")
                       .replace("B", "8")
                       .replace("Z", "2");
        }
        return text;
    }

    /**
     * 금액 데이터 말미의 불필요한 마침표를 제거합니다.
     */
    private static String fixCurrencyFormat(final String text) {
        if (PATTERN_CURRENCY_DOT.matcher(text).matches()) {
            return text.replace(".", "");
        }
        return text;
    }

    /**
     * 천 단위 구분자로 잘못 쓰인 마침표를 콤마로 변환합니다.
     */
    private static String fixThousandsSeparator(final String text) {
        if (PATTERN_THOUSANDS_DOT.matcher(text).matches()) {
            return text.replace(".", ",");
        }
        return text;
    }
}
