package com.indie.payment.domain.payment.client;

/**
 * PaymentService -> PG 호출 포트. 구현은 TossPaymentGateway입니다.
 * 금액 검증과 결제 상태 관리는 PaymentService에서 처리하며 결제 준비 때는 PG를 호출하지 않습니다.
 */
public interface PaymentGateway {

    /**
     * 저장된 주문 금액으로 PG 승인을 요청합니다.
     *
     * @param paymentKey 토스 결제창 인증 후 발급된 결제 키
     * @param orderId PG 주문번호
     * @param amount 결제 금액(원)
     * @param testCode 로컬 테스트용 PG 강제 거절 코드
     */
    ConfirmResult confirm(String paymentKey, String orderId, int amount, String testCode);

    /**
     * PG에 전액 취소를 요청합니다. 이미 취소됐으면 성공하며 처리 중이거나 거절되면 예외를 반환합니다.
     *
     * @param paymentKey 토스 결제창 인증 후 발급된 결제 키
     * @param reason 취소 또는 재고 변경 사유
     */
    void cancel(String paymentKey, String reason);

    /**
     * PG 결제를 조회합니다. 결제가 없으면 null, 통신 실패면 PG_UNAVAILABLE 예외를 반환합니다.
     *
     * @param paymentKey 토스 결제창 인증 후 발급된 결제 키
     */
    Found find(String paymentKey);

    record ConfirmResult(boolean approved, String method, String declineReason) {}

    /** status = 토스 결제 상태 (READY, IN_PROGRESS, DONE, ABORTED, EXPIRED, CANCELED ...) */
    record Found(String orderId, String status, String method, int totalAmount) {}
}
