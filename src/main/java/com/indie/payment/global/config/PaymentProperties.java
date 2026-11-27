package com.indie.payment.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties("app")
public record PaymentProperties(Internal internal, Payment payment, Toss toss, Page page, Outbox outbox) {

    /** adminIssuers - /internal/admin/** 만 부를 수 있는 발급자 (Studio). allowedIssuers 는 그 밖의 /internal/** */
    public record Internal(String expectedAudience, List<String> allowedIssuers, List<String> adminIssuers, long clockSkewSeconds) {}

    /** 환불 재시도의 대기 시간과 실행 주기, 미승인 결제 만료의 유예 시간과 정리 주기를 설정합니다. */
    public record Payment(long ttlMinutes, long cancelRetryAfterSeconds, long cancelRetryIntervalMs,
                          long expiryGraceSeconds, long expiryIntervalMs) {}

    /** allowTestCode - X-Force-Decline 을 토스 테스트 코드로 보낼지. 로컬만 true (T4A, 운영은 무시) */
    public record Toss(String baseUrl, int connectTimeoutMs, int readTimeoutMs, boolean allowTestCode) {}

    public record Page(int size, int maxSize) {}

    public record Outbox(long relayIntervalMs, int batchSize) {}
}
