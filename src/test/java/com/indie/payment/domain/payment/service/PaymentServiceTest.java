package com.indie.payment.domain.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indie.payment.domain.payment.client.PaymentGateway;
import com.indie.payment.domain.payment.entity.PaymentEntity;
import com.indie.payment.domain.payment.exception.PaymentErrorCode;
import com.indie.payment.domain.payment.repository.PaymentRepository;
import com.indie.payment.global.common.exception.GeneralException;
import com.indie.payment.global.config.PaymentProperties;
import com.indie.payment.global.event.EventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentServiceTest {

    private final PaymentRepository repository = mock(PaymentRepository.class);
    private final PaymentGateway gateway = mock(PaymentGateway.class);
    private final EventPublisher eventPublisher = mock(EventPublisher.class);
    /** 콜백이 예외 없이 끝나면 커밋으로 셉니다 — 결과를 모르는 승인이 paymentKey 를 남긴 채 커밋되는지 확인용 */
    private final AtomicInteger commits = new AtomicInteger();
    private final PaymentService service = new PaymentService(repository, gateway, eventPublisher, new ObjectMapper(),
            passThrough(), new PaymentProperties(null, new PaymentProperties.Payment(10), null, null, null), Clock.systemUTC());

    /** 같은 주문으로 다시 준비할 때 사용자 · 금액이 다르면 코드가 따로 (둘 다 409) */
    @Test
    void prepareWithSameOrderButOtherUserOrAmountConflicts() {
        when(repository.findFirstByOrderTypeAndOrderRefOrderByIdDesc("TICKET", "42")).thenReturn(Optional.of(ticket()));   // userId 7 · 55000원

        assertThatThrownBy(() -> service.prepare(new PaymentService.PrepareCommand("TICKET", "42", 8L, 55000, null)))
                .isInstanceOfSatisfying(GeneralException.class,
                        e -> assertThat(e.getErrorCode().getCode()).isEqualTo("IDEMPOTENCY_USER_MISMATCH"));
        assertThatThrownBy(() -> service.prepare(new PaymentService.PrepareCommand("TICKET", "42", 7L, 44000, null)))
                .isInstanceOfSatisfying(GeneralException.class,
                        e -> assertThat(e.getErrorCode().getCode()).isEqualTo("IDEMPOTENCY_AMOUNT_MISMATCH"));
        assertThat(service.prepare(new PaymentService.PrepareCommand("TICKET", "42", 7L, 55000, null)).pgOrderId()).isEqualTo("TICKET-1");
        verify(repository, never()).save(any());
    }

    /** ★ 80B — 거절된 주문을 다시 준비하면 새 결제(시도 2 · 새 주문번호). 같은 주문번호로 다시 승인하면 토스가 지난 거절을 돌려주기 때문 */
    @Test
    void declinedOrderGetsNewPaymentOnPrepare() {
        PaymentEntity declined = ticket();
        declined.decline("REJECT_CARD_PAYMENT", OffsetDateTime.now());
        when(repository.findFirstByOrderTypeAndOrderRefOrderByIdDesc("TICKET", "42")).thenReturn(Optional.of(declined));
        when(repository.countByOrderTypeAndOrderRef("TICKET", "42")).thenReturn(1L);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String retry = service.prepare(new PaymentService.PrepareCommand("TICKET", "42", 7L, 55000, null)).pgOrderId();

        assertThat(retry).startsWith("TICKET-").isNotEqualTo("TICKET-1");
        verify(repository).save(argThat(p -> PaymentService.idempotencyKey("TICKET", "42", 2).equals(ReflectionTestUtils.getField(p, "idempotencyKey"))
                && p.getStatus() == PaymentEntity.Status.PENDING));
        assertThat(PaymentService.idempotencyKey("TICKET", "42", 1)).isNotEqualTo(PaymentService.idempotencyKey("TICKET", "42", 2));
    }

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

    /** ★ P1 은 티켓을 승인하지 않습니다 — 좌석을 결제 중(PAYING)으로 바꾼 Concert 의 내부 승인으로만 (11/04 77A) */
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

    /** ★ 84A — 토스 불통으로 결과를 모르면 승인 요청에 쓴 paymentKey 를 남긴 채 커밋하고 503 (정리가 이 키로 토스를 조회) */
    @Test
    void unknownConfirmKeepsRequestedKey() {
        PaymentEntity ticket = ticket();
        when(repository.lockByOrder("TICKET", "42")).thenReturn(Optional.of(ticket));
        when(gateway.confirm("pk_1", "TICKET-1", 55000, null)).thenThrow(new GeneralException(PaymentErrorCode.PG_UNAVAILABLE));

        assertThatThrownBy(() -> service.confirmByOrder("TICKET", "42", 7L, "pk_1", 55000, null))
                .isInstanceOfSatisfying(GeneralException.class, e -> assertThat(e.getErrorCode().getCode()).isEqualTo("PG_UNAVAILABLE"));
        assertThat(commits).hasValue(1);
        assertThat(ticket.getRequestedPaymentKey()).isEqualTo("pk_1");
        assertThat(ticket.getPaymentKey()).isNull();
        assertThat(ticket.getStatus()).isEqualTo(PaymentEntity.Status.PENDING);
    }

    /** 결과를 모르는 승인이 있으면 다른 paymentKey 로 덮어쓰지 않습니다 — 첫 결과를 잃지 않게 */
    @Test
    void otherKeyWhileUnknownIsRejected() {
        PaymentEntity ticket = ticket();
        ticket.requestConfirm("pk_1");
        when(repository.lockByOrder("TICKET", "42")).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.confirmByOrder("TICKET", "42", 7L, "pk_2", 55000, null))
                .isInstanceOfSatisfying(GeneralException.class, e -> assertThat(e.getErrorCode().getCode()).isEqualTo("PG_PROCESSING"));
        verify(gateway, never()).confirm(anyString(), anyString(), anyInt(), any());
        assertThat(ticket.getRequestedPaymentKey()).isEqualTo("pk_1");
    }

    /** ★ 84A — 결과를 모르는 결제를 정리할 때 토스가 승인(DONE)했으면 무효화하지 않고 승인으로 기록 → APPROVED */
    @Test
    void expiryResolvesUnknownApprovalAsApproved() {
        PaymentEntity ticket = unknownTicket();
        when(gateway.find("pk_1")).thenReturn(new PaymentGateway.Found("TICKET-1", "DONE", "카드", 55000));

        assertThat(service.cancelByOrder("TICKET", "42", "EXPIRED", false)).isEqualTo("APPROVED");
        assertThat(ticket.getPaymentKey()).isEqualTo("pk_1");
        verify(eventPublisher).publish(eq("payment.completed"), any());
        verify(gateway, never()).cancel(anyString(), anyString());
    }

    /** 토스가 아직 IN_PROGRESS — 승인될 수도 있으니 무효화하지 않고 503 (토스가 인증 10분 뒤 EXPIRED 로 바꿈) */
    @Test
    void expiryWaitsWhileTossIsStillOpen() {
        PaymentEntity ticket = unknownTicket();
        when(gateway.find("pk_1")).thenReturn(new PaymentGateway.Found("TICKET-1", "IN_PROGRESS", "카드", 55000));

        assertThatThrownBy(() -> service.cancelByOrder("TICKET", "42", "EXPIRED", false))
                .isInstanceOfSatisfying(GeneralException.class, e -> assertThat(e.getErrorCode().getCode()).isEqualTo("PG_PROCESSING"));
        assertThat(ticket.getStatus()).isEqualTo(PaymentEntity.Status.PENDING);
    }

    /** 토스가 승인하지 않음(ABORTED · EXPIRED · 남의 결제 · 없음) → 그대로 무효화 */
    @Test
    void expiryCancelsWhenTossDidNotApprove() {
        PaymentEntity aborted = unknownTicket();
        when(gateway.find("pk_1")).thenReturn(new PaymentGateway.Found("TICKET-1", "ABORTED", "카드", 55000));
        assertThat(service.cancelByOrder("TICKET", "42", "EXPIRED", false)).isEqualTo("CANCELLED");
        assertThat(aborted.getPaymentKey()).isNull();

        PaymentEntity othersDone = unknownTicket();
        when(gateway.find("pk_1")).thenReturn(new PaymentGateway.Found("TICKET-999", "DONE", "카드", 55000));
        assertThat(service.cancelByOrder("TICKET", "42", "EXPIRED", false)).isEqualTo("CANCELLED");
        assertThat(othersDone.getPaymentKey()).isNull();
        verify(eventPublisher, never()).publish(eq("payment.completed"), any());
    }

    private PaymentEntity unknownTicket() {
        PaymentEntity ticket = ticket();
        ticket.requestConfirm("pk_1");
        when(repository.lockByOrder("TICKET", "42")).thenReturn(Optional.of(ticket));
        return ticket;
    }

    private TransactionTemplate passThrough() {
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(inv -> {
            Object result = inv.<TransactionCallback<?>>getArgument(0).doInTransaction(null);
            commits.incrementAndGet();
            return result;
        });
        return tx;
    }

    private static PaymentEntity ticket() {
        PaymentEntity p = new PaymentEntity(UUID.randomUUID(), "TICKET-1", "TICKET", "42", 7L, 55000, null, OffsetDateTime.now());
        ReflectionTestUtils.setField(p, "id", 1L);
        return p;
    }
}
