package com.indie.payment.domain.payment.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 내부 승인 — paymentKey 는 토스 successUrl 의 값 · forceDecline 은 사용자의 X-Force-Decline 을 Concert 가 그대로 넘긴 값 (토스 테스트 코드) */
public record OrderConfirmRequest(@NotNull @Pattern(regexp = "TICKET|GOODS") String orderType,
                                  @NotBlank @Size(max = 50) String orderRef,
                                  @NotNull @Positive Long userId,
                                  @NotBlank @Size(max = 200) String paymentKey,
                                  @NotNull @Positive Integer amount,
                                  @Size(max = 50) String forceDecline) {}
