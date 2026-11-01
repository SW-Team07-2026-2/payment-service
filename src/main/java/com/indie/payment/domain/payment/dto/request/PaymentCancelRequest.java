package com.indie.payment.domain.payment.dto.request;

import jakarta.validation.constraints.Size;

public record PaymentCancelRequest(@Size(max = 100) String reason) {}
