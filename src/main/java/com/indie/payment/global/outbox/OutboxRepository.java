package com.indie.payment.global.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEntity, Long> {

    /** ★ replica 2 가 동시에 릴레이해도 같은 행을 집지 않도록 SKIP LOCKED */
    @Query(value = """
            SELECT * FROM outbox WHERE published_at IS NULL
            ORDER BY id LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEntity> lockUnpublished(@Param("limit") int limit);

    @Modifying
    @Query(value = "DELETE FROM outbox WHERE published_at < now() - interval '7 days'", nativeQuery = true)
    int deletePublishedOlderThanWeek();
}
