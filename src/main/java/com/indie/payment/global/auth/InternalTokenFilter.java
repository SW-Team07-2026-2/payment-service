package com.indie.payment.global.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indie.payment.global.common.exception.CommonErrorCode;
import com.indie.payment.global.common.response.ApiResponse;
import com.indie.payment.global.config.PaymentProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;

/**
 * /internal/** 전용 — X-Internal-Token (HS256 · 공유 시크릿) 검증 (Member 명세 §13 과 동일 · aud = payment).
 * <pre>
 * ① 헤더 없음 → 401 INTERNAL_TOKEN_REQUIRED   ② 서명 · ③ iss/aud · exp 없음 → 401 INTERNAL_TOKEN_INVALID (사유는 로그)
 * ④ exp(+30초) 지남 → 401 INTERNAL_TOKEN_EXPIRED
 * </pre>
 * 🔴 이 필터 없이 배포되면 클러스터 안의 누구든 금액을 정해 결제를 준비 · 취소할 수 있습니다.
 * 🔴 X-User-Id 를 신뢰하지 않습니다 — 내부 API 의 사용자 식별은 요청 본문 값으로 합니다.
 */
public class InternalTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Token";
    private static final Logger log = LoggerFactory.getLogger(InternalTokenFilter.class);

    private final MACVerifier verifier;
    private final PaymentProperties.Internal props;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public InternalTokenFilter(String sharedSecret, PaymentProperties.Internal props, Clock clock, ObjectMapper objectMapper) {
        try {
            this.verifier = new MACVerifier(sharedSecret.trim().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("internal.shared-secret 은 32바이트 이상이어야 합니다", e);
        }
        this.props = props;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = request.getHeader(HEADER);
        if (token == null || token.isBlank()) {
            reject(request, response, CommonErrorCode.INTERNAL_TOKEN_REQUIRED, "missing");
            return;
        }
        String rejected = check(token);
        if (rejected != null) {
            reject(request, response, "expired".equals(rejected)
                    ? CommonErrorCode.INTERNAL_TOKEN_EXPIRED : CommonErrorCode.INTERNAL_TOKEN_INVALID, rejected);
            return;
        }
        chain.doFilter(request, response);
    }

    /** 통과면 null — 아니면 거절 사유 (expired 만 응답 코드가 다르고 나머지는 로그로만 구분) */
    private String check(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())) return "alg";   // ★ alg 고정
            if (!jwt.verify(verifier)) return "signature";
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (claims.getIssuer() == null || !props.allowedIssuers().contains(claims.getIssuer())) return "issuer";
            if (claims.getAudience() == null || !claims.getAudience().contains(props.expectedAudience())) return "audience";
            Date exp = claims.getExpirationTime();
            if (exp == null) return "exp";
            Instant now = clock.instant();
            return now.isBefore(exp.toInstant().plusSeconds(props.clockSkewSeconds())) ? null : "expired";
        } catch (Exception e) {
            return "parse";
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, CommonErrorCode code, String reason) throws IOException {
        log.warn("internal_token_rejected code={} reason={} path={} remote={}", code, reason, request.getRequestURI(), request.getRemoteAddr());
        response.setStatus(code.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ApiResponse.onFailure(code, code.getMessage(), null));
    }
}
