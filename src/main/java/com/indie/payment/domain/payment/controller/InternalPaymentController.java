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
 * 내부 API — Catalog · Concert 전용 (X-Internal-Token · Gateway 라우트 없음 → 외부에서는 404).
 * 🔴 X-User-Id 를 보지 않습니다 — 사용자는 요청 본문의 userId 로 식별합니다.
 */
@RestController
public class InternalPaymentController {

    private final PaymentService paymentService;

    public InternalPaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /** I10 — 결제 준비 · PG 주문번호(pgOrderId) 발급. 같은 주문으로 다시 부르면 같은 결과 */
    @PostMapping("/internal/payments/prepare")
    public ApiResponse<PaymentPrepared> prepare(@Valid @RequestBody PaymentPrepareRequest r) {
        return ApiResponse.onSuccess(paymentService.prepare(new PaymentService.PrepareCommand(r.orderType(), r.orderRef(), r.userId(), r.amount(),
                r.albumIds())));
    }

    /**
     * 내부 승인 — 15 에 없는 내부 API 입니다. 티켓은 Concert 가 좌석을 먼저 확보한 뒤에만 여기로 승인합니다 (P1 은 티켓 거부).
     * 4xx 는 돈이 나가기 전 거부 — Concert 가 좌석을 다시 경합으로 돌립니다.
     */
    @PostMapping("/internal/payments/confirm")
    public ApiResponse<PaymentView> confirm(@Valid @RequestBody OrderConfirmRequest r) {
        return ApiResponse.onSuccess(paymentService.confirmByOrder(r.orderType(), r.orderRef(), r.userId(), r.paymentKey(),
                r.amount(), r.forceDecline()));
    }

    /** I11 — 결제 상태 대조 (PENDING 정산 배치) */
    @GetMapping("/internal/payments/{orderType}/{orderRef}")
    public ApiResponse<PaymentView> status(@PathVariable String orderType, @PathVariable String orderRef) {
        return ApiResponse.onSuccess(paymentService.getByOrder(orderType, orderRef));
    }

    /**
     * 주문 쪽 취소 — 15 에 없는 내부 API 입니다. 주문 취소(C12) · 결제창 이탈 만료가 결제를 먼저 끝내야
     * "재고는 돌려놨는데 결제는 승인됨"이 생기지 않습니다. 결과는 payment.cancelled 로도 나갑니다.
     */
    @PostMapping("/internal/payments/cancel")
    public ApiResponse<PaymentCancelResponse> cancel(@Valid @RequestBody OrderCancelRequest r) {
        return ApiResponse.onSuccess(new PaymentCancelResponse(paymentService.cancelByOrder(r.orderType(), r.orderRef(), r.reason(), r.refundApproved())));
    }
}
