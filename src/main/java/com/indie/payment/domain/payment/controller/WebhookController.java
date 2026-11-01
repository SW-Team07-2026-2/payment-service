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
 * P5 — PG 웹훅 (15 §5.5). 토스 웹훅은 아직 연결하지 않아 자리만 둡니다 (승인 · 취소는 서버가 직접 호출해서 결과를 받음).
 * JWT 가 없는 대신 X-Signature = hex(HMAC-SHA256(공유 시크릿, 본문)) 를 검증합니다 (Gateway 는 이 경로만 인증 · CSRF 면제).
 * TODO(결정대기): 실제 PG 연동 시 — eventId 멱등 저장(PG 는 같은 웹훅을 여러 번 보냄) + 결제 상태 반영
 */
@RestController
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final byte[] secret;

    public WebhookController(@Value("${payment.webhook-secret}") String secret) {
        this.secret = secret.trim().getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/api/payments/webhook/{provider}")
    public ResponseEntity<Void> receive(@PathVariable String provider,
                                        @RequestHeader(value = "X-Signature", required = false) String signature,
                                        @RequestBody byte[] body) {
        if (!valid(body, signature)) {
            log.warn("webhook_signature_invalid provider={}", provider);
            throw new GeneralException(PaymentErrorCode.INVALID_SIGNATURE);
        }
        log.info("webhook_received provider={} bytes={}", provider, body.length);
        return ResponseEntity.ok().build();
    }

    boolean valid(byte[] body, String signature) {
        if (signature == null || signature.isBlank()) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] expected = mac.doFinal(body);
            byte[] actual = HexFormat.of().parseHex(signature.trim().toLowerCase());
            return MessageDigest.isEqual(expected, actual);           // ★ 상수 시간 비교
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            return false;
        }
    }
}
