package com.indie.payment.domain.payment.service;

import com.indie.payment.domain.payment.entity.PaymentEntity;
import com.indie.payment.domain.payment.exception.PaymentErrorCode;
import com.indie.payment.domain.payment.repository.PaymentRepository;
import com.indie.payment.global.common.exception.GeneralException;
import com.indie.payment.global.config.PaymentProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PendingExpiryTest {

    private final PaymentRepository repository = mock(PaymentRepository.class);
    private final PaymentService paymentService = mock(PaymentService.class);
    private final PendingExpiry expiry = new PendingExpiry(repository, paymentService,
            new PaymentProperties(null, new PaymentProperties.Payment(15, 60, 30000, 120, 60000), null, null, null),
            Clock.fixed(Instant.parse("2026-11-25T03:00:00Z"), ZoneOffset.UTC));

    /** ttl 15분 + grace 2분 지난 결제 대기만 고르고, 한 건이 실패해도 다음 건으로 넘어갑니다 */
    @Test
    void expiresStalePendingAndKeepsGoingOnFailure() {
        when(repository.findByStatusAndRequestedPaymentKeyIsNullAndRequestedAtBeforeOrderByRequestedAtAsc(
                eq(PaymentEntity.Status.PENDING), eq(OffsetDateTime.parse("2026-11-25T02:43:00Z")), any()))
                .thenReturn(List.of(ticket("TICKET-a"), ticket("TICKET-b")));
        when(paymentService.expirePending("TICKET-a")).thenThrow(new GeneralException(PaymentErrorCode.PG_UNAVAILABLE));
        when(paymentService.expirePending("TICKET-b")).thenReturn("CANCELLED");

        expiry.expire();

        verify(paymentService).expirePending("TICKET-a");
        verify(paymentService).expirePending("TICKET-b");
    }

    private static PaymentEntity ticket(String pgOrderId) {
        return new PaymentEntity(UUID.randomUUID(), pgOrderId, "TICKET", pgOrderId, 7L, 55000, null, OffsetDateTime.now());
    }
}
