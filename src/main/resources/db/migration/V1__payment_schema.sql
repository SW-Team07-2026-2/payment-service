-- payment 스키마 — 11_결제서비스_설계 §2 기준 + payment_key · metadata
-- Payment 는 누가 · 얼마를 · 어떤 주문에 결제했는지만 압니다. 상품 · 좌석 · 재고는 모릅니다 (교차 FK 없음)

CREATE TABLE payment (
    id              BIGSERIAL PRIMARY KEY,
    idempotency_key UUID         NOT NULL UNIQUE,         -- ★ 재결제 방지 — (order_type, order_ref) 로 결정적으로 만듭니다
    payment_key     VARCHAR(64)  NOT NULL UNIQUE,         -- PG 결제 키 (prepare 때 발급 · confirm 의 식별자)
    order_type      VARCHAR(20)  NOT NULL,                -- TICKET | GOODS
    order_ref       VARCHAR(50)  NOT NULL,                -- booking.id | purchase_order.id
    user_id         BIGINT       NOT NULL,                -- member.users.id (FK 없음 · 스키마 분리)
    amount          INTEGER      NOT NULL,                -- 원 단위 정수 · 호출자가 서버에서 계산한 값
    method          VARCHAR(20),                          -- DUMMY_CARD
    status          VARCHAR(20)  NOT NULL,                -- PENDING | APPROVED | DECLINED | CANCELLED
    decline_reason  VARCHAR(100),
    metadata        JSONB,                                -- 호출자가 넘긴 값을 이벤트에 그대로 (albumIds) — Payment 는 해석하지 않음
    requested_at    TIMESTAMPTZ  NOT NULL,
    completed_at    TIMESTAMPTZ                           -- NULL = 미확정
);
CREATE INDEX idx_payment_order ON payment(order_type, order_ref);
CREATE INDEX idx_payment_user  ON payment(user_id, id DESC);

CREATE TABLE outbox (                                     -- 다른 서비스와 동일 구조
    id           BIGSERIAL PRIMARY KEY,
    event_id     UUID        NOT NULL UNIQUE,
    event_type   VARCHAR(64) NOT NULL,
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ                              -- NULL = 미발행
);
CREATE INDEX idx_outbox_unpublished ON outbox(id) WHERE published_at IS NULL;  -- ★ 부분 인덱스
