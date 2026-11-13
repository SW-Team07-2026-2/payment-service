package com.indie.payment.domain.payment.controller;

import com.indie.payment.domain.payment.dto.response.PaymentView;
import com.indie.payment.domain.payment.service.PaymentService;
import com.indie.payment.global.common.response.ApiResponse;
import com.indie.payment.global.common.response.CursorPage;
import com.indie.payment.global.common.util.Cursor;
import com.indie.payment.global.common.util.PageSize;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자(Studio) 전용 내부 API — X-Internal-Token 의 iss 가 admin-issuers(studio)일 때만 (InternalTokenFilter).
 * CS 관리자가 회원의 결제 내역을 봅니다. 조회만 있습니다 — 취소 · 환불은 주문 서비스(Catalog · Concert)의 관리자 취소가 Payment 를 부릅니다.
 */
@RestController
public class InternalAdminPaymentController {

    private final PaymentService paymentService;
    private final PageSize pageSize;

    public InternalAdminPaymentController(PaymentService paymentService, PageSize pageSize) {
        this.paymentService = paymentService;
        this.pageSize = pageSize;
    }

    /** 회원의 결제 내역 — 최신순 커서 (내 결제 내역 P2 와 같은 모양) */
    @GetMapping("/internal/admin/payments/users/{userId}")
    public ApiResponse<CursorPage<PaymentView>> byUser(@PathVariable @Positive long userId,
                                                       @RequestParam(required = false) String cursor,
                                                       @RequestParam(required = false) Integer size) {
        return ApiResponse.onSuccess(paymentService.list(userId, Cursor.decode(cursor), pageSize.resolve(size)));
    }
}
