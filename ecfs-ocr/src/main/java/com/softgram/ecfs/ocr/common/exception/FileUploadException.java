package com.softgram.ecfs.ocr.common.exception;

/**
 * 파일 업로드 및 임시 파일 시스템 제어 중 발생하는 예외입니다.
 */
public class FileUploadException extends RuntimeException {

    /**
     * 예외 메시지를 지정하여 생성합니다.
     */
    public FileUploadException(String message) {
        super(message);
    }

    /**
     * 예외 메시지와 근본 원인(Throwable)을 지정하여 생성합니다.
     */
    public FileUploadException(String message, Throwable cause) {
        super(message, cause);
    }
}
