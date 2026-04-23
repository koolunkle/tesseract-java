package com.softgram.ecfs.ocr.common.constant;

import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 시스템에서 지원하는 파일 확장자 정의 및 식별을 담당하는 열거형입니다.
 * 각 타입은 고유의 확장자 문자열을 보유하며, 관련 판별 로직을 제공합니다.
 */
@Getter
@RequiredArgsConstructor
public enum FileConstants {

    /** PDF 문서 포맷 */
    PDF("pdf"),

    /** TIF 이미지 포맷 */
    TIF("tif"),

    /** TIFF 이미지 포맷 */
    TIFF("tiff"),

    /** 지원하지 않거나 정의되지 않은 기타 포맷 */
    ETC("etc");

    /** 파일 확장자 명칭 (소문자 기준) */
    private final String extension;

    /**
     * 입력된 문자열을 기반으로 일치하는 파일 상수를 식별합니다. (대소문자 무관)
     */
    public static FileConstants from(String ext) {
        if (ext == null || ext.isBlank()) {
            return ETC;
        }

        String target = ext.trim().toLowerCase();
        return Arrays.stream(values())
                .filter(type -> type.extension.equals(target))
                .findFirst()
                .orElse(ETC);
    }

    /**
     * 현재 파일 형식이 PDF인지 확인합니다.
     */
    public boolean isPdf() {
        return this == PDF;
    }

    /**
     * 현재 파일 형식이 이미지(TIF/TIFF) 계열인지 확인합니다.
     */
    public boolean isImage() {
        return this == TIF || this == TIFF;
    }
}
