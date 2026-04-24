package com.example.ecfs.ocr.domain.model.document;

import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.ecfs.ocr.common.constant.ProcessingConstants;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 문서 텍스트 패턴 분석을 통한 논리적 구역 식별기입니다.
 */
@Getter
@RequiredArgsConstructor
public enum SectionType {
    
    /** 법원 기관명 */
    COURT(text -> matchesSection(text, ProcessingConstants.NAME_COURT), ProcessingConstants.NAME_COURT),

    /** 채권자 목록 */
    LIST(text -> matchesSection(text, ProcessingConstants.NAME_CREDITOR_LIST), ProcessingConstants.NAME_CREDITOR_LIST),

    /** 채권자 계좌번호신고서 */
    ACCOUNT(text -> matchesSection(text, ProcessingConstants.NAME_ACCOUNT_REPORT), ProcessingConstants.NAME_ACCOUNT_REPORT),

    /** 변제계획안 제출서 */
    SUBMISSION(text -> text.contains("제출") && (text.contains("변제") || text.contains("계획")), ProcessingConstants.NAME_SUBMISSION),

    /** 변제계획안 */
    PLAN(text -> matchesSection(text, ProcessingConstants.NAME_PLAN), ProcessingConstants.NAME_PLAN_DRAFT),

    /** 변제예정액표 */
    SCHEDULE(text -> matchesSection(text, ProcessingConstants.NAME_PAYMENT_SCHEDULE), ProcessingConstants.NAME_PAYMENT_SCHEDULE);

    /** 섹션 타이틀로 인정하기 위한 앞/뒤 여분 텍스트의 최대 길이 */
    private static final int MAX_TITLE_PADDING_LENGTH = 5;

    /** 문장 여부 판단을 위한 조사 목록 (긴 문자열 우선순위) */
    private static final List<String> PARTICLE_LIST = Arrays.asList(
            "에서의", "으로는", "에서는",
            "에서", "으로", "에게", "한테", "까지", "부터",
            "은", "는", "이", "가", "을", "를",
            "에", "의", "로", "도", "만", "와", "과", "랑"
    );

    /** 섹션 일치 여부를 판단하는 조건 로직 */
    private final Predicate<String> matcher;

    /** 섹션의 대표 명칭 */
    private final String sectionName;

    /** 내부 정규식 패턴 관리 클래스 */
    private static class Patterns {
        /** 법원명 패턴 (예: 서울회생법원, 수원지방법원 안산지원) */
        private static final Pattern PATTERN_COURT = Pattern.compile("([가-힣]{2,}법원(?:[가-힣]+지원)?)");
    }

    /**
     * 입력 텍스트를 분석하여 해당하는 섹션 유형을 식별합니다.
     */
    public static String identify(String text) {
        if (text == null || text.isBlank()) {
            return ProcessingConstants.NAME_UNKNOWN;
        }

        return Arrays.stream(values())
                .filter(type -> type.matcher.test(text))
                .findFirst()
                .map(type -> extractSectionName(type, text))
                .orElse(ProcessingConstants.NAME_UNKNOWN);
    }

    /**
     * 특정 섹션 타입의 규칙에 따라 실제 섹션명을 추출합니다.
     */
    private static String extractSectionName(SectionType type, String text) {
        return switch (type) {
            case COURT -> {
                Matcher matcher = Patterns.PATTERN_COURT.matcher(text);
                yield matcher.find() ? matcher.group(1) : ProcessingConstants.NAME_UNKNOWN;
            }
            default -> type.getSectionName();
        };
    }

    /**
     * 키워드 매칭 및 문맥 분석(조사 체크)을 통해 섹션 타이틀 여부를 확인합니다.
     */
    private static boolean matchesSection(String text, String keyword) {
        if (text == null || text.isBlank()) {
            return false;
        }

        // 공백 제거 후 인덱스 탐색
        String normalizedText = text.replace(" ", "");
        String normalizedKeyword = keyword.replace(" ", "");
        int keywordIndex = normalizedText.indexOf(normalizedKeyword);

        if (keywordIndex == -1) {
            return false;
        }

        String prefix = normalizedText.substring(0, keywordIndex);
        String suffix = normalizedText.substring(keywordIndex + normalizedKeyword.length());

        // 앞뒤 텍스트가 길면 본문 문장으로 간주
        if (prefix.length() > MAX_TITLE_PADDING_LENGTH || suffix.length() > MAX_TITLE_PADDING_LENGTH) {
            return false;
        }

        // 뒤에 조사가 붙어있으면 타이틀이 아닌 문장으로 판단
        return !startsWithParticle(suffix);
    }

    /**
     * 문자열이 한국어 조사로 시작하는지 확인합니다.
     */
    private static boolean startsWithParticle(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return PARTICLE_LIST.stream().anyMatch(text::startsWith);
    }
}
