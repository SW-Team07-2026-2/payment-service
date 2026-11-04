package com.indie.payment.domain.payment.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 토스 successUrl 의 쿼리 세 개 그대로 — orderId 는 prepare 가 준 pgOrderId 입니다 (C9 의 주문 번호가 아님) */
public record PaymentConfirmRequest(@NotBlank @Size(max = 200) String paymentKey,
                             @NotBlank @Size(max = 64) String orderId,
                             @NotNull @Positive Integer amount) {}
