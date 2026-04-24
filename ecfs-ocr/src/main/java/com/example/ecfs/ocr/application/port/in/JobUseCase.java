package com.example.ecfs.ocr.application.port.in;

import java.util.List;

import com.example.ecfs.ocr.domain.model.job.EventConsumer;
import com.example.ecfs.ocr.domain.model.job.JobResult;

/**
 * 작업 상태 조회, 목록 관리 및 실시간 구독을 위한 입력(Inbound) 포트.
 */
public interface JobUseCase<T> {
    
    /**
     * 전체 작업 목록 정보를 수집합니다.
     */
    List<JobResult> getAllJobs();

    /**
     * 특정 식별자에 해당하는 작업 결과를 조회합니다.
     */
    JobResult getJob(String jobId);

    /**
     * 이벤트 소비자를 등록하여 작업 진행 상황을 실시간으로 구독합니다.
     */
    void subscribe(String jobId, EventConsumer<T> consumer);

    /**
     * 작업 이력 및 관련 리소스를 물리적으로 삭제합니다.
     */
    void deleteJob(String jobId);
}
