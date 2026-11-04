package com.indie.payment.domain.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indie.payment.domain.payment.client.PaymentGateway;
import com.indie.payment.domain.payment.entity.PaymentEntity;
import com.indie.payment.domain.payment.repository.PaymentRepository;
import com.indie.payment.global.common.exception.GeneralException;
import com.indie.payment.global.config.PaymentProperties;
import com.indie.payment.global.event.EventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentServiceTest {

    private final PaymentRepository repository = mock(PaymentRepository.class);
    private final PaymentGateway gateway = mock(PaymentGateway.class);
    private final EventPublisher eventPublisher = mock(EventPublisher.class);
    private final PaymentService service = new PaymentService(repository, gateway, eventPublisher, new ObjectMapper(),
            mock(TransactionTemplate.class), new PaymentProperties(null, new PaymentProperties.Payment(10), null, null, null),
            Clock.systemUTC());

    /** ★ P4 는 티켓을 환불하지 않습니다 — 24시간 규칙은 Concert 예매 취소(N13)에서만 (10/24) */
    @Test
    void userCannotRefundTicketThroughP4() {
        PaymentEntity ticket = new PaymentEntity(UUID.randomUUID(), "TICKET-1", "TICKET", "42", 7L, 55000, null,
                OffsetDateTime.now());
        when(repository.lockByIdAndUserId(1L, 7L)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.cancelByUser(7L, 1L, null))
                .isInstanceOfSatisfying(GeneralException.class, e -> {
                    assertThat(e.getErrorCode().getStatus().value()).isEqualTo(409);
                    assertThat(e.getErrorCode().getCode()).isEqualTo("USE_BOOKING_CANCEL");
                });
        verify(gateway, never()).cancel(anyString(), anyString());
        verify(eventPublisher, never()).publish(anyString(), any());
    }

    /** ★ P1 은 티켓을 승인하지 않습니다 — 좌석을 먼저 확보한 Concert 의 내부 승인으로만 (10/27 좌석 경합) */
    @Test
    void userCannotConfirmTicketThroughP1() {
        when(repository.lockByPgOrderId("TICKET-1")).thenReturn(Optional.of(ticket()));

        assertThatThrownBy(() -> service.confirm(7L, "pk_1", "TICKET-1", 55000, null))
                .isInstanceOfSatisfying(GeneralException.class,
                        e -> assertThat(e.getErrorCode().getCode()).isEqualTo("USE_BOOKING_CONFIRM"));
        verify(gateway, never()).confirm(anyString(), anyString(), anyInt(), any());
    }

    /** ★ 토스에는 우리 주문번호(pgOrderId)와 저장한 금액으로 승인 — 성공하면 토스 paymentKey 를 남깁니다 (취소의 식별자) */
    @Test
    void concertConfirmsTicketInternally() {
        PaymentEntity ticket = ticket();
        when(repository.lockByOrder("TICKET", "42")).thenReturn(Optional.of(ticket));
        when(gateway.confirm("pk_1", "TICKET-1", 55000, null)).thenReturn(new PaymentGateway.ConfirmResult(true, "카드", null));

        assertThat(service.confirmByOrder("TICKET", "42", 7L, "pk_1", 55000, null).status()).isEqualTo("APPROVED");
        assertThat(ticket.getPaymentKey()).isEqualTo("pk_1");
        verify(eventPublisher).publish(eq("payment.completed"), any());
    }

    /** ★ 금액 위조는 토스에 닿기 전에 막힙니다 — 위조된 금액으로 승인되는 일이 없음 */
    @Test
    void tamperedAmountNeverReachesToss() {
        when(repository.lockByOrder("TICKET", "42")).thenReturn(Optional.of(ticket()));

        assertThatThrownBy(() -> service.confirmByOrder("TICKET", "42", 7L, "pk_1", 100, null))
                .isInstanceOfSatisfying(GeneralException.class,
                        e -> assertThat(e.getErrorCode().getCode()).isEqualTo("AMOUNT_MISMATCH"));
        verify(gateway, never()).confirm(anyString(), anyString(), anyInt(), any());
    }

    /** 토스 거절 → DECLINED + payment.failed (보상). 거절된 paymentKey 는 남기지 않습니다 */
    @Test
    void tossDeclineFailsPayment() {
        PaymentEntity ticket = ticket();
        when(repository.lockByOrder("TICKET", "42")).thenReturn(Optional.of(ticket));
        when(gateway.confirm("pk_1", "TICKET-1", 55000, "REJECT_CARD_PAYMENT"))
                .thenReturn(new PaymentGateway.ConfirmResult(false, null, "REJECT_CARD_PAYMENT"));

        assertThat(service.confirmByOrder("TICKET", "42", 7L, "pk_1", 55000, "REJECT_CARD_PAYMENT").reason())
                .isEqualTo("REJECT_CARD_PAYMENT");
        assertThat(ticket.getStatus()).isEqualTo(PaymentEntity.Status.DECLINED);
        assertThat(ticket.getPaymentKey()).isNull();
        verify(eventPublisher).publish(eq("payment.failed"), any());
    }

    private static PaymentEntity ticket() {
        PaymentEntity p = new PaymentEntity(UUID.randomUUID(), "TICKET-1", "TICKET", "42", 7L, 55000, null, OffsetDateTime.now());
        ReflectionTestUtils.setField(p, "id", 1L);
        return p;
    }
}
