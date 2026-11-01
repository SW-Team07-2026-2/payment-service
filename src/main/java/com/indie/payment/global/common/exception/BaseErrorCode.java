package com.indie.payment.global.common.exception;

import org.springframework.http.HttpStatus;

/** 에러 코드 규약 — CommonErrorCode · 도메인별 XxxErrorCode enum 이 구현합니다. getCode() 는 응답의 code 로 그대로 나갑니다 */
public interface BaseErrorCode {

    HttpStatus getStatus();

    String getCode();

    String getMessage();
}
