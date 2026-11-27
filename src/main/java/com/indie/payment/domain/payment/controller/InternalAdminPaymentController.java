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
 * Studio -> Payment 내부 관리자 API -> PaymentService 결제 내역 조회.
 * 취소와 환불은 Catalog 또는 Concert의 주문 취소 경로에서 요청합니다.
 */
@RestController
public class InternalAdminPaymentController {

    private final PaymentService paymentService;
    private final PageSize pageSize;

    public InternalAdminPaymentController(PaymentService paymentService, PageSize pageSize) {
        this.paymentService = paymentService;
        this.pageSize = pageSize;
    }

    /**
     * Studio에서 지정한 회원의 결제 내역을 최신순으로 조회합니다.
     *
     * @param userId 조회하거나 처리할 회원 ID
     * @param cursor 다음 조회 시작점을 나타내는 커서
     * @param size 페이지당 조회 개수
     */
    @GetMapping("/internal/admin/payments/users/{userId}")
    public ApiResponse<CursorPage<PaymentView>> byUser(@PathVariable @Positive long userId,
                                                       @RequestParam(required = false) String cursor,
                                                       @RequestParam(required = false) Integer size) {
        return ApiResponse.onSuccess(paymentService.list(userId, Cursor.decode(cursor), pageSize.resolve(size)));
    }
}
