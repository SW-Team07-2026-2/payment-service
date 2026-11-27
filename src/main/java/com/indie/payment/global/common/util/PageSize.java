package com.indie.payment.global.common.util;

import com.indie.payment.global.config.PaymentProperties;
import org.springframework.stereotype.Component;

/** size 파라미터 - 기본 20, 최대 50 */
@Component
public class PageSize {

    private final PaymentProperties.Page props;

    public PageSize(PaymentProperties props) {
        this.props = props.page();
    }

    /**
     * 요청한 조회 개수에 기본값과 상한을 적용합니다.
     *
     * @param requested 요청한 조회 개수, null이면 기본값
     */
    public int resolve(Integer requested) {
        if (requested == null || requested <= 0) return props.size();
        return Math.min(requested, props.maxSize());
    }
}
