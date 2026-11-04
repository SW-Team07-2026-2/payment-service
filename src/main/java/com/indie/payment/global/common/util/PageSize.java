package com.indie.payment.global.common.util;

import com.indie.payment.global.config.PaymentProperties;
import org.springframework.stereotype.Component;

/** size 파라미터 — 기본 20 · 최대 50 */
@Component
public class PageSize {

    private final PaymentProperties.Page props;

    public PageSize(PaymentProperties props) {
        this.props = props.page();
    }

    public int resolve(Integer requested) {
        if (requested == null || requested <= 0) return props.size();
        return Math.min(requested, props.maxSize());
    }
}
