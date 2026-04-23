package com.softgram.ecfs.ocr.application.port.in;

import java.util.List;

import com.softgram.ecfs.ocr.domain.model.job.FileSource;
import com.softgram.ecfs.ocr.domain.model.job.JobResult;

/**
 * 분석 대상 파일 등록 및 프로세스 실행을 위한 입력(Inbound) 포트.
 */
public interface ProcessingUseCase {
    
    /**
     * 전송된 파일 소스들을 받아 비동기 처리 공정에 등록합니다.
     */
    List<JobResult> registerJobs(List<FileSource> files, String operation);
}
