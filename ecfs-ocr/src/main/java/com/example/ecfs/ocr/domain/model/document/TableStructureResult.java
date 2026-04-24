package com.example.ecfs.ocr.domain.model.document;

import java.util.List;
import org.opencv.core.Mat;

/**
 * 표 구조 분석 결과와 전처리된 이미지 자산을 관리하는 데이터 객체입니다.
 */
public record TableStructureResult(

    /** 분석을 통해 추출된 개별 셀 데이터 목록 */
    List<TableCell> cells,

    /** 표의 격자(Line) 추출에 사용된 이진화 마스크 이미지 */
    Mat binaryMask,

    /** 노이즈 제거 및 왜곡 보정이 완료된 가공 이미지 */
    Mat repairedImage,

    /** 셀 경계 및 분석 프로세스가 시각화된 디버깅용 이미지 */
    Mat debugImage
) {
    /**
     * 최소 정보(셀 목록, 마스크)만으로 결과를 생성합니다.
     */
    public TableStructureResult(List<TableCell> cells, Mat binaryMask) {
        this(cells, binaryMask, null, null);
    }

    /**
     * 보정된 이미지를 포함하여 결과를 생성합니다.
     */
    public TableStructureResult(List<TableCell> cells, Mat binaryMask, Mat repairedImage) {
        this(cells, binaryMask, repairedImage, null);
    }

    /**
     * 보유 중인 모든 OpenCV Mat 자원을 메모리에서 해제합니다.
     */
    public void release() {
        // 내부 자원이 존재할 경우에만 메모리 해제 수행
        if (binaryMask != null) binaryMask.release();
        if (repairedImage != null) repairedImage.release();
        if (debugImage != null) debugImage.release();
    }
}
