package com.indie.payment.domain.payment.service;

import com.indie.payment.domain.payment.entity.PaymentEntity;
import com.indie.payment.domain.payment.repository.PaymentRepository;
import com.indie.payment.global.config.PaymentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * 완료되지 않은 환불 요청을 저장된 사유로 재처리합니다.
 * 설정된 대기 시간 뒤에 재시도하며 행 잠금, 결과 재확인, PG 멱등키로 중복 환불을 막습니다.
 */
@Component
public class CancelRecovery {

    private static final Logger log = LoggerFactory.getLogger(CancelRecovery.class);
    private static final int BATCH = 20;

    private final PaymentRepository repository;
    private final PaymentService paymentService;
    private final Duration retryAfter;
    private final Clock clock;

    public CancelRecovery(PaymentRepository repository, PaymentService paymentService, PaymentProperties props, Clock clock) {
        this.repository = repository;
        this.paymentService = paymentService;
        this.retryAfter = Duration.ofSeconds(props.payment().cancelRetryAfterSeconds());
        this.clock = clock;
    }

    /** 대기 시간이 지난 미완료 환불 요청을 다시 처리합니다. */
    @Scheduled(fixedDelayString = "${app.payment.cancel-retry-interval-ms}")
    public void resume() {
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minus(retryAfter);
        for (PaymentEntity p : repository.findByCancelRequestedAtBeforeOrderByCancelRequestedAtAsc(cutoff, Limit.of(BATCH))) {
            try {
                log.warn("cancel_resumed paymentId={} status={}", p.getId(), paymentService.resumeCancel(p.getPgOrderId()));
            } catch (RuntimeException e) {
                log.warn("cancel_resume_failed paymentId={} error={}", p.getId(), e.toString());   // 다음 주기에 다시 (요청 시각이 새로 찍혔으면 60초 뒤)
            }
        }
    }
}
