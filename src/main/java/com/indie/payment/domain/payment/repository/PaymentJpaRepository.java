package com.indie.payment.domain.payment.repository;

import com.indie.payment.domain.payment.entity.PaymentEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PaymentJpaRepository extends JpaRepository<PaymentEntity, Long>, PaymentRepository {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentEntity p where p.pgOrderId = :pgOrderId")
    Optional<PaymentEntity> lockByPgOrderId(@Param("pgOrderId") String pgOrderId);

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentEntity p where p.id = :id and p.userId = :userId")
    Optional<PaymentEntity> lockByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /** 마지막 시도만 — 거절된 앞선 결제는 끝난 행이라 건드리지 않습니다 (11/04 80B) */
    @Override
    @Query(value = "SELECT * FROM payment WHERE order_type = :orderType AND order_ref = :orderRef ORDER BY id DESC LIMIT 1 FOR UPDATE",
            nativeQuery = true)
    Optional<PaymentEntity> lockByOrder(@Param("orderType") String orderType, @Param("orderRef") String orderRef);
}
