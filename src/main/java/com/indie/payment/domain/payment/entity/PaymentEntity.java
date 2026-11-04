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
    }

    public void cancel(String reason, OffsetDateTime now) {
        this.status = Status.CANCELLED;
        this.declineReason = truncate(reason);
        this.completedAt = now;
    }

    private static String truncate(String reason) {
        return reason == null || reason.length() <= 100 ? reason : reason.substring(0, 100);
    }

    public Long getId() { return id; }
    public String getPgOrderId() { return pgOrderId; }
    public String getPaymentKey() { return paymentKey; }
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
