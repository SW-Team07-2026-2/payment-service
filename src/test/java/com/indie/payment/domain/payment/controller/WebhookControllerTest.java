package com.indie.payment.domain.payment.controller;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookControllerTest {

    private static final String SECRET = "webhook-secret-webhook-secret-123";
    private final WebhookController controller = new WebhookController(SECRET);

    @Test
    void validSignaturePasses() throws Exception {
        byte[] body = "{\"eventId\":\"e1\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(controller.valid(body, sign(body))).isTrue();
    }

    @Test
    void tamperedBodyOrMissingSignatureFails() throws Exception {
        byte[] body = "{\"eventId\":\"e1\"}".getBytes(StandardCharsets.UTF_8);
        String signature = sign(body);
        assertThat(controller.valid("{\"eventId\":\"e2\"}".getBytes(StandardCharsets.UTF_8), signature)).isFalse();
        assertThat(controller.valid(body, null)).isFalse();
        assertThat(controller.valid(body, "not-hex")).isFalse();
    }

    private static String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }
}
