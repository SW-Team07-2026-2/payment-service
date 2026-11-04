package com.indie.payment.domain.payment.exception;

import com.indie.payment.global.common.exception.BaseErrorCode;
import org.springframework.http.HttpStatus;

/** 결제 에러 코드 — code 는 enum 이름 그대로 응답에 나갑니다 */
public enum PaymentErrorCode implements BaseErrorCode {

    AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "결제 금액이 주문 금액과 다릅니다"),
    ALREADY_CONFIRMED(HttpStatus.CONFLICT, "이미 승인된 결제입니다"),
    PAYMENT_NOT_PENDING(HttpStatus.CONFLICT, "진행할 수 없는 결제입니다"),
    PAYMENT_EXPIRED(HttpStatus.CONFLICT, "결제 가능 시간이 지났습니다. 다시 주문해주세요"),
    USE_BOOKING_CANCEL(HttpStatus.CONFLICT, "티켓은 예매 취소에서만 환불할 수 있습니다"),
    USE_BOOKING_CONFIRM(HttpStatus.CONFLICT, "티켓은 예매의 결제 승인으로만 결제할 수 있습니다"),
    PAYMENT_NOT_CANCELLABLE(HttpStatus.CONFLICT, "거절된 결제는 취소할 수 없습니다"),
    IDEMPOTENCY_USER_MISMATCH(HttpStatus.CONFLICT, "같은 주문에 다른 사용자의 결제 요청이 있습니다"),
    IDEMPOTENCY_AMOUNT_MISMATCH(HttpStatus.CONFLICT, "같은 주문에 다른 금액의 결제 요청이 있습니다"),
    INVALID_SIGNATURE(HttpStatus.UNAUTHORIZED, "서명이 올바르지 않습니다"),
    PG_CANCEL_REJECTED(HttpStatus.BAD_GATEWAY, "결제사가 취소를 거절했습니다"),
    PG_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "결제사 응답이 없습니다. 잠시 후 결제 내역을 확인해주세요"),
    PG_PROCESSING(HttpStatus.SERVICE_UNAVAILABLE, "결제사가 아직 처리 중입니다. 잠시 후 결제 내역을 확인해주세요");   // 🔴 5xx 유지 — 호출자가 승인 여부 모름(Unavailable)으로 다뤄야 함

    private final HttpStatus status;
    private final String message;

    PaymentErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override
    public HttpStatus getStatus() { return status; }

    @Override
    public String getCode() { return name(); }

    @Override
    public String getMessage() { return message; }
}
