package com.indie.payment.global.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.indie.payment.global.common.exception.BaseErrorCode;
import com.indie.payment.global.common.filter.CorrelationIdFilter;
import org.slf4j.MDC;

/**
 * 모든 HTTP 응답 본문의 공통 포맷 (Gateway 와 동일).
 * 성공 { isSuccess: true, code: "OK", message, result } · 실패 { isSuccess: false, code, message, result?, correlationId }.
 * result 가 없으면 키를 뺍니다. correlationId 는 실패 응답에만 — 로그에서 요청을 찾는 키입니다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"isSuccess", "code", "message", "result", "correlationId"})
public record ApiResponse<T>(@JsonProperty("isSuccess") Boolean isSuccess, String code, String message, T result,
                             String correlationId) {

    public static final String SUCCESS_CODE = "OK";
    private static final String SUCCESS_MESSAGE = "성공";

    public static <T> ApiResponse<T> onSuccess(T result) {
        return new ApiResponse<>(true, SUCCESS_CODE, SUCCESS_MESSAGE, result, null);
    }

    public static <T> ApiResponse<T> onFailure(BaseErrorCode code, String message, T data) {
        return new ApiResponse<>(false, code.getCode(), message, data, MDC.get(CorrelationIdFilter.MDC_KEY));
    }
}
