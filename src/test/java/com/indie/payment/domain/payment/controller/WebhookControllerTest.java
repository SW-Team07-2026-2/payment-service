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
        assertThat(controller.rejectReason(body, sign(body))).isNull();
    }

    @Test
    void tamperedBodyOrMissingSignatureFails() throws Exception {
        byte[] body = "{\"eventId\":\"e1\"}".getBytes(StandardCharsets.UTF_8);
        String signature = sign(body);
        assertThat(controller.rejectReason("{\"eventId\":\"e2\"}".getBytes(StandardCharsets.UTF_8), signature)).isEqualTo("mismatch");
        assertThat(controller.rejectReason(body, null)).isEqualTo("missing");
        assertThat(controller.rejectReason(body, "not-hex")).isEqualTo("malformed");
    }

    private static String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }
}
