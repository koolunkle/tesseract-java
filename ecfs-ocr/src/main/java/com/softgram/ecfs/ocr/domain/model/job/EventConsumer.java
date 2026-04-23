package com.softgram.ecfs.ocr.domain.model.job;

/**
 * 특정 기술(Web, Messaging 등)에 의존하지 않는 독립된 이벤트 수신자 인터페이스.
 */
public interface EventConsumer<T> {

    /** 신규 이벤트를 구독자에게 전달합니다. */
    void onEvent(String name, T data);

    /** 모든 이벤트 전송이 종료되었음을 알립니다. */
    void onComplete();

    /** 처리 중 발생한 예외 상황을 알리고 중단합니다. */
    void onError(Throwable t);

    /** 수신자의 구독 해제 시 실행할 사후 처리를 등록합니다. */
    void onCancel(Runnable callback);
}
