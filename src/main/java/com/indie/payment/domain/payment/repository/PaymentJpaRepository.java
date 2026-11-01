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

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentEntity p where p.orderType = :orderType and p.orderRef = :orderRef")
    Optional<PaymentEntity> lockByOrder(@Param("orderType") String orderType, @Param("orderRef") String orderRef);
}
