package com.example.ecfs.ocr.domain.model.analysis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonValue;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 추출된 데이터의 최소 의미 단위(값 및 위치 정보)를 담당하는 공용 모델.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class Field {

    private String value;

    @JsonIgnore
    private int page;

    @Builder.Default
    @JsonIgnore
    @ToString.Exclude
    private List<CellBox> locations = new ArrayList<>();

    @JsonValue
    public String getValue() {
        return value != null ? value : "";
    }

    public void addLocation(CellBox box) {
        if (box != null) {
            this.locations.add(box);
        }
    }

    public void appendValue(String moreValue) {
        if (moreValue == null || moreValue.isBlank()) return;
        if (this.value == null || this.value.isBlank()) {
            this.value = moreValue;
            return;
        }
        boolean isDuplicate = Arrays.asList(this.value.split("\\s+")).contains(moreValue);
        if (!isDuplicate) {
            this.value = this.value + " " + moreValue;
        }
    }
}
