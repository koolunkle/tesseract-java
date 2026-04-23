package com.softgram.ecfs.ocr.adapter.in.web;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.softgram.ecfs.ocr.application.port.in.JobUseCase;
import com.softgram.ecfs.ocr.application.port.in.ProcessingUseCase;
import com.softgram.ecfs.ocr.common.constant.ProcessingConstants;
import com.softgram.ecfs.ocr.common.exception.ProcessingException;
import com.softgram.ecfs.ocr.domain.model.job.EventConsumer;
import com.softgram.ecfs.ocr.domain.model.job.FileSource;
import com.softgram.ecfs.ocr.domain.model.job.JobResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * OCR 작업의 생명주기를 관리하고 상태 조회 및 SSE 통지를 담당하는 어댑터.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping(JobController.BASE_URL)
@Tag(name = "OCR Job API", description = "문서 분석 작업 생성, 상태 조회 및 실시간 알림 관리")
public class JobController {

    public static final String BASE_URL = "/api/ocr/jobs";

    private final ProcessingUseCase processingUseCase;
    private final JobUseCase<JobResult> jobUseCase;

    /**
     * 비동기 데이터 추출 작업을 생성합니다.
     */
    @Operation(summary = "데이터 추출 작업 등록", description = "문서에서 텍스트 및 표 데이터를 추출하는 비동기 작업을 시작합니다.")
    @PostMapping(value = "/data", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<List<JobResult>> createDataExtractionJobs(
            @Parameter(description = "분석할 문서 파일 목록", required = true)
            @RequestParam("files") List<MultipartFile> files
    ) {
        return register(files, ProcessingConstants.OPERATION_ANALYZE_DATA);
    }

    /**
     * 이미지 품질 진단 작업을 생성합니다.
     */
    @Operation(summary = "품질 진단 작업 등록", description = "이미지 전처리 수준 및 가독성 상태를 진단하는 비동기 작업을 시작합니다.")
    @PostMapping(value = "/quality", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<List<JobResult>> createQualityCheckJobs(
            @Parameter(description = "진단할 문서 파일 목록", required = true)
            @RequestParam("files") List<MultipartFile> files
    ) {
        return register(files, ProcessingConstants.OPERATION_ANALYZE_QUALITY);
    }

    /**
     * 문서 구조 분석 작업을 생성합니다.
     */
    @Operation(summary = "구조 분석 작업 등록", description = "문서 내 표의 물리적 윤곽선과 격자 구조를 식별하는 비동기 작업을 시작합니다.")
    @PostMapping(value = "/structure", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<List<JobResult>> createStructureAnalysisJobs(
            @Parameter(description = "분석할 문서 파일 목록", required = true)
            @RequestParam("files") List<MultipartFile> files
    ) {
        return register(files, ProcessingConstants.OPERATION_ANALYZE_CONTOUR);
    }

    /**
     * 전체 작업 목록을 조회합니다.
     */
    @Operation(summary = "작업 목록 조회", description = "현재 등록된 모든 분석 작업의 요약 목록을 반환합니다.")
    @GetMapping
    public ResponseEntity<List<JobResult>> getJobs() {
        return ResponseEntity.ok(jobUseCase.getAllJobs());
    }

    /**
     * 특정 작업의 상세 결과와 상태를 조회합니다.
     */
    @Operation(summary = "작업 상세 조회", description = "ID를 통해 특정 작업의 진행 상태 및 추출된 최종 결과물을 확인합니다.")
    @GetMapping("/{jobId}")
    public ResponseEntity<JobResult> getJob(@PathVariable String jobId) {
        return ResponseEntity.of(Optional.ofNullable(jobUseCase.getJob(jobId)));
    }

    /**
     * 작업을 취소하고 관련 자원을 삭제합니다.
     */
    @Operation(summary = "작업 취소 및 삭제", description = "진행 중인 작업을 중단하고 서버의 모든 관련 임시 리소스를 제거합니다.")
    @DeleteMapping("/{jobId}")
    public ResponseEntity<Void> deleteJob(@PathVariable String jobId) {
        jobUseCase.deleteJob(jobId);
        return ResponseEntity.noContent().build();
    }

    /**
     * SSE 채널을 통해 실시간 상태를 구독합니다.
     */
    @Operation(summary = "실시간 상태 구독", description = "SSE 채널을 통해 작업의 진행률 및 상태 변화를 실시간으로 수신합니다.")
    @GetMapping(value = "/{jobId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@PathVariable String jobId) {
        SseEmitter emitter = new SseEmitter(60 * 60 * 1000L); 
        jobUseCase.subscribe(jobId, createEventConsumer(emitter));
        return emitter;
    }

    /**
     * 분석 요청을 수신하고 작업을 등록합니다.
     */
    private ResponseEntity<List<JobResult>> register(List<MultipartFile> files, String operation) {
        log.info("Received analysis request: operation={}, fileCount={}", operation, files.size());

        List<FileSource> sources = files.stream()
                .filter(f -> !f.isEmpty())
                .map(this::toFileSource)
                .collect(Collectors.toList());

        List<JobResult> results = processingUseCase.registerJobs(sources, operation);
        return ResponseEntity.status(HttpStatus.CREATED).body(results);
    }

    /**
     * MultipartFile을 도메인 모델인 FileSource로 변환합니다.
     */
    private FileSource toFileSource(MultipartFile file) {
        try {
            return new FileSource(
                    file.getOriginalFilename(),
                    file.getInputStream(),
                    file.getSize(),
                    file.getContentType()
            );
        } catch (IOException e) {
            throw new ProcessingException("FILE_READ_ERROR", "업로드 파일 읽기 실패: " + file.getOriginalFilename(), e);
        }
    }

    /**
     * SSE 이벤트를 처리하는 소비자를 생성합니다.
     */
    private EventConsumer<JobResult> createEventConsumer(SseEmitter emitter) {
        return new EventConsumer<JobResult>() {
            @Override
            public void onEvent(String name, JobResult data) {
                try {
                    emitter.send(SseEmitter.event().name(name).data(data));
                } catch (IOException ignored) {}
            }

            @Override
            public void onComplete() {
                emitter.complete();
            }

            @Override
            public void onError(Throwable t) {
                emitter.completeWithError(t);
            }

            @Override
            public void onCancel(Runnable callback) {
                emitter.onCompletion(callback);
                emitter.onTimeout(callback);
                emitter.onError(e -> callback.run());
            }
        };
    }
}
