package com.indie.payment.global.common.exception;

import org.springframework.http.HttpStatus;

/** 도메인에 속하지 않는 공통 에러 */
public enum CommonErrorCode implements BaseErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다"),
    INVALID_CURSOR(HttpStatus.BAD_REQUEST, "잘못된 커서입니다"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다"),
    INTERNAL_TOKEN_REQUIRED(HttpStatus.UNAUTHORIZED, "내부 호출 인증에 실패했습니다"),
    INTERNAL_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "내부 호출 인증에 실패했습니다"),
    INTERNAL_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "내부 호출 인증이 만료되었습니다"),   // 서버 간 시계 차이 신호
    NOT_FOUND(HttpStatus.NOT_FOUND, "찾을 수 없습니다"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "허용되지 않는 메서드입니다"),
    CONFLICT(HttpStatus.CONFLICT, "요청을 처리할 수 없습니다"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다");

    private final HttpStatus status;
    private final String message;

    CommonErrorCode(HttpStatus status, String message) {
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
