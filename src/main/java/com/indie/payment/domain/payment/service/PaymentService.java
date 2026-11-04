package com.indie.payment.domain.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indie.payment.domain.payment.client.PaymentGateway;
import com.indie.payment.domain.payment.dto.response.PaymentPrepared;
import com.indie.payment.domain.payment.dto.response.PaymentView;
import com.indie.payment.domain.payment.entity.PaymentEntity;
import com.indie.payment.domain.payment.exception.PaymentErrorCode;
import com.indie.payment.domain.payment.repository.PaymentRepository;
import com.indie.payment.global.common.exception.CommonErrorCode;
import com.indie.payment.global.common.exception.GeneralException;
import com.indie.payment.global.common.response.CursorPage;
import com.indie.payment.global.common.util.Cursor;
import com.indie.payment.global.config.PaymentProperties;
import com.indie.payment.global.event.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 결제 — 준비(prepare) · 승인(confirm) · 취소(cancel) · 조회. PG 호출만 빼고 전부 여기 있습니다.
 * <pre>
 * prepare   PENDING 생성 · PG 주문번호 발급        (호출자가 서버에서 계산한 금액을 저장)
 * confirm   ★ 저장된 금액과 대조 → 토스 승인/거절   → payment.completed | payment.failed
 * cancel    PENDING 무효화 · APPROVED 환불          → payment.cancelled
 * </pre>
 * 🔴 Payment 는 prepare 금액을 그대로 믿습니다. 금액이 맞는지(상품 가격 × 수량)는 호출자(Catalog · Concert)의 책임입니다.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    /** Concert 의 orderType — Payment 는 주문 종류를 해석하지 않지만 P1 · P4 만 예외입니다 */
    private static final String TICKET = "TICKET";

    private final PaymentRepository repository;
    private final PaymentGateway gateway;
    private final EventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final Duration ttl;
    private final Clock clock;

    public PaymentService(PaymentRepository repository, PaymentGateway gateway, EventPublisher eventPublisher,
                          ObjectMapper objectMapper, TransactionTemplate tx, PaymentProperties props, Clock clock) {
        this.repository = repository;
        this.gateway = gateway;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.tx = tx;
        this.ttl = Duration.ofMinutes(props.payment().ttlMinutes());
        this.clock = clock;
    }

    /**
     * 결제 준비 — 같은 주문으로 다시 부르면 기존 결과를 그대로 돌려줍니다 (호출자의 네트워크 재시도 대비).
     * 조회-삽입 사이 경합은 idempotency_key UNIQUE 가 최종 방어선 — 충돌하면 조회로 전환합니다 (11 §6).
     */
    public PaymentPrepared prepare(PrepareCommand cmd) {
        UUID key = idempotencyKey(cmd.orderType(), cmd.orderRef());
        try {
            return tx.execute(s -> repository.findByIdempotencyKey(key)
                    .map(existing -> sameRequest(existing, cmd))
                    .orElseGet(() -> {
                        PaymentEntity p = repository.save(new PaymentEntity(key, pgOrderId(cmd.orderType()), cmd.orderType(),
                                cmd.orderRef(), cmd.userId(), cmd.amount(), metadata(cmd.albumIds()), OffsetDateTime.now(clock)));
                        return prepared(p);
                    }));
        } catch (DataIntegrityViolationException e) {
            log.info("prepare_race orderType={} orderRef={}", cmd.orderType(), cmd.orderRef());
            return tx.execute(s -> sameRequest(repository.findByIdempotencyKey(key).orElseThrow(), cmd));
        }
    }

    /**
     * 결제 승인 (P1) — 토스 successUrl 의 paymentKey · orderId(= pgOrderId) · amount 그대로.
     * ★ prepare 때 저장한 금액과 대조. 프론트가 금액을 조작해도 여기서 막힙니다. 이미 승인된 건은 409 ALREADY_CONFIRMED (두 번 결제되지 않음).
     * 티켓은 받지 않습니다 (10/27) — 좌석 경합에서 Concert 가 좌석을 먼저 확보한 뒤에만 승인해야 해서 N16 → 내부 승인으로만
     */
    @Transactional
    public PaymentView confirm(long userId, String paymentKey, String pgOrderId, int amount, String forceDecline) {
        PaymentEntity p = owned(repository.lockByPgOrderId(pgOrderId), userId);
        if (TICKET.equals(p.getOrderType())) {
            throw new GeneralException(PaymentErrorCode.USE_BOOKING_CONFIRM);
        }
        return approve(p, paymentKey, amount, forceDecline);
    }

    /** 내부 승인 — Concert 가 좌석을 확보한 뒤 부릅니다 (N16). 검증은 P1 과 같습니다 */
    @Transactional
    public PaymentView confirmByOrder(String orderType, String orderRef, long userId, String paymentKey, int amount,
                                      String forceDecline) {
        return approve(owned(repository.lockByOrder(orderType, orderRef), userId), paymentKey, amount, forceDecline);
    }

    private static PaymentEntity owned(Optional<PaymentEntity> found, long userId) {
        return found.filter(p -> p.getUserId() == userId).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND));
    }

    /** 🔴 토스에는 클라이언트 금액이 아니라 저장한 금액을 보냅니다 — 금액 대조를 통과한 뒤에도 */
    private PaymentView approve(PaymentEntity p, String paymentKey, int amount, String forceDecline) {
        if (p.getAmount() != amount) {
            log.warn("amount_mismatch paymentId={} expected={} actual={}", p.getId(), p.getAmount(), amount);
            throw new GeneralException(PaymentErrorCode.AMOUNT_MISMATCH);
        }
        switch (p.getStatus()) {
            case APPROVED -> throw new GeneralException(PaymentErrorCode.ALREADY_CONFIRMED);
            case DECLINED, CANCELLED -> throw new GeneralException(PaymentErrorCode.PAYMENT_NOT_PENDING);
            case PENDING -> { }
        }
        if (OffsetDateTime.now(clock).isAfter(expiresAt(p))) {
            throw new GeneralException(PaymentErrorCode.PAYMENT_EXPIRED);
        }

        PaymentGateway.ConfirmResult result = gateway.confirm(paymentKey, p.getPgOrderId(), p.getAmount(), forceDecline);
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (result.approved()) {
            p.approve(paymentKey, result.method(), now);
            Map<String, Object> payload = payload(p);
            payload.putAll(readMetadata(p.getMetadata()));            // albumIds — 해석하지 않고 그대로 전달
            eventPublisher.publish("payment.completed", payload);
        } else {
            p.decline(result.declineReason(), now);
            Map<String, Object> payload = payload(p);
            payload.put("reason", p.getDeclineReason());
            eventPublisher.publish("payment.failed", payload);
        }
        return view(p);
    }

    /** 내부 취소 — Catalog 의 주문 취소(refundApproved=true) · 만료(false) */
    @Transactional
    public String cancelByOrder(String orderType, String orderRef, String reason, boolean refundApproved) {
        PaymentEntity p = repository.lockByOrder(orderType, orderRef).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND));
        return cancel(p, reason, refundApproved).name();
    }

    /**
     * P4 — 사용자 결제 취소 · 환불. 결과는 payment.cancelled 로 주문 서비스에 전달됩니다.
     * TICKET 은 받지 않습니다 (10/24) — 공연 24시간 전 규칙(FR-CON-14)을 Concert 예매 취소(N13)에서만 지키도록
     */
    @Transactional
    public PaymentView cancelByUser(long userId, long paymentId, String reason) {
        PaymentEntity p = repository.lockByIdAndUserId(paymentId, userId).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND));
        if (TICKET.equals(p.getOrderType())) {
            throw new GeneralException(PaymentErrorCode.USE_BOOKING_CANCEL);
        }
        if (p.getStatus() == PaymentEntity.Status.DECLINED) {
            throw new GeneralException(PaymentErrorCode.PAYMENT_NOT_CANCELLABLE);
        }
        cancel(p, reason == null ? "USER_REQUEST" : reason, true);
        return view(p);
    }

    @Transactional(readOnly = true)
    public PaymentView get(long userId, long paymentId) {
        return view(repository.findByIdAndUserId(paymentId, userId).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND)));
    }

    @Transactional(readOnly = true)
    public PaymentView getByOrder(String orderType, String orderRef) {
        return view(repository.findByOrderTypeAndOrderRef(orderType, orderRef).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND)));
    }

    @Transactional(readOnly = true)
    public CursorPage<PaymentView> list(long userId, Cursor cursor, int size) {
        Limit limit = Limit.of(size + 1);
        List<PaymentEntity> rows = cursor == null
                ? repository.findByUserIdOrderByIdDesc(userId, limit)
                : repository.findByUserIdAndIdLessThanOrderByIdDesc(userId, cursor.id(), limit);
        return CursorPage.of(rows.stream().map(this::view).toList(), size, v -> new Cursor(null, v.paymentId()));
    }

    /**
     * PENDING → 무효화 · APPROVED → (refundApproved 일 때만) PG 환불. 이미 끝난 건은 그대로 — 멱등.
     * refundApproved=false 는 만료 처리용: 만료 직전에 결제된 건을 환불하지 않고 APPROVED 를 돌려줍니다.
     */
    private PaymentEntity.Status cancel(PaymentEntity p, String reason, boolean refundApproved) {
        switch (p.getStatus()) {
            case PENDING -> { }
            case APPROVED -> {
                if (!refundApproved) return p.getStatus();
                gateway.cancel(p.getPaymentKey(), reason);
            }
            case DECLINED, CANCELLED -> {
                return p.getStatus();
            }
        }
        boolean refunded = p.getStatus() == PaymentEntity.Status.APPROVED;
        p.cancel(reason, OffsetDateTime.now(clock));
        Map<String, Object> payload = payload(p);
        payload.put("reason", p.getDeclineReason());
        payload.put("refunded", refunded);
        eventPublisher.publish("payment.cancelled", payload);
        return p.getStatus();
    }

    private PaymentPrepared sameRequest(PaymentEntity existing, PrepareCommand cmd) {
        if (!Objects.equals(existing.getUserId(), cmd.userId())) {
            throw new GeneralException(PaymentErrorCode.IDEMPOTENCY_USER_MISMATCH);
        }
        if (existing.getAmount() != cmd.amount()) {
            throw new GeneralException(PaymentErrorCode.IDEMPOTENCY_AMOUNT_MISMATCH);
        }
        return prepared(existing);
    }

    private PaymentPrepared prepared(PaymentEntity p) {
        return new PaymentPrepared(p.getPgOrderId(), p.getStatus().name(), expiresAt(p));
    }

    private OffsetDateTime expiresAt(PaymentEntity p) {
        return p.getRequestedAt().plus(ttl);
    }

    /**
     * 토스 orderId — 6~64자 · 영숫자 - _ 만 · 상점 안에서 유일. 주문 번호만 쓰면 로컬 DB 를 초기화한 뒤 같은 번호가 다시 나와
     * 테스트 상점이 중복으로 거절하므로 무작위 값을 붙입니다 (GOODS-1b4e… 43자 이하)
     */
    private static String pgOrderId(String orderType) {
        return orderType + "-" + UUID.randomUUID();
    }

    /** (주문 종류, 주문 번호) → 항상 같은 UUID. 같은 주문의 두 번째 prepare 가 UNIQUE 에 걸립니다 */
    static UUID idempotencyKey(String orderType, String orderRef) {
        return UUID.nameUUIDFromBytes((orderType + ":" + orderRef).getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> payload(PaymentEntity p) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentId", p.getId());
        payload.put("orderType", p.getOrderType());
        payload.put("orderRef", p.getOrderRef());
        payload.put("userId", p.getUserId());
        payload.put("amount", p.getAmount());
        payload.put("status", p.getStatus().name());
        return payload;
    }

    private String metadata(List<Long> albumIds) {
        if (albumIds == null || albumIds.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(Map.of("albumIds", albumIds));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("metadata serialization failed", e);
        }
    }

    private Map<String, Object> readMetadata(String json) {
        if (json == null) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored metadata is not JSON", e);
        }
    }

    private PaymentView view(PaymentEntity p) {
        return new PaymentView(p.getId(), p.getOrderType(), p.getOrderRef(), p.getAmount(), p.getStatus().name(),
                p.getMethod(), p.getDeclineReason(), p.getRequestedAt(), p.getCompletedAt());
    }

    public record PrepareCommand(String orderType, String orderRef, long userId, int amount, List<Long> albumIds) {}
}
