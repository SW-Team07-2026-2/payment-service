package com.indie.payment.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indie.payment.global.auth.CurrentUserArgumentResolver;
import com.indie.payment.global.auth.InternalTokenFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Clock;
import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final CurrentUserArgumentResolver currentUserArgumentResolver;

    public WebConfig(CurrentUserArgumentResolver currentUserArgumentResolver) {
        this.currentUserArgumentResolver = currentUserArgumentResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserArgumentResolver);
    }

    /** /internal/** 에만 건다 — /actuator/** 는 대상이 아닙니다 */
    @Bean
    public FilterRegistrationBean<InternalTokenFilter> internalTokenFilter(@Value("${internal.shared-secret}") String sharedSecret,
                                                                           PaymentProperties props, Clock clock,
                                                                           ObjectMapper objectMapper) {
        FilterRegistrationBean<InternalTokenFilter> registration = new FilterRegistrationBean<>(
                new InternalTokenFilter(sharedSecret, props.internal(), clock, objectMapper));
        registration.addUrlPatterns("/internal/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);   // CorrelationIdFilter 다음
        return registration;
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
