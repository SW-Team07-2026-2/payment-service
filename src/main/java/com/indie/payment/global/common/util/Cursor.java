package com.indie.payment.global.common.util;

import com.indie.payment.global.common.exception.CommonErrorCode;
import com.indie.payment.global.common.exception.GeneralException;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;

/**
 * 커서 — (시각, id) 복합 키를 base64url 로 감쌉니다. OFFSET 을 쓰지 않습니다
 * (뒤 페이지로 갈수록 느려지고, 새 활동이 들어오면 같은 항목이 두 페이지에 나타납니다).
 * at 이 없는 커서(id 단독)도 허용합니다 — 결제 내역.
 */
public record Cursor(OffsetDateTime at, long id) {

    public String encode() {
        String raw = (at == null ? "" : at.toString()) + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** null · 빈 값이면 첫 페이지 (null 반환). 형식이 깨졌으면 400 */
    public static Cursor decode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            int sep = raw.lastIndexOf('|');
            String at = raw.substring(0, sep);
            return new Cursor(at.isEmpty() ? null : OffsetDateTime.parse(at), Long.parseLong(raw.substring(sep + 1)));
        } catch (RuntimeException e) {
            throw new GeneralException(CommonErrorCode.INVALID_CURSOR);
        }
    }
}
