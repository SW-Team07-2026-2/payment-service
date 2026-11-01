package com.indie.payment.global.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indie.payment.global.event.EventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * 이벤트를 업무 데이터와 <b>같은 트랜잭션</b>에 INSERT 합니다 — DB 커밋은 됐는데 Kafka 발행이 실패해
 * 데이터와 이벤트가 어긋나는 일이 없습니다. 트랜잭션 밖에서 부르면 예외 (MANDATORY).
 */
@Component
public class OutboxAppender implements EventPublisher {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public OutboxAppender(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String eventType, Map<String, Object> payload) {
        try {
            jdbc.update("INSERT INTO outbox (event_id, event_type, payload) VALUES (?, ?, ?::jsonb)",
                    UUID.randomUUID(), eventType, objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("outbox payload serialization failed", e);
        }
    }
}
