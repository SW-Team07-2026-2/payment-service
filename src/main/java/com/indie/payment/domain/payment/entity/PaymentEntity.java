package com.indie.payment.domain.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * payment — 상태 전이 PENDING → APPROVED | DECLINED | CANCELLED, APPROVED → CANCELLED(환불).
 * 전이는 행 잠금(SELECT ... FOR UPDATE) 안에서만 합니다 — confirm 과 cancel 이 동시에 와도 하나만 이깁니다.
 */
@Entity
@Table(name = "payment")
public class PaymentEntity {

    public enum Status { PENDING, APPROVED, DECLINED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private UUID idempotencyKey;

    /** 토스 orderId — prepare 때 우리가 만듭니다 (6~64자 · 영숫자 - _) */
    @Column(unique = true, nullable = false, length = 64)
    private String pgOrderId;

    /** 토스가 결제창 인증 뒤 발급 — 승인에 성공했을 때만 채웁니다 (취소 · 조회의 식별자) */
    @Column(unique = true, length = 200)
    private String paymentKey;

    /** 승인 요청에 쓴 paymentKey — 토스를 부르기 전에 남깁니다. 결과를 모를 때 정리가 이 키로 조회합니다 (11/04 84A · 검증 전 값이라 UNIQUE 아님) */
    @Column(length = 200)
    private String requestedPaymentKey;

    /** 진행 중인 환불 요청 (11/17) — 토스 취소를 부르기 전에 남기고 끝나면 지웁니다. 남아 있으면 복구(CancelRecovery)가 이어서 끝냅니다 */
    private OffsetDateTime cancelRequestedAt;

    /** 그 환불 요청의 사유 — 처음 사유를 유지합니다 (토스 취소 멱등키가 결제마다 같아 첫 요청이 기준) */
    private String cancelReason;

    private String orderType;

    private String orderRef;

    private Long userId;

    private Integer amount;

    private String method;

    @Enumerated(EnumType.STRING)
    private Status status;

    private String declineReason;

    @JdbcTypeCode(SqlTypes.JSON)
    private String metadata;

    private OffsetDateTime requestedAt;

    private OffsetDateTime completedAt;

    protected PaymentEntity() {}

    public PaymentEntity(UUID idempotencyKey, String pgOrderId, String orderType, String orderRef, Long userId,
                         Integer amount, String metadata, OffsetDateTime now) {
        this.idempotencyKey = idempotencyKey;
        this.pgOrderId = pgOrderId;
        this.orderType = orderType;
        this.orderRef = orderRef;
        this.userId = userId;
        this.amount = amount;
        this.metadata = metadata;
        this.status = Status.PENDING;
        this.requestedAt = now;
    }

    public void requestConfirm(String paymentKey) {
        this.requestedPaymentKey = paymentKey;
    }

    public void approve(String paymentKey, String method, OffsetDateTime now) {
        this.status = Status.APPROVED;
        this.paymentKey = paymentKey;
        this.method = method;
        this.completedAt = now;
    }

    public void decline(String reason, OffsetDateTime now) {
        this.status = Status.DECLINED;
        this.declineReason = truncate(reason);
        this.completedAt = now;
        clearCancelRequest();
    }

    public void cancel(String reason, OffsetDateTime now) {
        this.status = Status.CANCELLED;
        this.declineReason = truncate(reason);
        this.completedAt = now;
        clearCancelRequest();
    }

    /** 요청할 때마다 시각을 새로 찍습니다 — 복구는 이 시각이 오래된 것만 집습니다 (진행 중인 요청과 겹치지 않게) */
    public void requestCancel(String reason, OffsetDateTime now) {
        if (this.cancelReason == null) this.cancelReason = truncate(reason);
        this.cancelRequestedAt = now;
    }

    public void clearCancelRequest() {
        this.cancelRequestedAt = null;
        this.cancelReason = null;
    }

    private static String truncate(String reason) {
        return reason == null || reason.length() <= 100 ? reason : reason.substring(0, 100);
    }

    public Long getId() { return id; }
    public String getPgOrderId() { return pgOrderId; }
    public String getPaymentKey() { return paymentKey; }
    public String getRequestedPaymentKey() { return requestedPaymentKey; }
    public OffsetDateTime getCancelRequestedAt() { return cancelRequestedAt; }
    public String getCancelReason() { return cancelReason; }
    public String getOrderType() { return orderType; }
    public String getOrderRef() { return orderRef; }
    public Long getUserId() { return userId; }
    public Integer getAmount() { return amount; }
    public String getMethod() { return method; }
    public Status getStatus() { return status; }
    public String getDeclineReason() { return declineReason; }
    public String getMetadata() { return metadata; }
    public OffsetDateTime getRequestedAt() { return requestedAt; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
}
