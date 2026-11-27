package com.indie.payment.domain.payment.controller;

import com.indie.payment.domain.payment.exception.PaymentErrorCode;
import com.indie.payment.global.common.exception.GeneralException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * PG 웹훅 -> Gateway -> Payment의 HMAC 서명 검증.
 * 현재 토스 웹훅은 연결되지 않았고 수신 로그만 남깁니다.
 * TODO: 실제 연동 시 eventId 중복 처리와 결제 상태 반영을 추가합니다.
 */
@RestController
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final byte[] secret;

    public WebhookController(@Value("${payment.webhook-secret}") String secret) {
        this.secret = secret.trim().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 웹훅 본문의 HMAC 서명을 검증하고 수신 로그를 남깁니다.
     *
     * @param provider 웹훅을 보낸 PG 식별자
     * @param signature 요청 본문을 검증할 HMAC 서명
     * @param body 서명 검증에 사용할 원본 본문
     */
    @PostMapping("/api/payments/webhook/{provider}")
    public ResponseEntity<Void> receive(@PathVariable String provider,
                                        @RequestHeader(value = "X-Signature", required = false) String signature,
                                        @RequestBody byte[] body) {
        String rejected = rejectReason(body, signature);
        if (rejected != null) {
            log.warn("webhook_signature_invalid provider={} reason={}", provider, rejected);
            throw new GeneralException(PaymentErrorCode.INVALID_SIGNATURE);
        }
        log.info("webhook_received provider={} bytes={}", provider, body.length);
        return ResponseEntity.ok().build();
    }

    /** 통과면 null - 아니면 사유 (응답은 INVALID_SIGNATURE 하나, 사유는 로그로만) */
    String rejectReason(byte[] body, String signature) {
        if (signature == null || signature.isBlank()) return "missing";
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] expected = mac.doFinal(body);
            byte[] actual = HexFormat.of().parseHex(signature.trim().toLowerCase());
            return MessageDigest.isEqual(expected, actual) ? null : "mismatch";           // 상수 시간 비교
        } catch (IllegalArgumentException e) {
            return "malformed";   // 16진수가 아님
        } catch (GeneralSecurityException e) {
            return "mac";
        }
    }
}
