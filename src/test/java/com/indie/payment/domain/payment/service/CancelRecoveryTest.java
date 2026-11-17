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

class CancelRecoveryTest {

    private final PaymentRepository repository = mock(PaymentRepository.class);
    private final PaymentService paymentService = mock(PaymentService.class);
    private final CancelRecovery recovery = new CancelRecovery(repository, paymentService,
            new PaymentProperties(null, new PaymentProperties.Payment(15, 60, 30000), null, null, null),
            Clock.fixed(Instant.parse("2026-11-17T03:00:00Z"), ZoneOffset.UTC));

    /** 환불 요청이 60초 넘게 남은 결제만 이어서 끝내고, 한 건이 실패해도 다음 건으로 넘어갑니다 */
    @Test
    void resumesStaleRequestsAndKeepsGoingOnFailure() {
        when(repository.findByCancelRequestedAtBeforeOrderByCancelRequestedAtAsc(eq(OffsetDateTime.parse("2026-11-17T02:59:00Z")), any()))
                .thenReturn(List.of(goods("GOODS-a"), goods("GOODS-b")));
        when(paymentService.resumeCancel("GOODS-a")).thenThrow(new GeneralException(PaymentErrorCode.PG_UNAVAILABLE));
        when(paymentService.resumeCancel("GOODS-b")).thenReturn("CANCELLED");

        recovery.resume();

        verify(paymentService).resumeCancel("GOODS-a");
        verify(paymentService).resumeCancel("GOODS-b");
    }

    private static PaymentEntity goods(String pgOrderId) {
        return new PaymentEntity(UUID.randomUUID(), pgOrderId, "GOODS", pgOrderId, 7L, 30000, null, OffsetDateTime.now());
    }
}
