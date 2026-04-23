package com.softgram.ecfs.ocr.domain.parser;

import java.util.List;

import com.softgram.ecfs.ocr.domain.model.document.PageTable;
import com.softgram.ecfs.ocr.domain.model.analysis.ExtractedData;

/**
 * 문서 구역별로 특화된 데이터 추출 로직을 정의하는 인터페이스.
 */
public interface SectionParser {

    /**
     * 원시 표 데이터를 분석하여 정형화된 모델로 변환합니다.
     */
    List<ExtractedData> parse(List<PageTable> pages);
}
