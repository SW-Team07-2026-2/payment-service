package com.indie.payment.global.common.exception;

import com.indie.payment.global.common.response.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** 모든 에러를 ApiResponse 실패 응답으로. 🔴 예외 메시지에 SQL · 스택트레이스를 노출하지 않습니다 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(GeneralException.class)
    public ResponseEntity<ApiResponse<Object>> general(GeneralException e) {
        return failure(e.getErrorCode(), e.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
            HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, ConstraintViolationException.class})
    public ResponseEntity<ApiResponse<Object>> badRequest(Exception e) {
        return failure(CommonErrorCode.INVALID_REQUEST);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Object>> notFound(NoResourceFoundException e) {
        return failure(CommonErrorCode.NOT_FOUND);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Object>> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return failure(CommonErrorCode.METHOD_NOT_ALLOWED);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Object>> conflict(DataIntegrityViolationException e) {
        log.warn("data_integrity_violation error={}", e.getMostSpecificCause().getClass().getSimpleName());
        return failure(CommonErrorCode.CONFLICT);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> unexpected(Exception e) {
        log.error("unexpected_error", e);
        return failure(CommonErrorCode.INTERNAL_ERROR);
    }

    private static ResponseEntity<ApiResponse<Object>> failure(BaseErrorCode code) {
        return failure(code, code.getMessage());
    }

    private static ResponseEntity<ApiResponse<Object>> failure(BaseErrorCode code, String message) {
        return ResponseEntity.status(code.getStatus()).body(ApiResponse.onFailure(code, message, null));
    }
}
