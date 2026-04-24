package com.example.ecfs.ocr.domain.parser;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.ecfs.ocr.domain.model.analysis.CellBox;
import com.example.ecfs.ocr.domain.model.analysis.ExtractedData;
import com.example.ecfs.ocr.domain.model.document.DebtListRecord;
import com.example.ecfs.ocr.domain.model.document.PageTable;
import com.example.ecfs.ocr.domain.model.document.PageTable.CellData;
import com.example.ecfs.ocr.domain.model.document.PageTable.TableData;

/**
 * 채권자 목록 서식에서 데이터를 추출하고 도메인 모델로 변환하는 규칙을 테스트합니다.
 */
class DebtListParserTest {

    private DebtListParser debtListParser;

    @BeforeEach
    void setUp() {
        debtListParser = new DebtListParser();
    }

    @Test
    @DisplayName("셀의 좌표를 기준으로 채권자 번호, 성명, 금액 필드를 분류한다.")
    void mapFieldsByCellCoordinates() {
        // given: 헤더 좌표 정보가 있는 셀 데이터 구성
        List<List<CellData>> grid = new ArrayList<>();

        // 헤더: 열별 데이터 종류를 결정하는 위치 기준점
        grid.add(List.of(
                createCell("번호", 10, 10, 50, 20),
                createCell("채권자명", 70, 10, 150, 20),
                createCell("원금", 230, 10, 100, 20),
                createCell("이자", 340, 10, 100, 20)));

        // 데이터: 헤더 좌표와 비교하여 필드가 할당될 텍스트
        grid.add(List.of(
                createCell("1", 10, 40, 50, 20),
                createCell("주식회사 테스트", 70, 40, 150, 20),
                createCell("1,500,000", 230, 40, 100, 20),
                createCell("50,200", 340, 40, 100, 20)));

        TableData table = TableData.builder().cellGrid(grid).build();
        PageTable page = PageTable.builder()
                .pageNumber(1)
                .tables(List.of(table))
                .build();

        // when: 파싱 실행
        List<ExtractedData> results = debtListParser.parse(List.of(page));

        // then: 좌표 기반 필드 매핑 결과 확인
        assertThat(results).hasSize(1);
        DebtListRecord record = (DebtListRecord) results.get(0);
        assertThat(record.getCreditorNo().getValue()).isEqualTo("1");
        assertThat(record.getCreditorName().getValue()).isEqualTo("주식회사 테스트");
        assertThat(record.getPrincipal().getValue()).isEqualTo("1,500,000");
        assertThat(record.getInterest().getValue()).isEqualTo("50,200");
    }

    @Test
    @DisplayName("한 셀에 포함된 번호와 채권자명을 정규식으로 분리하여 각각 저장한다.")
    void extractCombinedIndexAndName() {
        // given: '번호'와 '성명'이 결합된 형태의 셀 데이터
        List<List<CellData>> grid = new ArrayList<>();

        grid.add(List.of(
                createCell("채권자명", 50, 10, 200, 20),
                createCell("원금", 260, 10, 100, 20)));

        grid.add(List.of(
                createCell("2-1 (주)테스트", 50, 40, 200, 20),
                createCell("2,000,000", 260, 40, 100, 20)));

        TableData table = TableData.builder().cellGrid(grid).build();
        PageTable page = PageTable.builder().pageNumber(1).tables(List.of(table)).build();

        // when: 파싱 실행
        List<ExtractedData> results = debtListParser.parse(List.of(page));

        // then: 텍스트 분리 및 개별 필드 저장 확인
        DebtListRecord record = (DebtListRecord) results.get(0);
        assertThat(record.getCreditorNo().getValue()).isEqualTo("2-1");
        assertThat(record.getCreditorName().getValue()).contains("(주)테스트");
        assertThat(record.getPrincipal().getValue()).isEqualTo("2,000,000");
    }

    @Test
    @DisplayName("좌표 정보가 없는 경우 셀이 나타나는 순서에 따라 데이터를 추출한다.")
    void parseByCellOrderWhenCoordinatesAreMissing() {
        // given: 좌표 정보 없이 텍스트만 존재하는 표 데이터
        List<List<CellData>> grid = new ArrayList<>();

        grid.add(List.of(
                CellData.builder().text("번호").build(),
                CellData.builder().text("채권자명").build(),
                CellData.builder().text("원금").build()));

        grid.add(List.of(
                CellData.builder().text("3").build(),
                CellData.builder().text("홍길동").build(),
                CellData.builder().text("3,500,000").build()));

        TableData table = TableData.builder().cellGrid(grid).build();
        PageTable page = PageTable.builder().pageNumber(1).tables(List.of(table)).build();

        // when: 파싱 실행
        List<ExtractedData> results = debtListParser.parse(List.of(page));

        // then: 셀 순서 기반 데이터 추출 결과 확인
        DebtListRecord record = (DebtListRecord) results.get(0);
        assertThat(record.getCreditorNo().getValue()).isEqualTo("3");
        assertThat(record.getCreditorName().getValue()).isEqualTo("홍길동");
        assertThat(record.getPrincipal().getValue()).isEqualTo("3,500,000");
    }

    /**
     * 특정 텍스트와 좌표 정보를 가진 셀 데이터를 생성합니다.
     */
    private CellData createCell(String text, int x, int y, int w, int h) {
        return CellData.builder()
                .text(text)
                .box(CellBox.builder().x(x).y(y).width(w).height(h).build())
                .build();
    }
}
