package com.softgram.ecfs.ocr.common.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.UUID;

import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

/**
 * MultipartFile 및 임시 파일 시스템의 물리적 제어를 담당하는 유틸리티입니다.
 */
@Slf4j
@UtilityClass
public class FileUtils {

    /** 임시 파일 생성 시 사용할 접두사 */
    private static final String TEMP_FILE_PREFIX = "ecfs-";

    /** 기본 임시 확장자 */
    private static final String DEFAULT_TEMP_EXTENSION = ".tmp";

    /** 파일명을 식별할 수 없을 때의 대체 기본값 */
    private static final String UNKNOWN_FILENAME = "unknown";

    /** OS의 기본 임시 디렉터리 경로 */
    private static final String OS_TEMP_DIR = System.getProperty("java.io.tmpdir");

    /** 애플리케이션 전용 임시 디렉터리 명칭 */
    private static final String APP_TEMP_DIR_NAME = "ecfs-temp";

    /**
     * 임시 파일을 생성하여 로직을 실행하고 작업 완료 후 자동 삭제합니다.
     */
    public static <T> T process(final MultipartFile file, final PathProcessor<T> processor) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("처리할 파일 데이터가 존재하지 않습니다.");
        }

        final String extension = getExtension(file.getOriginalFilename());
        final String suffix = extension.isEmpty() ? DEFAULT_TEMP_EXTENSION : "." + extension;

        Path tempPath = null;
        try {
            // 시스템 임시 폴더에 파일 생성
            tempPath = Files.createTempFile(TEMP_FILE_PREFIX, suffix);
            file.transferTo(Objects.requireNonNull(tempPath));
            
            return processor.process(tempPath);

        } catch (final Exception e) {
            log.error("파일 처리 중 오류 발생: {}", e.getMessage());
            throw new RuntimeException("파일 처리 중 시스템 오류가 발생했습니다.", e);
        } finally {
            // 작업 성공 여부와 관계없이 물리 파일 삭제
            delete(tempPath);
        }
    }

    /**
     * 파일을 앱 전용 임시 디렉터리 내 지정된 서브 디렉터리에 저장합니다.
     */
    public static Path saveToTemp(final MultipartFile file, final String subDir) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("저장할 파일이 존재하지 않습니다.");
        }

        // 고유 파일명 생성 (UUID 활용으로 중복 방지)
        final String originalName = file.getOriginalFilename();
        final String safeName = (originalName != null && !originalName.isBlank()) ? originalName : UNKNOWN_FILENAME;
        final String cleanName = StringUtils.cleanPath(safeName);
        final String uniqueName = UUID.randomUUID() + "_" + cleanName;

        // 저장 디렉터리 확보
        final Path directory = Paths.get(OS_TEMP_DIR, APP_TEMP_DIR_NAME, subDir).normalize();
        if (Files.notExists(directory)) {
            Files.createDirectories(directory);
        }

        final Path target = directory.resolve(uniqueName);
        file.transferTo(Objects.requireNonNull(target));

        return target;
    }

    /**
     * 지정된 경로의 파일을 안전하게 삭제합니다.
     */
    public static void delete(final Path path) {
        if (path == null) return;

        try {
            if (Files.deleteIfExists(path)) {
                log.debug("임시 파일 삭제 완료: {}", path);
            }
        } catch (final IOException e) {
            log.warn("파일 삭제 실패: {} (사유: {})", path, e.getMessage());
        }
    }

    /**
     * 파일명에서 확장자를 추출하여 소문자로 반환합니다.
     */
    public static String getExtension(final String filename) {
        if (filename == null) return "";
        
        final int lastDot = filename.lastIndexOf('.');
        if (lastDot == -1) return "";
        
        return filename.substring(lastDot + 1).toLowerCase();
    }

    /**
     * 파일 경로 처리를 위한 함수형 인터페이스입니다.
     */
    @FunctionalInterface
    public interface PathProcessor<T> {
        T process(Path path) throws Exception;
    }
}
