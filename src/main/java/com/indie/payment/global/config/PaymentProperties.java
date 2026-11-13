package com.indie.payment.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties("app")
public record PaymentProperties(Internal internal, Payment payment, Toss toss, Page page, Outbox outbox) {

    /** adminIssuers — /internal/admin/** 만 부를 수 있는 발급자 (Studio). allowedIssuers 는 그 밖의 /internal/** */
    public record Internal(String expectedAudience, List<String> allowedIssuers, List<String> adminIssuers, long clockSkewSeconds) {}

    public record Payment(long ttlMinutes) {}

    /** allowTestCode — X-Force-Decline 을 토스 테스트 코드로 보낼지. 로컬만 true (11/04 T4A · 운영은 무시) */
    public record Toss(String baseUrl, int connectTimeoutMs, int readTimeoutMs, boolean allowTestCode) {}

    public record Page(int size, int maxSize) {}

    public record Outbox(long relayIntervalMs, int batchSize) {}
}
