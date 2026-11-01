package com.indie.payment.domain.payment.dto.response;

import java.time.OffsetDateTime;

/** pgOrderId — 프론트가 토스 SDK requestPayment 의 orderId 로 그대로 씁니다 */
public record PaymentPrepared(String pgOrderId, String status, OffsetDateTime expiresAt) {}
