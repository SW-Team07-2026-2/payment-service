package com.indie.payment.domain.payment.client;

import com.indie.payment.global.common.exception.GeneralException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TossPaymentGatewayTest {

    private static final String BASE = "https://api.tosspayments.com";
    private static final String CONFIRM = BASE + "/v1/payments/confirm";
    private static final String LOOKUP = BASE + "/v1/payments/pk_1";
    private static final String KEY = "test_gsk_unit";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final TossPaymentGateway gateway = new TossPaymentGateway(builder, BASE, KEY);

    /** ★ 실결제 차단 — 라이브 키면 빈을 만들지 못해 Payment 가 기동하지 않습니다 */
    @Test
    void liveKeyIsRejected() {
        assertThatThrownBy(() -> new TossPaymentGateway(RestClient.builder(), BASE, "live_gsk_x"))
                .isInstanceOf(IllegalStateException.class);
    }

    /** 시크릿 키 뒤에 콜론을 붙여 base64 · 본문은 우리 주문번호와 저장한 금액 */
    @Test
    void confirmApprovesOnDone() {
        String basic = Base64.getEncoder().encodeToString((KEY + ":").getBytes(StandardCharsets.UTF_8));
        server.expect(requestTo(CONFIRM))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Basic " + basic))
                .andExpect(headerDoesNotExist(TossPaymentGateway.TEST_CODE))
                .andExpect(content().json("{\"paymentKey\":\"pk_1\",\"orderId\":\"GOODS-1\",\"amount\":55000}"))
                .andRespond(withSuccess("{\"status\":\"DONE\",\"method\":\"카드\"}", MediaType.APPLICATION_JSON));

        assertThat(gateway.confirm("pk_1", "GOODS-1", 55000, null))
                .isEqualTo(new PaymentGateway.ConfirmResult(true, "카드", null));
        server.verify();
    }

    /** X-Force-Decline → 토스 테스트 코드 헤더 → 토스가 그 에러를 재현 → 조회해도 승인 전 → 거절 (사유 = 토스 에러 코드) */
    @Test
    void forcedDeclineBecomesDecline() {
        server.expect(requestTo(CONFIRM))
                .andExpect(header(TossPaymentGateway.TEST_CODE, "REJECT_CARD_PAYMENT"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"REJECT_CARD_PAYMENT\",\"message\":\"한도초과 혹은 잔액부족으로 결제에 실패했습니다.\"}"));
        expectLookup("{\"orderId\":\"GOODS-1\",\"status\":\"IN_PROGRESS\",\"method\":\"카드\",\"totalAmount\":55000}");

        assertThat(gateway.confirm("pk_1", "GOODS-1", 55000, "REJECT_CARD_PAYMENT"))
                .isEqualTo(new PaymentGateway.ConfirmResult(false, null, "REJECT_CARD_PAYMENT"));
        server.verify();
    }

    /** ★ 승인 실패를 바로 단정하지 않습니다 — 토스가 이미 승인했으면 조회로 확인해 승인 */
    @Test
    void failedConfirmButDoneAtTossIsApproved() {
        server.expect(requestTo(CONFIRM))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"ALREADY_PROCESSED_PAYMENT\",\"message\":\"이미 처리된 결제 입니다.\"}"));
        server.expect(requestTo(LOOKUP))
                .andExpect(method(HttpMethod.GET))
                .andExpect(headerDoesNotExist(TossPaymentGateway.IDEMPOTENCY_KEY))   // GET 에는 멱등키를 붙이지 않음
                .andRespond(withSuccess("{\"orderId\":\"GOODS-1\",\"status\":\"DONE\",\"method\":\"간편결제\",\"totalAmount\":55000}",
                        MediaType.APPLICATION_JSON));

        assertThat(gateway.confirm("pk_1", "GOODS-1", 55000, null))
                .isEqualTo(new PaymentGateway.ConfirmResult(true, "간편결제", null));
        server.verify();
    }

    /** 첫 요청이 아직 처리 중(409) — 조회해도 승인 전이면 거절이 아니라 503 (다시 부르면 첫 결과를 받음) */
    @Test
    void idempotentProcessingIsUnknownNotDecline() {
        server.expect(requestTo(CONFIRM))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"IDEMPOTENT_REQUEST_PROCESSING\",\"message\":\"이전 멱등 요청이 처리중입니다.\"}"));
        expectLookup("{\"orderId\":\"GOODS-1\",\"status\":\"IN_PROGRESS\",\"method\":\"카드\",\"totalAmount\":55000}");

        assertThatThrownBy(() -> gateway.confirm("pk_1", "GOODS-1", 55000, null))
                .isInstanceOfSatisfying(GeneralException.class, e -> {
                    assertThat(e.getErrorCode().getCode()).isEqualTo("PG_PROCESSING");
                    assertThat(e.getErrorCode().getStatus().value()).isEqualTo(503);
                });
    }

    /** 🔴 다른 주문의 승인된 paymentKey 를 보내도 우리 주문으로 승인하지 않습니다 */
    @Test
    void doneLookupForOtherOrderIsNotApproved() {
        server.expect(requestTo(CONFIRM))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"ALREADY_PROCESSED_PAYMENT\",\"message\":\"이미 처리된 결제 입니다.\"}"));
        expectLookup("{\"orderId\":\"GOODS-2\",\"status\":\"DONE\",\"method\":\"카드\",\"totalAmount\":55000}");

        assertThat(gateway.confirm("pk_1", "GOODS-1", 55000, null))
                .isEqualTo(new PaymentGateway.ConfirmResult(false, null, "ALREADY_PROCESSED_PAYMENT"));
    }

    /** 가상계좌처럼 입금을 기다리는 결제는 승인으로 보지 않습니다 */
    @Test
    void waitingForDepositIsNotApproved() {
        server.expect(requestTo(CONFIRM))
                .andRespond(withSuccess("{\"status\":\"WAITING_FOR_DEPOSIT\",\"method\":\"가상계좌\"}", MediaType.APPLICATION_JSON));

        assertThat(gateway.confirm("pk_1", "GOODS-1", 55000, null).approved()).isFalse();
    }

    /** ★ 5xx · 타임아웃은 거절이 아니라 "모름" (503) — 같은 주문은 같은 멱등키라 다시 불러도 토스가 한 번만 처리합니다 */
    @Test
    void serverErrorIsUnknownAndRetrySendsSameIdempotencyKey() {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            server.expect(requestTo(CONFIRM))
                    .andExpect(request -> keys.add(request.getHeaders().getFirst(TossPaymentGateway.IDEMPOTENCY_KEY)))
                    .andRespond(withServerError());
            server.expect(requestTo(LOOKUP))
                    .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                            .body("{\"code\":\"NOT_FOUND_PAYMENT\",\"message\":\"존재하지 않는 결제 입니다.\"}"));
        }

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> gateway.confirm("pk_1", "GOODS-1", 55000, null))
                    .isInstanceOfSatisfying(GeneralException.class,
                            e -> assertThat(e.getErrorCode().getCode()).isEqualTo("PG_UNAVAILABLE"));
        }
        assertThat(keys).doesNotContainNull().hasSize(2);
        assertThat(keys.get(0)).isEqualTo(keys.get(1));
    }

    @Test
    void cancelSendsReason() {
        server.expect(requestTo(BASE + "/v1/payments/pk_1/cancel"))
                .andExpect(content().json("{\"cancelReason\":\"USER_CANCEL\"}"))
                .andRespond(withSuccess("{\"status\":\"CANCELED\"}", MediaType.APPLICATION_JSON));

        assertThatCode(() -> gateway.cancel("pk_1", "USER_CANCEL")).doesNotThrowAnyException();
        server.verify();
    }

    /** 개발자센터에서 먼저 취소한 결제 — 이미 취소됐으면 성공으로 봅니다 (멱등) */
    @Test
    void alreadyCanceledIsSuccess() {
        server.expect(requestTo(BASE + "/v1/payments/pk_1/cancel"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"ALREADY_CANCELED_PAYMENT\",\"message\":\"이미 취소된 결제 입니다.\"}"));

        assertThatCode(() -> gateway.cancel("pk_1", "USER_CANCEL")).doesNotThrowAnyException();
    }

    @Test
    void cancelRejectedKeepsPaymentApproved() {
        server.expect(requestTo(BASE + "/v1/payments/pk_1/cancel"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"NOT_CANCELABLE_PAYMENT\",\"message\":\"취소 할 수 없는 결제 입니다.\"}"));

        assertThatThrownBy(() -> gateway.cancel("pk_1", "USER_CANCEL"))
                .isInstanceOfSatisfying(GeneralException.class,
                        e -> assertThat(e.getErrorCode().getCode()).isEqualTo("PG_CANCEL_REJECTED"));
    }

    private void expectLookup(String body) {
        server.expect(requestTo(LOOKUP)).andExpect(method(HttpMethod.GET)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }
}
