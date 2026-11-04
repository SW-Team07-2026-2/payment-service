package com.indie.payment.domain.payment.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentView(long paymentId, String orderType, String orderRef, int amount, String status, String method,
                          String reason, OffsetDateTime requestedAt, OffsetDateTime completedAt) {}
