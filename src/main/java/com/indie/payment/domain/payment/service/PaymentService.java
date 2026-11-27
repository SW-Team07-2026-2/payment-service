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
 * 결제 준비, 승인, 취소와 조회를 처리합니다.
 * 준비 시 저장한 금액을 승인 요청과 대조하며 원래 주문 금액 계산은 Catalog와 Concert가 담당합니다.
 * PG 호출은 트랜잭션 밖에서 수행하고 상태 변경과 결과 이벤트는 함께 저장합니다.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    /** Concert 의 orderType - Payment 는 주문 종류를 해석하지 않지만 P1, P4 만 예외입니다 */
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
     * 같은 주문의 마지막 결제를 반환합니다. 마지막 결제가 거절됐으면 새 PG 주문번호로 결제를 만듭니다.
     * 동시 생성은 멱등키 유일 제약으로 막고 충돌 시 기존 결제를 조회합니다.
     *
     * @param cmd 주문 식별자, 회원과 서버에서 계산한 금액
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
     * 저장된 금액과 대조해 굿즈 결제를 승인합니다. 티켓은 Concert의 내부 승인 경로만 허용합니다.
     *
     * @param userId 조회하거나 처리할 회원 ID
     * @param paymentKey 토스 결제창 인증 후 발급된 결제 키
     * @param pgOrderId 결제 준비 시 발급한 PG 주문번호
     * @param amount 결제 금액(원)
     * @param forceDecline 로컬 테스트용 PG 강제 거절 코드
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

    /**
     * 내부 승인 - Concert 가 좌석을 결제 중(PAYING)으로 바꾼 뒤 부릅니다 (N16). 검증은 P1 과 같습니다
     *
     * @param orderType 주문 종류
     * @param orderRef 주문 서비스의 주문 참조 번호
     * @param userId 조회하거나 처리할 회원 ID
     * @param paymentKey 토스 결제창 인증 후 발급된 결제 키
     * @param amount 결제 금액(원)
     * @param forceDecline 로컬 테스트용 PG 강제 거절 코드
     */
    public PaymentView confirmByOrder(String orderType, String orderRef, long userId, String paymentKey, int amount,
                                      String forceDecline) {
        return approveLocked(() -> owned(repository.lockByOrder(orderType, orderRef), userId), paymentKey, amount, forceDecline);
    }

    /**
     * 행 잠금으로 검증하고 paymentKey를 저장한 뒤 트랜잭션 밖에서 PG를 호출합니다.
     * 응답 후 다시 잠가 결과를 반영하며, 결과가 불명확하면 키를 유지해 취소 시 조회합니다.
     */
    private PaymentView approveLocked(Supplier<PaymentEntity> lock, String paymentKey, int amount, String forceDecline) {
        PaymentEntity requested = tx.execute(s -> request(lock.get(), paymentKey, amount));
        PaymentGateway.ConfirmResult result = gateway.confirm(paymentKey, requested.getPgOrderId(), requested.getAmount(), forceDecline);
        return tx.execute(s -> record(repository.lockByPgOrderId(requested.getPgOrderId()).orElseThrow(), paymentKey, result));
    }

    private static PaymentEntity owned(Optional<PaymentEntity> found, long userId) {
        return found.filter(p -> p.getUserId() == userId).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND));
    }

    /** 승인 전 금액과 상태를 검증하고 결제 키를 저장합니다. PG에는 저장된 금액을 전달합니다. */
    private PaymentEntity request(PaymentEntity p, String paymentKey, int amount) {
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
            // 앞선 승인 요청의 결과를 아직 모릅니다 - 같은 키로 다시 부르면 토스가 첫 결과를 돌려주지만(멱등키), 다른 키로 덮어쓰면 그 결과를 잃습니다
            log.warn("confirm_with_other_key_while_unknown paymentId={}", p.getId());
            throw new GeneralException(PaymentErrorCode.PG_PROCESSING);
        }
        p.requestConfirm(paymentKey);   // 토스를 부르기 전에 커밋 - 결과를 모르거나 파드가 죽어도 이 키로 조회합니다 (84A)
        return p;
    }

    /** PG 승인 결과를 반영합니다. 다른 요청이 먼저 상태를 확정했으면 덮어쓰거나 이벤트를 중복 발행하지 않습니다. */
    private PaymentView record(PaymentEntity p, String paymentKey, PaymentGateway.ConfirmResult result) {
        switch (p.getStatus()) {
            case APPROVED, DECLINED -> {
                return view(p);
            }
            case CANCELLED -> throw new GeneralException(PaymentErrorCode.PAYMENT_NOT_PENDING);
            case PENDING -> { }
        }
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
        payload.putAll(readMetadata(p.getMetadata()));            // albumIds - 해석하지 않고 그대로 전달
        eventPublisher.publish("payment.completed", payload);
    }

    /**
     * 내부 취소 - Catalog 의 주문 취소(refundApproved=true), 만료(false), Concert 의 예매 취소(true), 만료(false)
     *
     * @param orderType 주문 종류
     * @param orderRef 주문 서비스의 주문 참조 번호
     * @param reason 취소 또는 재고 변경 사유
     * @param refundApproved 이미 승인된 결제의 환불 허용 여부
     */
    public String cancelByOrder(String orderType, String orderRef, String reason, boolean refundApproved) {
        return cancelLocked(() -> repository.lockByOrder(orderType, orderRef).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND)),
                reason, refundApproved).getStatus().name();
    }

    /**
     * P4 - 사용자 결제 취소, 환불. 결과는 payment.cancelled 로 주문 서비스에 전달됩니다.
     * TICKET 은 받지 않습니다 - 공연 24시간 전 규칙(FR-CON-14)을 Concert 예매 취소(N13)에서만 지키도록
     *
     * @param userId 조회하거나 처리할 회원 ID
     * @param paymentId 결제 ID
     * @param reason 취소 또는 재고 변경 사유
     */
    public PaymentView cancelByUser(long userId, long paymentId, String reason) {
        return view(cancelLocked(() -> {
            PaymentEntity p = repository.lockByIdAndUserId(paymentId, userId).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND));
            if (TICKET.equals(p.getOrderType())) {
                throw new GeneralException(PaymentErrorCode.USE_BOOKING_CANCEL);
            }
            if (p.getStatus() == PaymentEntity.Status.DECLINED) {
                throw new GeneralException(PaymentErrorCode.PAYMENT_NOT_CANCELLABLE);
            }
            return p;
        }, reason == null ? "USER_REQUEST" : reason, true));
    }

    /**
     * 환불 복구(CancelRecovery) - 남아 있는 환불 요청을 처음 사유 그대로 이어서 끝냅니다
     *
     * @param pgOrderId 결제 준비 시 발급한 PG 주문번호
     */
    public String resumeCancel(String pgOrderId) {
        return cancelLocked(() -> repository.lockByPgOrderId(pgOrderId).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND)),
                null, true).getStatus().name();
    }

    /**
     * 결제 대기 만료 정리(PendingExpiry) - 만료 취소와 같습니다 (refundApproved=false, 그사이 승인됐으면 APPROVED 그대로)
     *
     * @param pgOrderId 결제 준비 시 발급한 PG 주문번호
     */
    public String expirePending(String pgOrderId) {
        return cancelLocked(() -> repository.lockByPgOrderId(pgOrderId).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND)),
                "PENDING_EXPIRED", false).getStatus().name();
    }

    /**
     * 회원 본인의 결제 상세를 조회합니다.
     *
     * @param userId 조회하거나 처리할 회원 ID
     * @param paymentId 결제 ID
     */
    @Transactional(readOnly = true)
    public PaymentView get(long userId, long paymentId) {
        return view(repository.findByIdAndUserId(paymentId, userId).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND)));
    }

    /**
     * 주문 종류와 참조 번호로 마지막 결제를 조회합니다.
     *
     * @param orderType 주문 종류
     * @param orderRef 주문 서비스의 주문 참조 번호
     */
    @Transactional(readOnly = true)
    public PaymentView getByOrder(String orderType, String orderRef) {
        return view(repository.findFirstByOrderTypeAndOrderRefOrderByIdDesc(orderType, orderRef)
                .orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND)));
    }

    /**
     * 회원의 결제 내역을 최신순으로 조회합니다.
     *
     * @param userId 조회하거나 처리할 회원 ID
     * @param cursor 다음 조회 시작점을 나타내는 커서
     * @param size 페이지당 조회 개수
     */
    @Transactional(readOnly = true)
    public CursorPage<PaymentView> list(long userId, Cursor cursor, int size) {
        Limit limit = Limit.of(size + 1);
        List<PaymentEntity> rows = cursor == null
                ? repository.findByUserIdOrderByIdDesc(userId, limit)
                : repository.findByUserIdAndIdLessThanOrderByIdDesc(userId, cursor.id(), limit);
        return CursorPage.of(rows.stream().map(this::view).toList(), size, v -> new Cursor(null, v.paymentId()));
    }

    /**
     * 취소 대상을 잠가 확인한 뒤 트랜잭션 밖에서 PG 조회나 환불을 요청하고 결과를 반영합니다.
     * 환불 요청을 먼저 저장해 장애 시 CancelRecovery가 이어서 처리합니다.
     * refundApproved=false이면 승인된 결제는 환불하지 않고 APPROVED를 반환합니다.
     */
    private PaymentEntity cancelLocked(Supplier<PaymentEntity> lock, String reason, boolean refundApproved) {
        CancelStep step = tx.execute(s -> begin(lock.get(), reason, refundApproved));
        if (step.done() != null) return step.done();
        PgResult pg;
        try {
            pg = callPg(step, refundApproved);
        } catch (GeneralException e) {
            if (e.getErrorCode() == PaymentErrorCode.PG_CANCEL_REJECTED) {
                tx.execute(s -> {
                    repository.lockByPgOrderId(step.pgOrderId()).ifPresent(PaymentEntity::clearCancelRequest);
                    return null;
                });
            }
            throw e;
        }
        return tx.execute(s -> finish(repository.lockByPgOrderId(step.pgOrderId()).orElseThrow(), step.reason(), refundApproved, pg));
    }

    /** 행 잠금 안에서 취소 가능 여부를 확인하고 PG 호출에 필요한 값을 반환합니다. */
    private CancelStep begin(PaymentEntity p, String reason, boolean refundApproved) {
        switch (p.getStatus()) {
            case DECLINED, CANCELLED -> {
                return CancelStep.finished(p);
            }
            case APPROVED -> {
                if (!refundApproved) return CancelStep.finished(p);
            }
            case PENDING -> {
                if (p.getRequestedPaymentKey() == null) {   // 승인 요청을 보낸 적 없음 - 돈이 나갈 수 없으니 바로 무효화
                    cancelled(p, reason, false);
                    return CancelStep.finished(p);
                }
            }
        }
        if (refundApproved) p.requestCancel(reason, OffsetDateTime.now(clock));   // 토스를 부르기 전에 커밋 - 기록 전에 멈춰도 복구가 이어서 끝냄
        return new CancelStep(null, p.getPgOrderId(), p.getStatus(), p.getPaymentKey(), p.getRequestedPaymentKey(), p.getAmount(),
                p.getCancelReason() != null ? p.getCancelReason() : reason);
    }

    /**
     * 승인 결과가 불명확한 결제를 PG에서 조회합니다. DONE은 주문번호와 금액을 대조합니다.
     * READY와 IN_PROGRESS는 처리 중으로 응답하며 나머지는 미승인으로 판단합니다.
     */
    private PgResult callPg(CancelStep step, boolean refundApproved) {
        String paymentKey = step.paymentKey();
        boolean tossApproved = false;
        String method = null;
        if (step.status() == PaymentEntity.Status.PENDING) {
            PaymentGateway.Found found = gateway.find(step.requestedPaymentKey());   // 불통이면 PG_UNAVAILABLE
            if (found != null && step.pgOrderId().equals(found.orderId())) {
                if ("DONE".equals(found.status()) && found.totalAmount() == step.amount()) {
                    log.warn("unknown_confirm_resolved_approved pgOrderId={}", step.pgOrderId());
                    tossApproved = true;
                    method = found.method();
                    paymentKey = step.requestedPaymentKey();
                } else if ("READY".equals(found.status()) || "IN_PROGRESS".equals(found.status())) {
                    log.info("unknown_confirm_still_open pgOrderId={} status={}", step.pgOrderId(), found.status());
                    throw new GeneralException(PaymentErrorCode.PG_PROCESSING);
                }
            }
            if (!tossApproved) return new PgResult(false, null, false);
        }
        if (!refundApproved) return new PgResult(tossApproved, method, false);
        gateway.cancel(paymentKey, step.reason());
        return new PgResult(tossApproved, method, true);
    }

    /**
     * 취소 결과를 다시 잠가 반영합니다. 이미 끝난 상태는 유지하고 이벤트는 중복 발행하지 않습니다.
     * 조회 뒤 승인이 확인돼 환불을 못 끝내면 503을 반환하고 복구용 환불 요청을 유지합니다.
     */
    private PaymentEntity finish(PaymentEntity p, String reason, boolean refundApproved, PgResult pg) {
        switch (p.getStatus()) {
            case DECLINED, CANCELLED -> {
                return p;
            }
            case PENDING -> {
                if (!pg.tossApproved()) {
                    cancelled(p, reason, false);
                    return p;
                }
                approved(p, p.getRequestedPaymentKey(), pg.method(), OffsetDateTime.now(clock));
            }
            case APPROVED -> { }
        }
        if (pg.refunded()) {
            cancelled(p, reason, true);
            return p;
        }
        if (refundApproved) throw new GeneralException(PaymentErrorCode.PG_PROCESSING);
        return p;
    }

    private void cancelled(PaymentEntity p, String reason, boolean refunded) {
        p.cancel(reason, OffsetDateTime.now(clock));
        Map<String, Object> payload = payload(p);
        payload.put("reason", p.getDeclineReason());
        payload.put("refunded", refunded);
        eventPublisher.publish("payment.cancelled", payload);
    }

    /** 취소 사전 검증 결과. done이 있으면 완료된 결제이고, 없으면 PG 호출에 필요한 값입니다. */
    private record CancelStep(PaymentEntity done, String pgOrderId, PaymentEntity.Status status, String paymentKey,
                              String requestedPaymentKey, int amount, String reason) {
        static CancelStep finished(PaymentEntity p) {
            return new CancelStep(p, null, null, null, null, 0, null);
        }
    }

    /** PG 처리 결과. tossApproved는 승인 확인 여부, refunded는 환불 완료 여부입니다. */
    private record PgResult(boolean tossApproved, String method, boolean refunded) {}

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
     * 토스 orderId - 6~64자, 영숫자 - _ 만, 상점 안에서 유일. 주문 번호만 쓰면 로컬 DB 를 초기화한 뒤 같은 번호가 다시 나와
     * 테스트 상점이 중복으로 거절하므로 무작위 값을 붙입니다 (GOODS-1b4e... 43자 이하)
     */
    private static String pgOrderId(String orderType) {
        return orderType + "-" + UUID.randomUUID();
    }

    /**
     * (주문 종류, 주문 번호, 시도) -> 항상 같은 UUID. 같은 시도의 두 번째 prepare 가 UNIQUE 에 걸립니다.
     * 첫 시도는 예전 형식 그대로 (이미 있는 행과 같은 키), 거절 뒤 다시 준비하면 시도 번호가 붙습니다 (80B)
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
