package com.example.ecfs.barcode.controller;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.example.ecfs.barcode.service.BarcodeService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/barcode")
@RequiredArgsConstructor
@Tag(name = "Barcode API", description = "바코드 및 QR코드 인식 API")
public class BarcodeController {

    private static final String SAMPLE_IMAGE_PATH = "samples/sample-qr.png";

    private final BarcodeService barcodeService;

    @Operation(summary = "바코드 이미지 디코딩", description = "업로드된 이미지 파일에서 바코드 또는 QR코드 텍스트를 추출합니다.")
    @PostMapping(value = "/decode", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BarcodeDecodeResponse> decode(
            @Parameter(description = "스캔할 바코드/QR코드 이미지 파일", required = true) @RequestParam("file") MultipartFile file) {

        if (file.isEmpty()) {
            log.warn("Request file is empty.");
            return ResponseEntity.badRequest().build();
        }

        try (InputStream imageStream = file.getInputStream()) {
            String decodedText = barcodeService.decode(imageStream);

            if (!StringUtils.hasText(decodedText)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }

            BarcodeDecodeResponse response = new BarcodeDecodeResponse(decodedText);

            return ResponseEntity.ok(response);

        } catch (IOException e) {
            log.error("업로드된 파일 스트림을 읽는 중 예외가 발생했습니다.", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @Operation(summary = "샘플 바코드 디코딩", description = "서버에 내장된 샘플 이미지를 사용하여 디코딩 기능을 테스트합니다.")
    @GetMapping("/sample")
    public ResponseEntity<BarcodeDecodeResponse> decodeSample() {

        ClassPathResource resource = new ClassPathResource(SAMPLE_IMAGE_PATH);

        try (InputStream imageStream = resource.getInputStream()) {
            String decodedText = barcodeService.decode(imageStream);

            if (!StringUtils.hasText(decodedText)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }

            BarcodeDecodeResponse response = new BarcodeDecodeResponse(decodedText);

            return ResponseEntity.ok(response);

        } catch (IOException e) {
            log.error("샘플 이미지 리소스 [{}]를 읽는 중 예외가 발생했습니다.", SAMPLE_IMAGE_PATH, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 바코드 인식 결과 응답 DTO
     */
    public record BarcodeDecodeResponse(
            String content) {
    }
}
