package com.indie.payment.domain.payment.client;

/**
 * PG 어댑터 (15 §5.3) — 구현은 TossPaymentGateway.
 * 금액 대조 · 멱등 · 상태 전이 · Outbox · 보상 트리거는 전부 PaymentService 에 있고, PG 가 바뀌어도 그대로입니다.
 * 결제 준비(prepare)는 PG 를 부르지 않습니다 — 토스는 paymentKey 를 결제창 인증 뒤에 발급하고, 그 전에는 우리 주문번호만 있습니다.
 */
public interface PaymentGateway {

    /**
     * 결제 승인 — orderId 는 우리가 만든 PG 주문번호(pg_order_id), amount 는 prepare 때 저장한 금액입니다.
     * testCode — 테스트용 강제 실패 (X-Force-Decline). 로컬 프로필(app.toss.allow-test-code)에서만 보내고 운영은 무시합니다.
     */
    ConfirmResult confirm(String paymentKey, String orderId, int amount, String testCode);

    /** 전액 취소(환불) — 이미 취소된 결제면 그대로 성공입니다 */
    void cancel(String paymentKey, String reason);

    /** paymentKey 로 결제 조회 — 토스에 없으면 null · 불통이면 PG_UNAVAILABLE (11/04 84A — 결과를 모르는 승인을 정리할 때) */
    Found find(String paymentKey);

    record ConfirmResult(boolean approved, String method, String declineReason) {}

    /** status = 토스 결제 상태 (READY · IN_PROGRESS · DONE · ABORTED · EXPIRED · CANCELED …) */
    record Found(String orderId, String status, String method, int totalAmount) {}
}
