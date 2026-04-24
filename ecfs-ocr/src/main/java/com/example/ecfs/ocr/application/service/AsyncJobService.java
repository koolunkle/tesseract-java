package com.example.ecfs.ocr.application.service;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import com.example.ecfs.ocr.application.port.in.JobUseCase;
import com.example.ecfs.ocr.application.port.in.ProcessingUseCase;
import com.example.ecfs.ocr.application.port.out.EnginePort;
import com.example.ecfs.ocr.common.constant.ProcessingConstants;
import com.example.ecfs.ocr.common.exception.ProcessingException;
import com.example.ecfs.ocr.domain.model.job.EventConsumer;
import com.example.ecfs.ocr.domain.model.job.FileSource;
import com.example.ecfs.ocr.domain.model.job.JobResult;
import com.example.ecfs.ocr.domain.model.job.JobResult.OcrJobStatus;
import com.example.ecfs.ocr.domain.model.analysis.AnalysisPayload;
import com.example.ecfs.ocr.domain.model.analysis.SectionRange;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 비동기 OCR 작업의 생명주기를 관리하고 상태 전이를 제어하는 서비스.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncJobService implements ProcessingUseCase, JobUseCase<JobResult> {

    /** 비동기 작업을 실행하는 스레드 풀 */
    private final Executor ocrExecutor;

    /** 분석 공정을 수행하는 엔진 */
    private final EnginePort ocrEnginePort;

    /** 작업 정보 저장소 (In-memory) */
    private final Map<String, JobResult> jobStore = new ConcurrentHashMap<>();

    /** 실시간 이벤트 소비자 관리 맵 */
    private final Map<String, EventConsumer<JobResult>> eventConsumers = new ConcurrentHashMap<>();

    /** 실행 중인 작업의 제어권 관리 맵 */
    private final Map<String, JobExecution> jobExecutions = new ConcurrentHashMap<>();

    /**
     * 실행 중인 작업의 제어 수단 묶음.
     */
    private record JobExecution(Future<?> future, Thread thread) {
        public JobExecution(Future<?> future) {
            this(future, null);
        }

        public JobExecution withThread(Thread thread) {
            return new JobExecution(this.future, thread);
        }
    }

    /**
     * 종료 시 진행 중인 작업을 모두 중단합니다.
     */
    @PreDestroy
    public void onShutdown() {
        if (jobExecutions.isEmpty()) {
            return;
        }

        log.info("Server shutting down, Cancelling {} active OCR jobs...", jobExecutions.size());
        jobExecutions.forEach((jobId, exec) -> {
            if (!exec.future().isDone()) {
                log.warn("Force cancelling job on shutdown: {}", jobId);
                exec.future().cancel(true);
                if (exec.thread() != null) {
                    exec.thread().interrupt();
                }
            }
        });
    }

    /**
     * 신규 비동기 분석 작업을 등록합니다.
     */
    @Override
    public List<JobResult> registerJobs(List<FileSource> files, String operation) {
        List<JobResult> registeredJobs = new ArrayList<>();

        for (FileSource fileSource : files) {
            String jobId = java.util.UUID.randomUUID().toString();
            String originalFileName = fileSource.fileName();

            File tempFile = saveTempFile(fileSource, jobId, originalFileName);

            JobResult jobStatus = registerJob(jobId, originalFileName, operation);
            registeredJobs.add(jobStatus);

            processAsync(jobId, tempFile, operation);
        }

        return registeredJobs;
    }

    /**
     * 입력 스트림을 임시 파일로 저장합니다.
     */
    private File saveTempFile(FileSource fileSource, String jobId, String originalFileName) {
        try {
            String tempFileName = jobId + "_" + originalFileName;
            File tempFile = new File(System.getProperty("java.io.tmpdir"), tempFileName);
            Files.copy(fileSource.inputStream(), tempFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return tempFile;
        } catch (IOException e) {
            throw new ProcessingException("FILE_SAVE_ERROR", "Failed to save temporary file: " + originalFileName, e);
        } finally {
            try {
                fileSource.inputStream().close();
            } catch (IOException ignored) {}
        }
    }

    /**
     * 전체 작업 목록을 반환합니다.
     */
    @Override
    public List<JobResult> getAllJobs() {
        return new ArrayList<>(jobStore.values()).stream()
                .sorted((a, b) -> b.submittedAt().compareTo(a.submittedAt()))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 특정 작업을 조회합니다.
     */
    @Override
    public JobResult getJob(String jobId) {
        JobResult result = jobStore.get(jobId);
        if (result == null) {
            throw new ProcessingException("JOB_NOT_FOUND", "Job not found: " + jobId);
        }
        return result;
    }

    /**
     * 실시간 알림 채널을 활성화합니다.
     */
    @Override
    public void subscribe(String jobId, EventConsumer<JobResult> consumer) {
        if (!jobStore.containsKey(jobId)) {
            throw new ProcessingException("JOB_NOT_FOUND", "Job not found for subscription: " + jobId);
        }

        eventConsumers.put(jobId, consumer);

        consumer.onCancel(() -> {
            eventConsumers.remove(jobId);
            log.debug("Consumer disconnected: jobId={}", jobId);
        });

        JobResult current = jobStore.get(jobId);
        if (current != null) {
            notifySubscriber(jobId, current, "connect");
        }
    }

    /**
     * 작업을 취소하고 관련 리소스를 제거합니다.
     */
    @Override
    public void deleteJob(String jobId) {
        cancelJob(jobId);
        jobStore.remove(jobId);
        cleanupJobArtifacts(jobId);

        EventConsumer<JobResult> consumer = eventConsumers.remove(jobId);
        if (consumer != null) {
            consumer.onComplete();
        }
    }

    /**
     * 작업 정보를 초기화합니다.
     */
    private JobResult registerJob(String jobId, String fileName, String operation) {
        JobResult initialJob = JobResult.created(jobId, fileName, operation);
        jobStore.put(jobId, initialJob);
        return initialJob;
    }

    /**
     * 분석 프로세스를 비동기로 실행합니다.
     */
    private void processAsync(String jobId, File tempFile, String operation) {
        JobResult currentJob = jobStore.get(jobId);

        Set<Integer> alreadyProcessed = currentJob != null ? currentJob.processedPages() : new HashSet<>();
        AnalysisPayload previousResult = currentJob != null ? currentJob.resultData() : null;
        List<SectionRange> existingRanges = currentJob != null ? currentJob.sectionRanges() : null;

        updateStatus(jobId, OcrJobStatus.PROCESSING, null);

        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            jobExecutions.computeIfPresent(jobId, (id, exec) -> exec.withThread(Thread.currentThread()));

            try {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Job cancelled by user");
                }

                String originalFileName = (currentJob != null) ? currentJob.fileName() : tempFile.getName();
                Consumer<AnalysisPayload> progressCallback = (partialData) -> updateProgress(jobId, partialData);

                EnginePort.OcrEngineRequest request = new EnginePort.OcrEngineRequest(
                        tempFile, originalFileName, jobId, alreadyProcessed,
                        previousResult, existingRanges, progressCallback
                );

                AnalysisPayload data;
                if (ProcessingConstants.OPERATION_ANALYZE_QUALITY.equals(operation)) {
                    data = ocrEnginePort.analyzeQuality(request);
                } else if (ProcessingConstants.OPERATION_ANALYZE_CONTOUR.equals(operation)) {
                    data = ocrEnginePort.analyzeStructure(request);
                } else {
                    data = ocrEnginePort.extractData(request);
                }

                completeJob(jobId, data);
            } catch (Throwable t) {
                handleAsyncFailure(jobId, t);
            }

            jobExecutions.remove(jobId);
            deleteFileSilently(tempFile);

        }, ocrExecutor);

        jobExecutions.put(jobId, new JobExecution(future));
    }

    /**
     * 비동기 작업 실패를 처리합니다.
     */
    private void handleAsyncFailure(String jobId, Throwable e) {
        if (e instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
            log.info("Job {} was cancelled.", jobId);
            failJob(jobId, "Cancelled by user");
        } else {
            String errorCode = (e instanceof ProcessingException oe) ? oe.getErrorCode() : "SYSTEM_ERROR";
            log.error("Async OCR Job Failed: jobId={}, code={}, message={}", jobId, errorCode, e.getMessage());
            failJob(jobId, e.getMessage());
        }
    }

    /**
     * 예외 발생 시에도 중단 없이 대상 파일을 물리적으로 삭제합니다.
     */
    private void deleteFileSilently(File file) {
        if (file != null && file.exists()) {
            try {
                Files.delete(file.toPath());
            } catch (IOException e) {
                log.warn("Failed to delete temp file: {}", file.getAbsolutePath());
            }
        }
    }

    /**
     * 분석 도중 생성된 중간 결과를 작업 정보에 실시간으로 갱신합니다.
     */
    private void updateProgress(String jobId, AnalysisPayload partialData) {
        jobStore.computeIfPresent(jobId, (id, job) -> {
            List<SectionRange> newRanges = job.sectionRanges();
            AnalysisPayload newResultData = job.resultData();

            // Progress reporting can be improved by adding explicit metadata to AnalysisPayload
            // but for now we update if not null.
            if (partialData != null) {
                newResultData = partialData;
            }

            return new JobResult(
                    job.jobId(), job.fileName(), job.operation(), job.status(),
                    job.submittedAt(), job.completedAt(),
                    newResultData,
                    job.errorMessage(),
                    job.processedPages(),
                    newRanges);
        });
    }

    /**
     * 진행 중인 작업의 Future를 중단하고 실행 중인 스레드에 인터럽트를 발생시킵니다.
     */
    public void cancelJob(String jobId) {
        JobExecution execution = jobExecutions.get(jobId);

        if (execution != null && execution.future() != null && !execution.future().isDone()) {
            log.info("Cancelling job: {}", jobId);
            execution.future().cancel(true);

            if (execution.thread() != null) {
                log.info("Interrupting thread for job: {}", jobId);
                execution.thread().interrupt();
            }

            failJob(jobId, "Cancelled by user");
        }
    }

    /**
     * 작업 수행 과정에서 생성된 모든 임시 파일 및 결과물을 삭제합니다.
     */
    private void cleanupJobArtifacts(String jobId) {
        try {
            Path tempRoot = Paths.get(System.getProperty("user.dir"), "temp");
            if (Files.exists(tempRoot)) {
                Files.walkFileTree(tempRoot, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        if (dir.getFileName().toString().equals(jobId)) {
                            try {
                                boolean deleted = FileSystemUtils.deleteRecursively(dir);
                                if (deleted) {
                                    log.info("Cleaned up artifact directory: {}", dir);
                                }
                            } catch (IOException e) {
                                log.warn("Failed to delete directory: {}", dir, e);
                            }
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                        if (exc instanceof NoSuchFileException) {
                            return FileVisitResult.CONTINUE;
                        }
                        return super.visitFileFailed(file, exc);
                    }
                });
            }
        } catch (Exception e) {
            log.warn("Failed to cleanup artifacts for job {}", jobId, e);
        }
    }

    /**
     * 작업의 현재 상태(Status)를 갱신하고 구독자들에게 변화를 통지합니다.
     */
    private void updateStatus(String jobId, OcrJobStatus status, String errorMessage) {
        JobResult updatedJob = jobStore.computeIfPresent(jobId, (id, job) -> new JobResult(
                job.jobId(), job.fileName(), job.operation(), status,
                job.submittedAt(),
                (status == OcrJobStatus.COMPLETED || status == OcrJobStatus.FAILED) ? LocalDateTime.now() : null,
                job.resultData(), errorMessage, job.processedPages(), job.sectionRanges()));

        if (updatedJob != null) {
            notifySubscriber(jobId, updatedJob, "update");
        }
    }

    /**
     * 작업이 성공적으로 완료되었음을 확정하고 최종 데이터를 통지합니다.
     */
    private void completeJob(String jobId, AnalysisPayload resultData) {
        JobResult updatedJob = jobStore.computeIfPresent(jobId, (id, job) -> new JobResult(
                job.jobId(), job.fileName(), job.operation(), OcrJobStatus.COMPLETED,
                job.submittedAt(), LocalDateTime.now(), resultData, null,
                job.processedPages(), job.sectionRanges()));

        if (updatedJob != null) {
            notifySubscriber(jobId, updatedJob, "complete");

            EventConsumer<JobResult> consumer = eventConsumers.remove(jobId);
            if (consumer != null) {
                consumer.onComplete();
            }
        }
    }

    /**
     * 작업을 실패로 마감하고 구독을 해제합니다.
     */
    private void failJob(String jobId, String errorMessage) {
        updateStatus(jobId, OcrJobStatus.FAILED, errorMessage);

        EventConsumer<JobResult> consumer = eventConsumers.remove(jobId);
        if (consumer != null) {
            consumer.onComplete();
        }
    }

    /**
     * 등록된 이벤트 소비자를 통해 특정 이벤트를 실시간 채널로 전송합니다.
     */
    private void notifySubscriber(String jobId, @NonNull JobResult data, @NonNull String eventName) {
        EventConsumer<JobResult> consumer = eventConsumers.get(jobId);
        if (consumer != null) {
            try {
                consumer.onEvent(eventName, data);
            } catch (Exception e) {
                eventConsumers.remove(jobId);
                log.debug("Failed to notify event consumer for job: {}", jobId);
            }
        }
    }
}
