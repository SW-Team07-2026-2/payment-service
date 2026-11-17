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
 * 환불 복구 (11/17) — 환불 요청(cancel_requested_at)이 남은 결제를 처음 사유 그대로 이어서 끝냅니다.
 * 토스 환불 뒤 기록 전에 파드가 죽었거나, 토스가 불통 · 처리 중이었던 경우입니다. 토스 취소는 결제마다 같은 멱등키라 다시 불러도 한 번만 환불됩니다.
 * 요청할 때마다 시각을 새로 찍으므로 진행 중인 요청(토스 최악 26초)은 cancel-retry-after(60초) 동안 건드리지 않고, 실패한 건도 그만큼 쉬었다가 다시 합니다.
 * replica 가 여럿이면 같은 건을 함께 집을 수 있지만 행 잠금 · 토스 멱등키 · ③ 재확인으로 한 번만 기록됩니다.
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
