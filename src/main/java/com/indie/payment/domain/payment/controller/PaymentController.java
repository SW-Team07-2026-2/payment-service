package com.indie.payment.domain.payment.controller;

import com.indie.payment.domain.payment.dto.request.PaymentCancelRequest;
import com.indie.payment.domain.payment.dto.request.PaymentConfirmRequest;
import com.indie.payment.domain.payment.dto.response.PaymentView;
import com.indie.payment.domain.payment.service.PaymentService;
import com.indie.payment.global.auth.CurrentUser;
import com.indie.payment.global.common.response.ApiResponse;
import com.indie.payment.global.common.response.CursorPage;
import com.indie.payment.global.common.util.Cursor;
import com.indie.payment.global.common.util.PageSize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공개 API P1 ~ P4 — 로그인 필요 (Gateway 가 X-User-Id 를 붙임 · POST 는 CSRF 도).
 * 결제 준비(prepare)는 공개하지 않습니다 — 사용자가 직접 부르면 주문 없이 결제가 생깁니다 (11 §7).
 */
@RestController
public class PaymentController {

    /** 테스트용 강제 거절 (15 §5.4) — 토스 테스트 코드로 넘깁니다 (REJECT_CARD_PAYMENT …). 테스트 키에서만 동작 */
    static final String FORCE_DECLINE = "X-Force-Decline";

    private final PaymentService paymentService;
    private final PageSize pageSize;

    public PaymentController(PaymentService paymentService, PageSize pageSize) {
        this.paymentService = paymentService;
        this.pageSize = pageSize;
    }

    @PostMapping("/api/payments/confirm")
    public ApiResponse<PaymentView> confirm(@CurrentUser Long userId, @Valid @RequestBody PaymentConfirmRequest request,
                                              @RequestHeader(value = FORCE_DECLINE, required = false) String forceDecline) {
        return ApiResponse.onSuccess(paymentService.confirm(userId, request.paymentKey(), request.orderId(), request.amount(), forceDecline));
    }

    @GetMapping("/api/payments/me")
    public ApiResponse<CursorPage<PaymentView>> mine(@CurrentUser Long userId,
                                                       @RequestParam(required = false) String cursor,
                                                       @RequestParam(required = false) Integer size) {
        return ApiResponse.onSuccess(paymentService.list(userId, Cursor.decode(cursor), pageSize.resolve(size)));
    }

    @GetMapping("/api/payments/{id}")
    public ApiResponse<PaymentView> get(@CurrentUser Long userId, @PathVariable @Positive long id) {
        return ApiResponse.onSuccess(paymentService.get(userId, id));
    }

    @PostMapping("/api/payments/{id}/cancel")
    public ApiResponse<PaymentView> cancel(@CurrentUser Long userId, @PathVariable @Positive long id,
                                             @Valid @RequestBody(required = false) PaymentCancelRequest request) {
        return ApiResponse.onSuccess(paymentService.cancelByUser(userId, id, request == null ? null : request.reason()));
    }
}
