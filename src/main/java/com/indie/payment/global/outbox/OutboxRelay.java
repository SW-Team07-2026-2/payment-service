package com.indie.payment.global.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.indie.payment.global.config.PaymentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Outbox → Kafka. 1초 간격으로 미발행 100건을 집어 발행하고 published_at 을 찍습니다.
 * 발행이 실패하면 그 지점에서 멈추고 다음 주기에 다시 시도합니다 (그 앞까지는 발행 완료로 커밋).
 * 봉투 — { eventId, type, version, occurredAt, payload } · 파티션 키 — payload.orderRef (같은 주문의 승인 · 취소 순서 보장)
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final Clock clock;

    public OutboxRelay(OutboxRepository repository, KafkaTemplate<String, String> kafkaTemplate,
                       ObjectMapper objectMapper, PaymentProperties props, Clock clock) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.batchSize = props.outbox().batchSize();
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.outbox.relay-interval-ms}")
    @Transactional
    public void relay() {
        List<OutboxEntity> batch = repository.lockUnpublished(batchSize);
        for (OutboxEntity row : batch) {
            try {
                JsonNode payload = objectMapper.readTree(row.getPayload());
                ObjectNode envelope = objectMapper.createObjectNode()
                        .put("eventId", row.getEventId().toString())
                        .put("type", row.getEventType())
                        .put("version", 1)
                        .put("occurredAt", row.getCreatedAt().toString());
                envelope.set("payload", payload);
                String key = payload.hasNonNull("orderRef") ? payload.get("orderRef").asText() : null;
                kafkaTemplate.send(row.getEventType(), key, objectMapper.writeValueAsString(envelope)).get(5, TimeUnit.SECONDS);
                row.markPublished(OffsetDateTime.now(clock));
            } catch (Exception e) {
                log.warn("outbox_relay_failed eventId={} error={}", row.getEventId(), e.toString());
                return;   // 다음 주기에 재시도 — 앞서 성공한 행은 커밋
            }
        }
    }

    /** 발행 완료 7일 경과 행 정리 — 없으면 테이블이 무한히 커집니다 */
    @Scheduled(cron = "0 0 4 * * SUN")
    @Transactional
    public void purge() {
        int deleted = repository.deletePublishedOlderThanWeek();
        log.info("outbox_purged count={}", deleted);
    }
}
