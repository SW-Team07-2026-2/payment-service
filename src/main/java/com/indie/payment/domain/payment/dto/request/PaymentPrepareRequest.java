package com.indie.payment.domain.payment.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record PaymentPrepareRequest(@NotNull @Pattern(regexp = "TICKET|GOODS") String orderType,
                             @NotBlank @Size(max = 50) String orderRef,
                             @NotNull @Positive Long userId,
                             @NotNull @Positive Integer amount,
                             @Size(max = 100) List<@NotNull @Positive Long> albumIds) {}
