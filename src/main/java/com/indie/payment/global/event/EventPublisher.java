package com.indie.payment.global.event;

import java.util.Map;

/**
 * 도메인 이벤트 발행 포트 — 구현은 global.outbox.OutboxAppender (같은 트랜잭션의 outbox 테이블 → 릴레이 → Kafka).
 * 🔴 업무 데이터를 바꾸는 트랜잭션 안에서만 부릅니다. 밖에서 부르면 구현이 예외를 던집니다.
 */
public interface EventPublisher {

    void publish(String topic, Map<String, Object> payload);
}
