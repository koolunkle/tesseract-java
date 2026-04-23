package com.softgram.ecfs.ocr.domain.model.document;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.softgram.ecfs.ocr.domain.model.analysis.ExtractedData;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;

/**
 * 정해진 스키마가 없는 범용적인 데이터 레코드를 담는 DTO.
 */
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class DefaultRecord implements ExtractedData {

    @Builder.Default
    @JsonIgnore
    private Map<String, Object> data = new LinkedHashMap<>();

    @Override
    @JsonAnyGetter
    public Map<String, Object> toMap() {
        return data;
    }

    @Override
    @JsonIgnore
    public Object get(String key) {
        return data.get(key);
    }

    @JsonAnySetter
    public void put(String key, Object value) {
        data.put(key, value);
    }
}
