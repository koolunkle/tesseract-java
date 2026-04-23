package com.softgram.ecfs.ocr.domain.model.analysis;

import java.util.Map;

/**
 * 추출된 데이터 레코드를 나타내는 공용 인터페이스.
 */
public interface ExtractedData {
    Map<String, Object> toMap();
    Object get(String key);
}
