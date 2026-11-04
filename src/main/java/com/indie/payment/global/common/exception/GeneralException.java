package com.indie.payment.global.common.exception;

/** 에러 코드로 던지는 예외 — GlobalExceptionHandler 가 ApiResponse 실패 응답으로 바꿉니다 */
public class GeneralException extends RuntimeException {

    private final BaseErrorCode errorCode;

    public GeneralException(BaseErrorCode errorCode) {
        this(errorCode, errorCode.getMessage());
    }

    /** 같은 코드라도 상황에 맞는 메시지가 필요할 때 */
    public GeneralException(BaseErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BaseErrorCode getErrorCode() { return errorCode; }
}
