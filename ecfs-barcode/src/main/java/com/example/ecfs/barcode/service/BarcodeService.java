package com.example.ecfs.barcode.service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import javax.imageio.ImageIO;

import org.springframework.stereotype.Service;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class BarcodeService {

    // 바코드 인식을 위한 디코딩 옵션
    private static final Map<DecodeHintType, Object> DECODE_HINTS = Map.of(
            DecodeHintType.TRY_HARDER, Boolean.TRUE);

    /**
     * 입력 스트림에서 이미지를 읽어 바코드 텍스트를 해독합니다.
     *
     * @param inputStream 이미지 데이터 스트림
     * @return 해독된 바코드 텍스트 (인식 실패 시 null 반환)
     * @throws IOException 이미지 데이터를 읽을 수 없거나 손상된 경우
     */
    public String decode(InputStream inputStream) throws IOException {

        BufferedImage image = ImageIO.read(inputStream);

        if (image == null) {
            throw new IOException("스트림에서 유효한 이미지 데이터를 읽을 수 없습니다.");
        }

        return decode(image);
    }

    /**
     * 메모리에 로드된 이미지 객체에서 바코드 텍스트를 해독합니다.
     *
     * @param image 분석할 이미지 객체
     * @return 해독된 바코드 텍스트 (인식 실패 시 null 반환)
     */
    public String decode(BufferedImage image) {

        try {
            // 이미지 데이터를 ZXing이 해독할 수 있는 이진 비트맵으로 변환
            LuminanceSource source = new BufferedImageLuminanceSource(image);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

            // 바코드 해독
            MultiFormatReader reader = new MultiFormatReader();
            Result result = reader.decode(bitmap, DECODE_HINTS);

            return result.getText();

        } catch (ReaderException e) {
            return null;
        }
    }
}
