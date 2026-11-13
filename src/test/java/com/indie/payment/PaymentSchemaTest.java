package com.indie.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indie.payment.domain.payment.entity.PaymentEntity;
import com.indie.payment.domain.payment.repository.PaymentRepository;
import com.indie.payment.global.outbox.OutboxAppender;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 스키마 검증 — Flyway(V1 구조 · V2 값)로 스키마를 만들고 ddl-auto: validate 로 엔티티와 대조합니다 (어긋나면 컨텍스트 기동 실패).
 * Docker 가 없으면 건너뜁니다.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({OutboxAppender.class, PaymentSchemaTest.Beans.class})
class PaymentSchemaTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=payment");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @TestConfiguration
    static class Beans {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
    }

    @Autowired PaymentRepository repository;
    @Autowired OutboxAppender outbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    /** ★ 같은 주문의 두 번째 결제 행은 idempotency_key UNIQUE 에 걸립니다 (재결제 방지의 최종 방어선) */
    @Test
    void sameOrderCannotHaveTwoPayments() {
        UUID key = UUID.randomUUID();
        repository.save(new PaymentEntity(key, "GOODS-a1", "GOODS", "1", 7L, 1000, null, OffsetDateTime.now()));   // payment_key NULL (승인 전)
        assertThat(repository.lockByOrder("GOODS", "1")).isPresent();
        assertThatThrownBy(() -> repository.save(new PaymentEntity(key, "GOODS-a2", "GOODS", "1", 7L, 1000, null, OffsetDateTime.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** ★ 80B — 거절 뒤 다시 준비하면 주문 하나에 결제 행이 둘. 승인 · 취소는 마지막 시도만 잠급니다 (native FOR UPDATE) */
    @Test
    void lockByOrderTakesLatestAttempt() {
        repository.save(new PaymentEntity(UUID.randomUUID(), "TICKET-a1", "TICKET", "9", 7L, 1000, null, OffsetDateTime.now()));
        PaymentEntity retry = repository.save(new PaymentEntity(UUID.randomUUID(), "TICKET-a2", "TICKET", "9", 7L, 1000, null, OffsetDateTime.now()));
        em.flush();
        em.clear();

        assertThat(repository.lockByOrder("TICKET", "9")).get().extracting(PaymentEntity::getPgOrderId).isEqualTo("TICKET-a2");
        assertThat(repository.findFirstByOrderTypeAndOrderRefOrderByIdDesc("TICKET", "9")).get()
                .extracting(PaymentEntity::getId).isEqualTo(retry.getId());
        assertThat(repository.countByOrderTypeAndOrderRef("TICKET", "9")).isEqualTo(2);
    }

    /** ★ V2 의 CHECK 값 목록 = Java 쪽 값 — 한쪽만 고치면 여기서 깨집니다 */
    @Test
    void checkConstraintValuesMatchJavaEnums() {
        assertThat(checkValues("ck_payment_status"))
                .isEqualTo(new TreeSet<>(Arrays.stream(PaymentEntity.Status.values()).map(Enum::name).toList()));
        assertThat(checkValues("ck_payment_order_type")).containsExactly("GOODS", "TICKET");   // 요청 DTO 의 @Pattern("TICKET|GOODS")
    }

    private Set<String> checkValues(String constraint) {
        String def = jdbc.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?", String.class, constraint);
        Set<String> values = new TreeSet<>();
        Matcher m = Pattern.compile("'([A-Z0-9_]+)'").matcher(def);
        while (m.find()) values.add(m.group(1));
        return values;
    }

    /** 같은 트랜잭션에 jsonb 로 저장 (@DataJpaTest 는 테스트마다 트랜잭션) */
    @Test
    void outboxStoresJsonbPayloadInTransaction() {
        outbox.publish("payment.test", Map.of("paymentId", 7));
        em.flush();   // JPA 저장분을 JdbcTemplate 로 읽으려면

        assertThat(jdbc.queryForObject("SELECT payload->>'paymentId' FROM outbox WHERE event_type = 'payment.test'",
                String.class)).isEqualTo("7");
    }
}
