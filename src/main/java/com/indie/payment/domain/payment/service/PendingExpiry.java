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
 * 유효 시간과 유예 시간이 지난 미승인 PENDING 결제를 무효화합니다.
 * 승인 요청이 기록된 결제는 제외하고, 기존 취소 경로로 payment.cancelled 이벤트를 발행합니다.
 */
@Component
public class PendingExpiry {

    private static final Logger log = LoggerFactory.getLogger(PendingExpiry.class);
    private static final int BATCH = 100;

    private final PaymentRepository repository;
    private final PaymentService paymentService;
    private final Duration ttlAndGrace;
    private final Clock clock;

    public PendingExpiry(PaymentRepository repository, PaymentService paymentService, PaymentProperties props, Clock clock) {
        this.repository = repository;
        this.paymentService = paymentService;
        this.ttlAndGrace = Duration.ofMinutes(props.payment().ttlMinutes()).plusSeconds(props.payment().expiryGraceSeconds());
        this.clock = clock;
    }

    /** 유효 시간과 유예 시간이 지난 미승인 결제를 무효화합니다. */
    @Scheduled(fixedDelayString = "${app.payment.expiry-interval-ms}")
    public void expire() {
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minus(ttlAndGrace);
        for (PaymentEntity p : repository.findByStatusAndRequestedPaymentKeyIsNullAndRequestedAtBeforeOrderByRequestedAtAsc(
                PaymentEntity.Status.PENDING, cutoff, Limit.of(BATCH))) {
            try {
                log.info("payment_pending_expired paymentId={} orderType={} orderRef={} status={}",
                        p.getId(), p.getOrderType(), p.getOrderRef(), paymentService.expirePending(p.getPgOrderId()));
            } catch (RuntimeException e) {
                log.warn("payment_pending_expire_failed paymentId={} error={}", p.getId(), e.toString());   // 다음 주기에 다시
            }
        }
    }
}
