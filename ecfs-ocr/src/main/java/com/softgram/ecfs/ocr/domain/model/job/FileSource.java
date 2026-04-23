package com.softgram.ecfs.ocr.domain.model.job;

import java.io.InputStream;

/**
 * 인프라 기술로부터 독립된 시스템 내부 전용 문서 소스 모델.
 */
public record FileSource(
        String fileName,
        InputStream inputStream,
        long size,
        String contentType
) {
}
