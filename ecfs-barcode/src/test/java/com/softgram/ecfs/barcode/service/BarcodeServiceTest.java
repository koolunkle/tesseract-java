package com.softgram.ecfs.barcode.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import lombok.extern.slf4j.Slf4j;

@Slf4j
class BarcodeServiceTest {

    private BarcodeService barcodeService;

    @BeforeEach
    void setUp() {
        barcodeService = new BarcodeService();
    }

    @Test
    @DisplayName("정상적인 QR 코드 이미지 스트림을 전달하면 텍스트를 해독한다.")
    void decode_ValidQrCodeImage_ReturnsDecodedText() throws IOException {

        // given
        ClassPathResource resource = new ClassPathResource("samples/sample-qr.png");
        String result;

        // when
        try (InputStream source = resource.getInputStream()) {
            result = barcodeService.decode(source);
        }

        // then
        assertThat(result).isNotNull();
        log.info("해독된 QR 코드 텍스트: [{}]", result);
    }

    @Test
    @DisplayName("바코드가 없는 빈 이미지를 전달하면 null을 반환한다.")
    void decode_BlankImage_ReturnsNull() throws IOException {

        // given
        InputStream blankImageStream = createBlankImageStream();

        // when
        String result = barcodeService.decode(blankImageStream);

        // then
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("이미지 형식이 아닌 데이터를 전달하면 IOException이 발생한다.")
    void decode_InvalidData_ThrowsIOException() {

        // given
        InputStream invalidStream = new ByteArrayInputStream("invalid-data".getBytes());

        // when & then
        assertThatThrownBy(() -> barcodeService.decode(invalidStream))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("스트림에서 유효한 이미지 데이터를 읽을 수 없습니다");
    }

    /**
     * 바코드 데이터가 없는 빈 흰색 이미지 스트림을 생성합니다.
     *
     * @return 생성된 이미지의 입력 스트림
     * @throws IOException 이미지 쓰기 실패 시 발생
     */
    private InputStream createBlankImageStream() throws IOException {

        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();

        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 100, 100);
        graphics.dispose();

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "png", outputStream);

        return new ByteArrayInputStream(outputStream.toByteArray());
    }
}
