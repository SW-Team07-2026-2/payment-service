package com.indie.payment.global.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 컨트롤러 파라미터 {@code @CurrentUser Long userId} — Gateway 가 주입한 X-User-Id.
 * 컨트롤러가 헤더를 직접 읽는 코드는 한 곳도 없어야 합니다 (직접 읽는 곳에서 검증이 빠집니다).
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {}
