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
 * 프론트 -> Gateway -> PaymentService의 결제 승인, 조회, 취소 API.
 * 결제 준비는 주문 서비스에서만 호출할 수 있습니다.
 */
@RestController
public class PaymentController {

    /** 테스트용 강제 거절 (15 §5.4) - 토스 테스트 코드로 넘깁니다 (REJECT_CARD_PAYMENT ...). 테스트 키에서만 동작 */
    static final String FORCE_DECLINE = "X-Force-Decline";

    private final PaymentService paymentService;
    private final PageSize pageSize;

    public PaymentController(PaymentService paymentService, PageSize pageSize) {
        this.paymentService = paymentService;
        this.pageSize = pageSize;
    }

    /**
     * 회원의 굿즈 결제를 승인합니다.
     *
     * @param userId 조회하거나 처리할 회원 ID
     * @param request 결제 키, PG 주문번호와 요청 금액
     * @param forceDecline 로컬 테스트용 PG 강제 거절 코드
     */
    @PostMapping("/api/payments/confirm")
    public ApiResponse<PaymentView> confirm(@CurrentUser Long userId, @Valid @RequestBody PaymentConfirmRequest request,
                                              @RequestHeader(value = FORCE_DECLINE, required = false) String forceDecline) {
        return ApiResponse.onSuccess(paymentService.confirm(userId, request.paymentKey(), request.orderId(), request.amount(), forceDecline));
    }

    /**
     * 회원 본인의 결제 내역을 최신순으로 조회합니다.
     *
     * @param userId 조회하거나 처리할 회원 ID
     * @param cursor 다음 조회 시작점을 나타내는 커서
     * @param size 페이지당 조회 개수
     */
    @GetMapping("/api/payments/me")
    public ApiResponse<CursorPage<PaymentView>> mine(@CurrentUser Long userId,
                                                       @RequestParam(required = false) String cursor,
                                                       @RequestParam(required = false) Integer size) {
        return ApiResponse.onSuccess(paymentService.list(userId, Cursor.decode(cursor), pageSize.resolve(size)));
    }

    /**
     * 회원 본인의 결제 상세를 조회합니다.
     *
     * @param userId 조회하거나 처리할 회원 ID
     * @param paymentId 결제 ID
     */
    @GetMapping("/api/payments/{paymentId}")
    public ApiResponse<PaymentView> get(@CurrentUser Long userId, @PathVariable @Positive long paymentId) {
        return ApiResponse.onSuccess(paymentService.get(userId, paymentId));
    }

    /**
     * 회원 본인의 결제를 취소합니다.
     *
     * @param userId 조회하거나 처리할 회원 ID
     * @param paymentId 결제 ID
     * @param request 취소 사유, 생략하면 기본 사유 사용
     */
    @PostMapping("/api/payments/{paymentId}/cancel")
    public ApiResponse<PaymentView> cancel(@CurrentUser Long userId, @PathVariable @Positive long paymentId,
                                             @Valid @RequestBody(required = false) PaymentCancelRequest request) {
        return ApiResponse.onSuccess(paymentService.cancelByUser(userId, paymentId, request == null ? null : request.reason()));
    }
}
