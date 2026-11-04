package com.indie.payment.domain.payment.client;

import com.indie.payment.domain.payment.exception.PaymentErrorCode;
import com.indie.payment.global.common.exception.GeneralException;
import com.indie.payment.global.config.PaymentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * 토스페이먼츠 — 결제 승인 · 취소 (서버 API · 시크릿 키). 결제창 인증은 프론트가 SDK(클라이언트 키)로 합니다.
 * 🔴 테스트 키(test_…)만 받습니다 — 라이브 키면 기동하지 않습니다. 테스트 키 결제는 토스가 실제와 같은 흐름으로 승인하지만 돈은 나가지 않습니다.
 * 라이브 전환 때 이 검사를 지우세요.
 * 승인 · 취소에는 결제마다 고정된 Idempotency-Key 를 붙입니다 — 타임아웃 뒤 다시 불러도 토스는 첫 결과를 돌려줍니다 (15일).
 */
@Component
public class TossPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(TossPaymentGateway.class);

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    /** 넣은 에러 코드를 그대로 재현합니다 (REJECT_CARD_PAYMENT …) — allowTestCode(로컬)일 때만 보냅니다 · 라이브 키면 토스가 무시합니다 */
    static final String TEST_CODE = "TossPayments-Test-Code";
    /** 같은 멱등키의 첫 요청이 아직 처리 중 — 거절이 아니라 "모름" */
    private static final String IDEMPOTENT_REQUEST_PROCESSING = "IDEMPOTENT_REQUEST_PROCESSING";

    private final RestClient rest;
    private final boolean allowTestCode;

    @Autowired
    public TossPaymentGateway(PaymentProperties props, @Value("${toss.secret-key}") String secretKey) {
        this(RestClient.builder().requestFactory(requestFactory(props.toss())), props.toss().baseUrl(), secretKey,
                props.toss().allowTestCode());
    }

    TossPaymentGateway(RestClient.Builder builder, String baseUrl, String secretKey, boolean allowTestCode) {
        this.allowTestCode = allowTestCode;
        String key = secretKey.trim();
        if (!key.startsWith("test_")) {
            throw new IllegalStateException("toss.secret-key 는 테스트 키(test_…)만 허용합니다 — 실결제 차단");
        }
        String basic = Base64.getEncoder().encodeToString((key + ":").getBytes(StandardCharsets.UTF_8));   // ★ 끝의 콜론
        this.rest = builder.baseUrl(baseUrl).defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic).build();
    }

    /**
     * 200 + DONE 만 승인. 실패는 바로 단정하지 않고 결제를 조회해 다시 확인합니다 (토스 권장) — 이미 승인됐을 수 있습니다.
     * 조회해도 승인이 아니면 4xx 는 거절(사유 = 토스 에러 코드), 처리 중(409) · 불통 · 5xx · 타임아웃은 결과를 모르므로 503
     */
    @Override
    public ConfirmResult confirm(String paymentKey, String orderId, int amount, String testCode) {
        String failure;   // 토스 에러 코드 — 불통 · 5xx · 타임아웃이면 null
        try {
            Confirmed confirmed = rest.post().uri("/v1/payments/confirm")
                    .headers(h -> {
                        h.set(IDEMPOTENCY_KEY, idempotencyKey("confirm", orderId));
                        if (allowTestCode && testCode != null && !testCode.isBlank()) h.set(TEST_CODE, testCode);
                    })
                    .body(new ConfirmRequest(paymentKey, orderId, amount))
                    .exchange((req, res) -> {
                        if (res.getStatusCode().is2xxSuccessful()) {
                            TossPayment payment = res.bodyTo(TossPayment.class);
                            if (payment == null) throw new RestClientException("empty confirm response");
                            return new Confirmed(payment, null);
                        }
                        if (res.getStatusCode().is4xxClientError()) {
                            TossError error = res.bodyTo(TossError.class);
                            String code = error == null || error.code() == null ? "HTTP_" + res.getStatusCode().value() : error.code();
                            log.info("toss_confirm_failed orderId={} code={} message={}", orderId, code,
                                    error == null ? null : error.message());
                            return new Confirmed(null, code);
                        }
                        throw new RestClientException("status " + res.getStatusCode());
                    });
            if (confirmed.payment() != null) {
                TossPayment payment = confirmed.payment();
                if ("DONE".equals(payment.status())) return new ConfirmResult(true, payment.method(), null);
                // 가상계좌(WAITING_FOR_DEPOSIT)처럼 승인이 나중에 끝나는 결제는 받지 않습니다 — 입금 웹훅이 없음
                log.warn("toss_confirm_not_done orderId={} status={}", orderId, payment.status());
                return new ConfirmResult(false, null, payment.status());
            }
            failure = confirmed.errorCode();
        } catch (RestClientException e) {
            log.warn("toss_confirm_unavailable orderId={} error={}", orderId, e.getMessage());
            failure = null;
        }

        // 🔴 클라이언트가 보낸 paymentKey 라 주문번호 · 금액이 우리 것과 같을 때만 승인으로 봅니다
        TossPayment found = lookup(paymentKey);
        if (found != null && "DONE".equals(found.status()) && orderId.equals(found.orderId()) && found.totalAmount() == amount) {
            log.warn("toss_confirm_recovered orderId={} failure={}", orderId, failure);
            return new ConfirmResult(true, found.method(), null);
        }
        if (failure == null) throw new GeneralException(PaymentErrorCode.PG_UNAVAILABLE);                       // 토스 불통 · 타임아웃
        if (IDEMPOTENT_REQUEST_PROCESSING.equals(failure)) throw new GeneralException(PaymentErrorCode.PG_PROCESSING);   // 첫 요청이 아직 처리 중
        return new ConfirmResult(false, null, failure);
    }

    /** paymentKey 로 결제 조회 — 주문서형 시크릿 키(gsk)로는 orderId 조회를 부를 수 없습니다. 없으면 null · 불통이면 503 */
    private TossPayment lookup(String paymentKey) {
        try {
            return rest.get().uri("/v1/payments/{paymentKey}", paymentKey)
                    .exchange((req, res) -> {
                        if (res.getStatusCode().is2xxSuccessful()) return res.bodyTo(TossPayment.class);
                        if (res.getStatusCode().is4xxClientError()) return null;
                        throw new RestClientException("status " + res.getStatusCode());
                    });
        } catch (RestClientException e) {
            log.warn("toss_lookup_unavailable paymentKey={} error={}", paymentKey, e.getMessage());
            throw new GeneralException(PaymentErrorCode.PG_UNAVAILABLE);
        }
    }

    @Override
    public void cancel(String paymentKey, String reason) {
        try {
            rest.post().uri("/v1/payments/{paymentKey}/cancel", paymentKey)
                    .header(IDEMPOTENCY_KEY, idempotencyKey("cancel", paymentKey))
                    .body(new CancelRequest(reason))
                    .exchange((req, res) -> {
                        if (res.getStatusCode().is2xxSuccessful()) return null;
                        if (res.getStatusCode().is4xxClientError()) {
                            TossError error = res.bodyTo(TossError.class);
                            if (error != null && "ALREADY_CANCELED_PAYMENT".equals(error.code())) return null;   // 개발자센터에서 먼저 취소한 경우 등
                            log.warn("toss_cancel_rejected paymentKey={} code={} message={}", paymentKey,
                                    error == null ? null : error.code(), error == null ? null : error.message());
                            throw new GeneralException(PaymentErrorCode.PG_CANCEL_REJECTED);
                        }
                        throw new RestClientException("status " + res.getStatusCode());
                    });
        } catch (RestClientException e) {
            log.warn("toss_cancel_unavailable paymentKey={} error={}", paymentKey, e.getMessage());
            throw new GeneralException(PaymentErrorCode.PG_UNAVAILABLE);
        }
    }

    private static String idempotencyKey(String op, String id) {
        return UUID.nameUUIDFromBytes((op + ":" + id).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static SimpleClientHttpRequestFactory requestFactory(PaymentProperties.Toss toss) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(toss.connectTimeoutMs());
        factory.setReadTimeout(toss.readTimeoutMs());
        return factory;
    }

    record ConfirmRequest(String paymentKey, String orderId, int amount) {}

    record CancelRequest(String cancelReason) {}

    /** Payment 객체 중 쓰는 필드만 — method 는 "카드" · "간편결제" … */
    record TossPayment(String orderId, String status, String method, int totalAmount) {}

    /** 승인 응답 — 2xx 면 payment, 4xx 면 errorCode */
    private record Confirmed(TossPayment payment, String errorCode) {}

    record TossError(String code, String message) {}
}
