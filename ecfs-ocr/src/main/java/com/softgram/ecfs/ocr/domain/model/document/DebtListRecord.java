package com.softgram.ecfs.ocr.domain.model.document;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.softgram.ecfs.ocr.common.constant.ProcessingConstants;
import com.softgram.ecfs.ocr.domain.model.analysis.ExtractedData;
import com.softgram.ecfs.ocr.domain.model.analysis.Field;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * [개인회생채권자목록] 전용 데이터 레코드 DTO.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class DebtListRecord implements ExtractedData {

    @JsonProperty(ProcessingConstants.KEY_CREDITOR_NO)
    private Field creditorNo;

    @JsonProperty(ProcessingConstants.KEY_CREDITOR_NAME)
    private Field creditorName;

    @JsonProperty(ProcessingConstants.KEY_PRINCIPAL)
    private Field principal;

    @JsonProperty(ProcessingConstants.KEY_INTEREST)
    private Field interest;

    @JsonProperty(ProcessingConstants.KEY_PAGE)
    private String page;

    /** 기타 동적으로 추가될 수 있는 필드 */
    @Builder.Default
    @JsonIgnore
    private Map<String, Object> additionalFields = new LinkedHashMap<>();

    @JsonAnyGetter
    public Map<String, Object> getAdditionalFields() {
        return additionalFields;
    }

    @Override
    @JsonIgnore
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        if (creditorNo != null) map.put(ProcessingConstants.KEY_CREDITOR_NO, creditorNo);
        if (creditorName != null) map.put(ProcessingConstants.KEY_CREDITOR_NAME, creditorName);
        if (principal != null) map.put(ProcessingConstants.KEY_PRINCIPAL, principal);
        if (interest != null) map.put(ProcessingConstants.KEY_INTEREST, interest);
        if (page != null) map.put(ProcessingConstants.KEY_PAGE, page);
        map.putAll(additionalFields);
        return map;
    }

    @Override
    public Object get(String key) {
        return switch (key) {
            case ProcessingConstants.KEY_CREDITOR_NO -> creditorNo;
            case ProcessingConstants.KEY_CREDITOR_NAME -> creditorName;
            case ProcessingConstants.KEY_PRINCIPAL -> principal;
            case ProcessingConstants.KEY_INTEREST -> interest;
            case ProcessingConstants.KEY_PAGE -> page;
            default -> additionalFields.get(key);
        };
    }

    @JsonAnySetter
    public void put(String key, Object value) {
        switch (key) {
            case ProcessingConstants.KEY_CREDITOR_NO -> creditorNo = toField(value);
            case ProcessingConstants.KEY_CREDITOR_NAME -> creditorName = toField(value);
            case ProcessingConstants.KEY_PRINCIPAL -> principal = toField(value);
            case ProcessingConstants.KEY_INTEREST -> interest = toField(value);
            case ProcessingConstants.KEY_PAGE -> page = value != null ? value.toString() : null;
            default -> additionalFields.put(key, value);
        }
    }

    private Field toField(Object value) {
        if (value instanceof Field f) return f;
        if (value instanceof String s) return Field.builder().value(s).build();
        return null;
    }
}
