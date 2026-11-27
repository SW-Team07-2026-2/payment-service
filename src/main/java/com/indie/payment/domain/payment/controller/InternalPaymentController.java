package com.indie.payment.domain.payment.controller;

import com.indie.payment.domain.payment.dto.request.OrderCancelRequest;
import com.indie.payment.domain.payment.dto.request.OrderConfirmRequest;
import com.indie.payment.domain.payment.dto.request.PaymentPrepareRequest;
import com.indie.payment.domain.payment.dto.response.PaymentCancelResponse;
import com.indie.payment.domain.payment.dto.response.PaymentPrepared;
import com.indie.payment.domain.payment.dto.response.PaymentView;
import com.indie.payment.domain.payment.service.PaymentService;
import com.indie.payment.global.common.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catalog 또는 Concert -> Payment 내부 API -> PaymentService.
 * 내부 토큰으로 인증하며 사용자 식별은 요청 본문의 userId를 사용합니다.
 */
@RestController
public class InternalPaymentController {

    private final PaymentService paymentService;

    public InternalPaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * 주문 서비스가 계산한 금액으로 결제를 준비하고 PG 주문번호를 발급합니다.
     *
     * @param r 주문 식별자, 회원과 서버에서 계산한 금액
     */
    @PostMapping("/internal/payments/prepare")
    public ApiResponse<PaymentPrepared> prepare(@Valid @RequestBody PaymentPrepareRequest r) {
        return ApiResponse.onSuccess(paymentService.prepare(new PaymentService.PrepareCommand(r.orderType(), r.orderRef(), r.userId(), r.amount(),
                r.albumIds())));
    }

    /**
     * Concert가 좌석을 PAYING으로 바꾼 뒤 요청한 결제를 승인합니다.
     *
     * @param r 주문 식별자와 PG 승인 정보
     */
    @PostMapping("/internal/payments/confirm")
    public ApiResponse<PaymentView> confirm(@Valid @RequestBody OrderConfirmRequest r) {
        return ApiResponse.onSuccess(paymentService.confirmByOrder(r.orderType(), r.orderRef(), r.userId(), r.paymentKey(),
                r.amount(), r.forceDecline()));
    }

    /**
     * 주문 종류와 참조 번호로 결제 상태를 조회합니다.
     *
     * @param orderType 주문 종류
     * @param orderRef 주문 서비스의 주문 참조 번호
     */
    @GetMapping("/internal/payments/{orderType}/{orderRef}")
    public ApiResponse<PaymentView> status(@PathVariable String orderType, @PathVariable String orderRef) {
        return ApiResponse.onSuccess(paymentService.getByOrder(orderType, orderRef));
    }

    /**
     * 주문 서비스의 취소 요청을 처리하고 결제 결과 이벤트를 발행합니다.
     *
     * @param r 주문 식별자, 취소 사유와 환불 허용 여부
     */
    @PostMapping("/internal/payments/cancel")
    public ApiResponse<PaymentCancelResponse> cancel(@Valid @RequestBody OrderCancelRequest r) {
        return ApiResponse.onSuccess(new PaymentCancelResponse(paymentService.cancelByOrder(r.orderType(), r.orderRef(), r.reason(), r.refundApproved())));
    }
}
