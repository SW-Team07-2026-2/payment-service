package com.indie.payment.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties("app")
public record PaymentProperties(Internal internal, Payment payment, Toss toss, Page page, Outbox outbox) {

    public record Internal(String expectedAudience, List<String> allowedIssuers, long clockSkewSeconds) {}

    public record Payment(long ttlMinutes) {}

    public record Toss(String baseUrl, int connectTimeoutMs, int readTimeoutMs) {}

    public record Page(int size, int maxSize) {}

    public record Outbox(long relayIntervalMs, int batchSize) {}
}
