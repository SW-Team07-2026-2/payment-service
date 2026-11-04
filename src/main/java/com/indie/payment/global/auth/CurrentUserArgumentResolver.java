package com.indie.payment.global.auth;

import com.indie.payment.global.common.exception.CommonErrorCode;
import com.indie.payment.global.common.exception.GeneralException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * X-User-Id → Long. Payment 는 자기 앞의 요청이 인증됐다고 가정합니다 — 헤더가 곧 신원이고,
 * 그 헤더를 Gateway 만 붙일 수 있게 하는 것은 NetworkPolicy 의 책임입니다.
 * 헤더가 없으면 401 + WARN — 이 로그가 찍히면 Gateway 우회 또는 라우트 설정 오류입니다.
 */
@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    public static final String HEADER = "X-User-Id";
    private static final Logger log = LoggerFactory.getLogger(CurrentUserArgumentResolver.class);

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class) && Long.class.equals(parameter.getParameterType());
    }

    @Override
    public Long resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                NativeWebRequest request, WebDataBinderFactory binderFactory) {
        String value = request.getHeader(HEADER);
        if (value == null || value.isBlank()) {
            log.warn("missing_user_header path={}", request.getDescription(false));
            throw new GeneralException(CommonErrorCode.UNAUTHENTICATED);
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            log.warn("invalid_user_header");
            throw new GeneralException(CommonErrorCode.UNAUTHENTICATED);
        }
    }
}
