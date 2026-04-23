package com.softgram.ecfs.ocr.domain.model.document;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.opencv.core.Rect;

import com.softgram.ecfs.ocr.domain.service.TextCleaner;

import lombok.Getter;
import lombok.Setter;
import net.sourceforge.tess4j.Word;

/**
 * 이미지 내 표의 개별 셀(Cell) 정보를 관리하며, 좌표 기반으로 텍스트를 재구성합니다.
 */
@Getter
@Setter
public class TableCell {

    /** Y축 라인 그룹화 시 허용할 오차 범위 (픽셀 단위) */
    private static final int Y_TOLERANCE_PIXELS = 10;

    /** 셀의 물리적 위치 및 크기 정보 */
    private Rect boundingBox;

    /** 셀 내부에서 감지된 단어 객체 목록 */
    private List<Word> detectedWords = new ArrayList<>();

    /** OCR 결과 대신 수동으로 설정할 텍스트 값 */
    private String overrideText;

    public TableCell(Rect boundingBox) {
        this.boundingBox = boundingBox;
    }

    /**
     * 셀의 최종 텍스트를 반환합니다. (수동 설정값 우선)
     */
    public String getText() {
        return assembleText();
    }

    /**
     * 셀 내부에 새로운 단어 정보를 추가합니다.
     */
    public void addWord(Word word) {
        if (word != null) {
            this.detectedWords.add(word);
        }
    }

    /**
     * 좌표를 분석하여 단어들을 읽기 순서(상->하, 좌->우)로 재배열하고 텍스트를 조립합니다.
     */
    public String assembleText() {
        if (overrideText != null) {
            return overrideText;
        }
        
        if (detectedWords == null || detectedWords.isEmpty()) {
            return "";
        }

        // 1. Y축 좌표를 기준으로 유사한 높이의 단어들을 라인별로 그룹화
        Map<Integer, List<Word>> lineGroups = new TreeMap<>();

        for (Word word : detectedWords) {
            String text = word.getText();
            if (text == null || text.isBlank()) continue;

            int currentY = word.getBoundingBox().y;
            int matchedLineY = findMatchingLineY(lineGroups.keySet(), currentY);

            lineGroups.computeIfAbsent(matchedLineY, k -> new ArrayList<>()).add(word);
        }

        // 2. 각 라인 내에서 X축 순서로 정렬 후 줄바꿈 문자로 병합
        String rawText = lineGroups.values().stream()
                .map(line -> line.stream()
                        .sorted(Comparator.comparingInt(w -> w.getBoundingBox().x))
                        .map(Word::getText)
                        .map(String::trim)
                        .collect(Collectors.joining(" ")))
                .collect(Collectors.joining("\n"));

        return TextCleaner.clean(rawText);
    }

    /**
     * 유효한 텍스트 내용이 존재하는지 확인합니다.
     */
    public boolean hasText() {
        String text = assembleText();
        return text != null && !text.isEmpty();
    }

    /**
     * 객체의 상태를 문자열로 반환합니다. (디버깅용)
     */
    @Override
    public String toString() {
        String rectInfo = Optional.ofNullable(boundingBox)
                .map(r -> String.format("[%d,%d %dx%d]", r.x, r.y, r.width, r.height))
                .orElse("no-rect");
                
        return String.format("TableCell(rect=%s, text='%s')", rectInfo, assembleText());
    }

    /**
     * 현재 Y좌표가 기존 라인들의 허용 오차 범위 내에 있는지 확인하여 기준 Y좌표를 반환합니다.
     */
    private int findMatchingLineY(Iterable<Integer> existingLines, int currentY) {
        for (int existingY : existingLines) {
            if (Math.abs(existingY - currentY) <= Y_TOLERANCE_PIXELS) {
                return existingY;
            }
        }
        return currentY;
    }
}
