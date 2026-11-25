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
 * 결제 대기 만료 정리 (11/25) — 유효 시간(ttl)이 지났는데 승인 요청을 한 번도 받지 않은 결제 대기(PENDING)를 무효화합니다.
 * 보통은 주문 쪽(Concert 선점 만료 · Catalog 주문 만료)이 먼저 취소를 부릅니다. 그런데 결제 준비 응답이 호출자의 읽기 시간(3초)을 넘기면
 * 호출자는 준비가 실패한 줄 알고 주문만 닫고, 여기에는 결제 대기가 남습니다 (k6 11/24 28건 · 11/25 WSL 멈춤 회차 1,585건).
 * 유효 시간이 지나면 승인(request)이 PAYMENT_EXPIRED 로 막히므로 새 승인 요청과 겹치지 않고, 주문 쪽 만료가 먼저 돌도록 grace 만큼 더 기다립니다.
 * 승인 요청을 보낸 결제(결과를 모르는 승인)는 집지 않습니다 — Concert 의 결제 중 정리 · 토스 조회(84A)가 맡습니다.
 * 취소 경로를 그대로 써서 payment.cancelled(refunded=false) 를 냅니다 — Concert · Catalog 는 이미 닫힌 주문이면 아무것도 하지 않습니다.
 * replica 가 여럿이면 같은 건을 함께 집을 수 있지만 행 잠금 뒤 CANCELLED 를 보고 그대로 끝납니다 (이벤트 한 번).
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
