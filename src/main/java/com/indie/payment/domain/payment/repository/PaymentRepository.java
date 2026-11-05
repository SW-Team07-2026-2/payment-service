package com.indie.payment.domain.payment.repository;

import com.indie.payment.domain.payment.entity.PaymentEntity;
import org.springframework.data.domain.Limit;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 결제 저장소 포트 — 구현은 PaymentJpaRepository.
 * lock* 은 상태 전이용 행 잠금 — confirm 과 cancel 이 동시에 와도 하나씩 처리됩니다.
 */
public interface PaymentRepository {

    Optional<PaymentEntity> findByIdempotencyKey(UUID idempotencyKey);

    /** ✅ 소유권은 항상 조회 조건에 (IDOR) */
    Optional<PaymentEntity> findByIdAndUserId(Long id, Long userId);

    /** 같은 주문의 마지막 결제 — 거절된 뒤 다시 준비하면 주문 하나에 결제 행이 늘어납니다 (11/04 80B) */
    Optional<PaymentEntity> findFirstByOrderTypeAndOrderRefOrderByIdDesc(String orderType, String orderRef);

    long countByOrderTypeAndOrderRef(String orderType, String orderRef);

    List<PaymentEntity> findByUserIdOrderByIdDesc(Long userId, Limit limit);

    List<PaymentEntity> findByUserIdAndIdLessThanOrderByIdDesc(Long userId, Long id, Limit limit);

    /** IDENTITY 라 즉시 INSERT 됩니다 — idempotency_key 충돌은 이 호출에서 예외로 드러납니다 */
    PaymentEntity save(PaymentEntity payment);

    Optional<PaymentEntity> lockByPgOrderId(String pgOrderId);

    Optional<PaymentEntity> lockByIdAndUserId(Long id, Long userId);

    /** 같은 주문의 마지막 결제를 잠급니다 */
    Optional<PaymentEntity> lockByOrder(String orderType, String orderRef);
}
