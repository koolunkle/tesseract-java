package com.softgram.ecfs.ocr.common.constant;

import lombok.experimental.UtilityClass;

/**
 * 분석 공정 및 시스템 전역에서 합의되어 사용되는 공용 리터럴 및 설정 상수를 관리합니다.
 * 비즈니스 로직(파싱 키워드) 및 시스템 식별자 위주로 구성합니다.
 */
@UtilityClass
public final class ProcessingConstants {

    // =========================================================================
    // 1. 문서 섹션 및 식별자 명칭
    // =========================================================================

    /** 개인회생채권자목록 섹션 명칭 */
    public static final String NAME_CREDITOR_LIST = "개인회생채권자목록";

    /** 개인회생채권변제예정액표 섹션 명칭 */
    public static final String NAME_PAYMENT_SCHEDULE = "개인회생채권변제예정액표";

    /** 법원 기관 섹션 명칭 */
    public static final String NAME_COURT = "법원";

    /** 변제계획 섹션 명칭 */
    public static final String NAME_PLAN = "변제계획";

    /** 변제계획(안) 섹션 명칭 */
    public static final String NAME_PLAN_DRAFT = "변제계획(안)";

    /** 변제계획안제출서 섹션 명칭 */
    public static final String NAME_SUBMISSION = "변제계획안제출서";

    /** 채권자계좌번호신고서 섹션 명칭 */
    public static final String NAME_ACCOUNT_REPORT = "채권자계좌번호신고서";

    /** 식별할 수 없는 섹션에 대한 기본값 */
    public static final String NAME_UNKNOWN = "UNKNOWN";

    // =========================================================================
    // 2. 오퍼레이션 및 엔진 식별자
    // =========================================================================

    /** 데이터 분석 작업 식별자 */
    public static final String OPERATION_ANALYZE_DATA = "analyze_data";

    /** 윤곽선 분석 작업 식별자 */
    public static final String OPERATION_ANALYZE_CONTOUR = "analyze_contour";

    /** 품질 분석 작업 식별자 */
    public static final String OPERATION_ANALYZE_QUALITY = "analyze_quality";

    /** Tesseract 분석 시 사용할 기본 언어 (한국어) */
    public static final String DEFAULT_OCR_LANGUAGE = "kor";

    /** 비동기 OCR 엔진용 스레드 이름 접두사 */
    public static final String OCR_EXECUTOR_THREAD_PREFIX = "OcrAsync-";

    // =========================================================================
    // 3. 맵(Map) 객체 및 데이터 접근용 공용 키
    // =========================================================================

    /** 맵(Map) 객체 내 섹션 정보 저장을 위한 키 */
    public static final String KEY_SECTION = "section";

    /** 맵(Map) 객체 내 페이지 번호 저장을 위한 키 */
    public static final String KEY_PAGE = "page";

    /** 맵(Map) 객체 내 실제 데이터(Payload) 저장을 위한 키 */
    public static final String KEY_DATA = "data";

    /** 맵(Map) 객체 내 텍스트 내용 저장을 위한 키 */
    public static final String KEY_TEXT = "text";

    /** 맵(Map) 객체 내 결과/오류 메시지 저장을 위한 키 */
    public static final String KEY_MESSAGE = "message";

    // =========================================================================
    // 4. 비즈니스 도메인 데이터 키 (파싱 및 매핑용)
    // =========================================================================

    /** 채권번호 추출용 키 */
    public static final String KEY_CREDITOR_NO = "채권번호";

    /** 채권자명 추출용 키 */
    public static final String KEY_CREDITOR_NAME = "채권자";

    /** 원금 금액 추출용 키 */
    public static final String KEY_PRINCIPAL = "채권현재액(원금)";

    /** 이자 금액 추출용 키 */
    public static final String KEY_INTEREST = "채권현재액(이자)";

    /** 결과 맵 내 별제권 내용 저장을 위한 키 */
    public static final String KEY_SPECIAL_RIGHT_CONTENT = "별제권_내용";

    // =========================================================================
    // 5. 비즈니스 파싱 키워드 및 단위
    // =========================================================================

    /** 금액 정보 중 '원금' 식별 키워드 */
    public static final String KEYWORD_PRINCIPAL = "원금";

    /** 금액 정보 중 '이자' 식별 키워드 */
    public static final String KEYWORD_INTEREST = "이자";

    /** 금액 정보 중 '합계' 식별 키워드 */
    public static final String KEYWORD_TOTAL = "합계";

    /** 담보 정보 중 '저당권' 식별 키워드 */
    public static final String KEYWORD_MORTGAGE = "저당권";

    /** 텍스트 분석 중 '별제권' 항목 식별 키워드 */
    public static final String KEYWORD_SPECIAL_RIGHT = "별제권";

    /** 텍스트 분석 중 '부속서류' 항목 식별 키워드 */
    public static final String KEYWORD_ATTACHED_DOCS = "부속서류";

    /** 금액 단위 접미사 */
    public static final String SUFFIX_WON = "원";

    /** 숫자 0 리터럴 */
    public static final String VALUE_ZERO = "0";

    /** 기본 대시(-) 구분자 */
    public static final String DELIMITER_DASH = "-";
}
