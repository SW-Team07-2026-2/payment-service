package com.indie.payment.domain.payment.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record OrderCancelRequest(@NotNull @Pattern(regexp = "TICKET|GOODS") String orderType,
                            @NotBlank @Size(max = 50) String orderRef,
                            @Size(max = 100) String reason,
                            boolean refundApproved) {}
