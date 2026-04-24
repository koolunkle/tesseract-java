package com.example.ecfs.ocr.common.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

/**
 * OCR 처리 과정에서 발생하는 중간 결과물(이미지)의 시각화 및 저장을 관리합니다.
 */
@Slf4j
@UtilityClass
public class DebugUtils {

    /** 파일명에서 UUID 접두사와 순번을 식별하여 제거하기 위한 패턴 */
    private static final Pattern PATTERN_UUID_PREFIX = 
            Pattern.compile("^[0-9a-f]{8}-([0-9a-f]{4}-){3}[0-9a-f]{12}_(?:\\d+_)?");

    /** 디버그용 이미지 기본 확장자 */
    private static final String DEFAULT_EXT = ".png";

    /** 결과물이 저장될 루트 디렉터리 명칭 */
    private static final String TEMP_ROOT_DIR = "temp";

    /**
     * 시각화(드로잉) 처리가 가능한 BGR 포맷의 복제본 이미지를 생성합니다.
     */
    public static Mat createDebugImage(Mat originalMat) {
        if (originalMat == null || originalMat.empty()) {
            return new Mat();
        }

        Mat debugImage = originalMat.clone();
        
        // 단일 채널(그레이스케일)인 경우 컬러 드로잉이 가능하도록 변환
        if (debugImage.channels() == 1) {
            Imgproc.cvtColor(debugImage, debugImage, Imgproc.COLOR_GRAY2BGR);
        }
        
        return debugImage;
    }

    /**
     * 분석 단계별 이미지를 지정된 경로 규칙에 따라 로컬 파일로 저장합니다.
     */
    public static void saveDebugImage(Mat image, String originalFileName, int pageNum, String jobId, 
                                     String typeSubDir, String modeSubDir, String fileSuffix) {
        if (image == null || image.empty()) {
            log.warn("Skipping save: Image is empty. (Page: {})", pageNum);
            return;
        }

        try {
            String baseFileName = extractBaseFileName(originalFileName);
            String debugFileName = String.format("%s_p%d_%s%s", baseFileName, pageNum, fileSuffix, DEFAULT_EXT);
            
            Path outputDir = buildOutputPath(jobId, typeSubDir, modeSubDir);

            if (!Files.exists(outputDir)) {
                Files.createDirectories(outputDir);
            }

            saveImageWithNio(image, outputDir.resolve(debugFileName));

        } catch (IOException e) {
            log.error("Failed to save debug image: {}", e.getMessage());
        }
    }

    /**
     * 경로로부터 이미지를 로드합니다. (NIO를 사용하여 한글 경로 호환성 확보)
     */
    public static Mat loadImage(String filePath) {
        Path path = Paths.get(filePath);
        
        if (!Files.exists(path)) {
            log.warn("File not found: {}", filePath);
            return new Mat();
        }

        MatOfByte buffer = null;
        try {
            byte[] fileBytes = Files.readAllBytes(path);
            buffer = new MatOfByte(fileBytes); 
            
            return Imgcodecs.imdecode(buffer, Imgcodecs.IMREAD_COLOR);
            
        } catch (IOException e) {
            log.error("Error occurred while loading image: {}", filePath, e);
            return new Mat();
        } finally {
            // MatOfByte 네이티브 메모리 명시적 해제
            if (buffer != null) {
                buffer.release();
            }
        }
    }

    /**
     * 파일명에서 경로와 확장자, UUID 접두사를 제거한 순수 파일명을 추출합니다.
     */
    private static String extractBaseFileName(String fileName) {
        String cleanName = PATTERN_UUID_PREFIX.matcher(fileName).replaceAll("");
        int dotIndex = cleanName.lastIndexOf('.');
        return (dotIndex == -1) ? cleanName : cleanName.substring(0, dotIndex);
    }

    /**
     * 실행 경로를 기준으로 작업 아이디와 단계별 구분자가 포함된 출력 경로를 생성합니다.
     */
    private static Path buildOutputPath(String jobId, String typeSubDir, String modeSubDir) {
        Path root = Paths.get(System.getProperty("user.dir"), TEMP_ROOT_DIR, typeSubDir, modeSubDir);
        return (jobId != null && !jobId.isBlank()) ? root.resolve(jobId) : root;
    }

    /**
     * 메모리 버퍼를 거쳐 파일을 작성함으로써 인코딩 관련 문제를 방지합니다.
     */
    private static void saveImageWithNio(Mat image, Path path) throws IOException {
        MatOfByte buffer = new MatOfByte();
        try {
            if (Imgcodecs.imencode(DEFAULT_EXT, image, buffer)) {
                Files.write(path, buffer.toArray());
                log.info("Debug image saved: {}", path.getFileName());
            }
        } finally {
            buffer.release();
        }
    }
}
