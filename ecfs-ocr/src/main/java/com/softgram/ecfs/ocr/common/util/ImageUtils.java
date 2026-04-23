package com.softgram.ecfs.ocr.common.util;

import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

import org.opencv.core.CvType;
import org.opencv.core.Mat;

import lombok.Getter;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import nu.pattern.OpenCV;

/**
 * OpenCV 라이브러리 로드 및 이미지 데이터 타입(Mat <-> BufferedImage) 간 변환을 처리합니다.
 */
@Slf4j
@UtilityClass
public class ImageUtils {

    /** 라이브러리 로컬 로드를 지원하는 최소 Java 버전 */
    private static final int MIN_JAVA_VERSION_FOR_LOCAL_LOAD = 12;

    /** 네이티브 라이브러리 로드 완료 상태 */
    @Getter
    private static volatile boolean loaded = false;

    static {
        load();
    }

    /**
     * 실행 환경의 JVM 버전에 맞춰 OpenCV 네이티브 라이브러리를 로드합니다.
     */
    public synchronized static void load() {
        if (loaded) return;

        try {
            int majorVersion = Runtime.version().feature();
            log.info("Initializing OpenCV (JVM: {})", majorVersion);

            // JVM 12 이상은 로컬 로드, 그 미만은 공유 라이브러리 로드 방식 사용
            if (majorVersion >= MIN_JAVA_VERSION_FOR_LOCAL_LOAD) {
                OpenCV.loadLocally();
            } else {
                OpenCV.loadShared();
            }

            loaded = true;
            log.info("OpenCV library loaded successfully.");

        } catch (Throwable e) {
            // 네이티브 에러는 복구가 불가능하므로 상세 스택 트레이스 기록
            log.error("Failed to load OpenCV native library.", e);
            loaded = false;
        }
    }

    /**
     * 라이브러리가 정상적으로 로드되었는지 확인합니다.
     */
    public static void validateState() {
        if (!loaded) {
            throw new IllegalStateException("OpenCV native library is not loaded.");
        }
    }

    /**
     * OpenCV Mat 객체들의 네이티브 메모리 자원을 명시적으로 해제합니다.
     */
    public static void release(Mat... mats) {
        if (mats == null) return;

        for (Mat mat : mats) {
            // 빈 객체가 아닐 경우에만 C++ 힙 영역 메모리 반환
            if (mat != null && !mat.empty()) {
                mat.release();
            }
        }
    }

    /**
     * OpenCV Mat 객체를 Java BufferedImage로 변환합니다.
     */
    public static BufferedImage toBufferedImage(Mat mat) {
        if (mat == null || mat.empty()) return null;

        // 채널 수에 따라 컬러(BGR) 또는 흑백(Gray) 타입 결정
        int imageType = (mat.channels() > 1) ? BufferedImage.TYPE_3BYTE_BGR : BufferedImage.TYPE_BYTE_GRAY;
        
        // Mat 데이터를 Java byte 배열로 복사
        byte[] sourceData = new byte[mat.channels() * mat.cols() * mat.rows()];
        mat.get(0, 0, sourceData);

        // BufferedImage 버퍼에 직접 데이터 주입
        BufferedImage image = new BufferedImage(mat.cols(), mat.rows(), imageType);
        byte[] targetPixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        System.arraycopy(sourceData, 0, targetPixels, 0, sourceData.length);

        return image;
    }

    /**
     * Java BufferedImage를 OpenCV Mat 객체로 변환합니다.
     */
    public static Mat toMat(BufferedImage bi) {
        if (bi == null) return new Mat();

        BufferedImage sourceImage = bi;

        // OpenCV 처리에 적합한 BGR 형식이 아닐 경우 강제 변환 수행
        if (bi.getType() != BufferedImage.TYPE_3BYTE_BGR) {
            sourceImage = new BufferedImage(bi.getWidth(), bi.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
            Graphics g = sourceImage.getGraphics();
            g.drawImage(bi, 0, 0, null);
            g.dispose();
        }

        // 이미지 픽셀 데이터를 네이티브 Mat 구조로 복사
        byte[] pixels = ((DataBufferByte) sourceImage.getRaster().getDataBuffer()).getData();
        Mat mat = new Mat(sourceImage.getHeight(), sourceImage.getWidth(), CvType.CV_8UC3);
        mat.put(0, 0, pixels);

        return mat;
    }
}
