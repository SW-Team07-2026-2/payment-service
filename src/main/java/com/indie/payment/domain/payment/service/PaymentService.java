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
import java.util.function.Supplier;

/**
 * 결제 — 준비(prepare) · 승인(confirm) · 취소(cancel) · 조회. PG 호출만 빼고 전부 여기 있습니다.
 * <pre>
 * prepare   PENDING 생성 · PG 주문번호 발급        (호출자가 서버에서 계산한 금액을 저장) · 거절된 주문이면 새 결제(새 주문번호)
 * confirm   ★ 저장된 금액과 대조 → 토스 승인/거절   → payment.completed | payment.failed
 * cancel    PENDING 무효화 · APPROVED 환불          → payment.cancelled  (결과를 모르는 승인은 토스에 먼저 물어봄)
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
     * 결제 준비 — 같은 주문으로 다시 부르면 마지막 결제를 그대로 돌려줍니다 (호출자의 네트워크 재시도 대비).
     * 마지막 결제가 거절(DECLINED)이면 새 결제(시도 n+1 · 새 pgOrderId)를 만듭니다 — 선점을 유지한 채 다른 결제수단으로 다시 결제 (11/04 80B).
     * 토스 orderId 는 결제마다 고유해야 하고, 같은 orderId(= 같은 멱등키)로 다시 승인하면 지난 거절이 그대로 돌아옵니다.
     * 조회-삽입 사이 경합은 idempotency_key UNIQUE 가 최종 방어선 — 충돌하면 조회로 전환합니다 (11 §6).
     */
    public PaymentPrepared prepare(PrepareCommand cmd) {
        try {
            return tx.execute(s -> {
                Optional<PaymentEntity> last = repository.findFirstByOrderTypeAndOrderRefOrderByIdDesc(cmd.orderType(), cmd.orderRef());
                if (last.isPresent()) {
                    PaymentPrepared same = sameRequest(last.get(), cmd);
                    if (last.get().getStatus() != PaymentEntity.Status.DECLINED) return same;
                }
                int attempt = last.isEmpty() ? 1 : (int) repository.countByOrderTypeAndOrderRef(cmd.orderType(), cmd.orderRef()) + 1;
                PaymentEntity p = repository.save(new PaymentEntity(idempotencyKey(cmd.orderType(), cmd.orderRef(), attempt),
                        pgOrderId(cmd.orderType()), cmd.orderType(), cmd.orderRef(), cmd.userId(), cmd.amount(),
                        metadata(cmd.albumIds()), OffsetDateTime.now(clock)));
                if (attempt > 1) log.info("payment_retry_prepared orderType={} orderRef={} attempt={}", cmd.orderType(), cmd.orderRef(), attempt);
                return prepared(p);
            });
        } catch (DataIntegrityViolationException e) {
            log.info("prepare_race orderType={} orderRef={}", cmd.orderType(), cmd.orderRef());
            return tx.execute(s -> sameRequest(
                    repository.findFirstByOrderTypeAndOrderRefOrderByIdDesc(cmd.orderType(), cmd.orderRef()).orElseThrow(), cmd));
        }
    }

    /**
     * 결제 승인 (P1) — 토스 successUrl 의 paymentKey · orderId(= pgOrderId) · amount 그대로.
     * ★ prepare 때 저장한 금액과 대조. 프론트가 금액을 조작해도 여기서 막힙니다. 이미 승인된 건은 409 ALREADY_CONFIRMED (두 번 결제되지 않음).
     * 티켓은 받지 않습니다 — Concert 가 선점 좌석을 결제 중(PAYING)으로 바꾼 뒤에만 승인해야 해서 N16 → 내부 승인으로만 (11/04 77A)
     */
    public PaymentView confirm(long userId, String paymentKey, String pgOrderId, int amount, String forceDecline) {
        return approveLocked(() -> {
            PaymentEntity p = owned(repository.lockByPgOrderId(pgOrderId), userId);
            if (TICKET.equals(p.getOrderType())) {
                throw new GeneralException(PaymentErrorCode.USE_BOOKING_CONFIRM);
            }
            return p;
        }, paymentKey, amount, forceDecline);
    }

    /** 내부 승인 — Concert 가 좌석을 결제 중(PAYING)으로 바꾼 뒤 부릅니다 (N16). 검증은 P1 과 같습니다 */
    public PaymentView confirmByOrder(String orderType, String orderRef, long userId, String paymentKey, int amount,
                                      String forceDecline) {
        return approveLocked(() -> owned(repository.lockByOrder(orderType, orderRef), userId), paymentKey, amount, forceDecline);
    }

    /**
     * 행 잠금 안에서 승인. 결과를 모르면(토스 불통 · 처리 중 — 503) 승인 요청에 쓴 paymentKey 를 남긴 채 커밋하고 503 을 그대로 던집니다 (11/04 84A).
     * 그 뒤 정리(cancel)가 그 키로 토스를 조회해 승인 · 무효화를 정합니다. 다른 실패는 지금처럼 롤백.
     */
    private PaymentView approveLocked(Supplier<PaymentEntity> lock, String paymentKey, int amount, String forceDecline) {
        Approval result = tx.execute(s -> {
            PaymentEntity p = lock.get();
            try {
                return new Approval(approve(p, paymentKey, amount, forceDecline), null);
            } catch (GeneralException e) {
                if (!isOutcomeUnknown(e)) throw e;
                return new Approval(null, e);
            }
        });
        if (result.unknown() != null) throw result.unknown();
        return result.view();
    }

    private static boolean isOutcomeUnknown(GeneralException e) {
        return e.getErrorCode() == PaymentErrorCode.PG_UNAVAILABLE || e.getErrorCode() == PaymentErrorCode.PG_PROCESSING;
    }

    private record Approval(PaymentView view, GeneralException unknown) {}

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
        if (p.getRequestedPaymentKey() != null && !p.getRequestedPaymentKey().equals(paymentKey)) {
            // 앞선 승인 요청의 결과를 아직 모릅니다 — 같은 키로 다시 부르면 토스가 첫 결과를 돌려주지만(멱등키), 다른 키로 덮어쓰면 그 결과를 잃습니다
            log.warn("confirm_with_other_key_while_unknown paymentId={}", p.getId());
            throw new GeneralException(PaymentErrorCode.PG_PROCESSING);
        }
        p.requestConfirm(paymentKey);   // ★ 토스를 부르기 전에 — 결과를 모르면 이 값이 커밋됩니다 (84A)

        PaymentGateway.ConfirmResult result = gateway.confirm(paymentKey, p.getPgOrderId(), p.getAmount(), forceDecline);
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (result.approved()) {
            approved(p, paymentKey, result.method(), now);
        } else {
            p.decline(result.declineReason(), now);
            Map<String, Object> payload = payload(p);
            payload.put("reason", p.getDeclineReason());
            eventPublisher.publish("payment.failed", payload);
        }
        return view(p);
    }

    private void approved(PaymentEntity p, String paymentKey, String method, OffsetDateTime now) {
        p.approve(paymentKey, method, now);
        Map<String, Object> payload = payload(p);
        payload.putAll(readMetadata(p.getMetadata()));            // albumIds — 해석하지 않고 그대로 전달
        eventPublisher.publish("payment.completed", payload);
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
        return view(repository.findFirstByOrderTypeAndOrderRefOrderByIdDesc(orderType, orderRef)
                .orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND)));
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
        if (p.getStatus() == PaymentEntity.Status.PENDING && p.getRequestedPaymentKey() != null) {
            resolveUnknown(p);
        }
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

    /**
     * 승인 요청을 보냈지만 결과를 모르는 결제 (84A) — 무효화하기 전에 토스에 묻습니다. 토스 권장: 승인 실패를 바로 단정하지 말고 조회.
     * DONE + 주문번호 · 금액 일치 → 지금 승인으로 기록 (payment.completed). READY · IN_PROGRESS → 아직 모름 → 503 PG_PROCESSING
     * (토스가 인증 10분 안에 승인되지 않은 결제를 EXPIRED 로 바꾸므로 길어야 10분). 그 밖(ABORTED · EXPIRED · 토스에 없음 · 남의 결제) → 승인 안 됨.
     */
    private void resolveUnknown(PaymentEntity p) {
        PaymentGateway.Found found = gateway.find(p.getRequestedPaymentKey());   // 불통이면 PG_UNAVAILABLE
        if (found == null || !p.getPgOrderId().equals(found.orderId())) return;
        if ("DONE".equals(found.status()) && found.totalAmount() == p.getAmount()) {
            log.warn("unknown_confirm_resolved_approved paymentId={}", p.getId());
            approved(p, p.getRequestedPaymentKey(), found.method(), OffsetDateTime.now(clock));
            return;
        }
        if ("READY".equals(found.status()) || "IN_PROGRESS".equals(found.status())) {
            log.info("unknown_confirm_still_open paymentId={} status={}", p.getId(), found.status());
            throw new GeneralException(PaymentErrorCode.PG_PROCESSING);
        }
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

    /**
     * (주문 종류, 주문 번호, 시도) → 항상 같은 UUID. 같은 시도의 두 번째 prepare 가 UNIQUE 에 걸립니다.
     * 첫 시도는 예전 형식 그대로 (이미 있는 행과 같은 키) · 거절 뒤 다시 준비하면 시도 번호가 붙습니다 (80B)
     */
    static UUID idempotencyKey(String orderType, String orderRef, int attempt) {
        String name = attempt == 1 ? orderType + ":" + orderRef : orderType + ":" + orderRef + ":" + attempt;
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
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
