package com.softgram.ecfs.ocr.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

/**
 * Jackson 엔진을 활용하여 객체와 JSON 텍스트 간의 상태 변환을 처리합니다.
 */
@Slf4j
@UtilityClass
public class JsonUtils {

    /** JSON 배열 형태의 기본 빈 값 */
    private static final String EMPTY_JSON_ARRAY = "[]";

    /** 전역적으로 재사용되는 스레드 세이프(Thread-safe)한 JSON 처리 엔진 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule()) // Java 8 날짜/시간(ISO-8601) 포맷 지원 설정
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES); // 객체에 정의되지 않은 속성이 JSON에 있어도 오류를 발생시키지 않음

    /** 가독성 높은 출력을 위한 Pretty Printer */
    private static final ObjectWriter PRETTY_WRITER = MAPPER.writerWithDefaultPrettyPrinter();

    /**
     * Java 객체를 JSON 문자열로 직렬화합니다.
     */
    public static <T> String stringify(T data) {
        if (data == null) {
            return null;
        }

        try {
            return MAPPER.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            log.error("JSON 직렬화 실패: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 들여쓰기가 적용된 가독성 높은 JSON 문자열을 반환합니다. (디버깅용)
     */
    public static <T> String prettyPrint(T data) {
        if (data == null) {
            return EMPTY_JSON_ARRAY;
        }

        try {
            return PRETTY_WRITER.writeValueAsString(data);
        } catch (Exception e) {
            log.error("Pretty JSON 포맷팅 실패: {}", e.getMessage());
            return EMPTY_JSON_ARRAY;
        }
    }

    /**
     * JSON 문자열을 지정된 타입의 객체로 역직렬화합니다.
     */
    public static <T> T parse(String json, Class<T> clazz) {
        if (json == null || json.isBlank()) {
            return null;
        }

        try {
            return MAPPER.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            log.error("JSON 역직렬화 실패 (Target Type: {}): {}", clazz.getSimpleName(), e.getMessage());
            return null;
        }
    }
}
