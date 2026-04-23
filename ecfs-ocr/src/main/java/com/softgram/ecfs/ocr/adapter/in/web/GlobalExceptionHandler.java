package com.softgram.ecfs.ocr.adapter.in.web;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.softgram.ecfs.ocr.common.exception.ProcessingException;

import lombok.extern.slf4j.Slf4j;

/**
 * 모듈 내에서 발생하는 예외를 가로채어 표준화된 응답 포맷으로 변환하는 핸들러.
 */
@Slf4j
@RestControllerAdvice(basePackageClasses = JobController.class)
public class GlobalExceptionHandler {

    /**
     * 비즈니스 로직 및 처리 공정 중 발생한 예외를 400 에러로 변환합니다.
     */
    @ExceptionHandler(ProcessingException.class)
    public ResponseEntity<ErrorResponse> handleOcrException(ProcessingException ex) {
        log.warn("OCR Domain Exception: [Code: {}], [Message: {}]", ex.getErrorCode(), ex.getMessage());
        
        ErrorResponse response = ErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                ex.getErrorCode(),
                ex.getMessage()
        );
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * 파일 크기 초과 예외 처리 (413 Payload Too Large)
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex) {
        log.warn("File upload size exceeded: {}", ex.getMessage());

        ErrorResponse response = ErrorResponse.of(
                HttpStatus.PAYLOAD_TOO_LARGE.value(),
                "FILE_SIZE_EXCEEDED",
                "업로드 가능한 파일 크기를 초과했습니다."
        );

        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(response);
    }

    /**
     * 입출력 예외 처리 (500 Internal Server Error)
     */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<ErrorResponse> handleIOException(IOException ex) {
        log.error("IO Exception during OCR processing: ", ex);

        ErrorResponse response = ErrorResponse.of(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "IO_FAILURE",
                "파일 처리 중 오류가 발생했습니다."
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    /**
     * 기타 예상치 못한 모든 예외 처리 (500 Internal Server Error)
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleAllExceptions(Exception ex) {
        log.error("Unhandled Global Exception: ", ex);

        ErrorResponse response = ErrorResponse.of(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "INTERNAL_SERVER_ERROR",
                "서버 내부 오류가 발생했습니다. 관리자에게 문의하세요."
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
