-- payment 스키마 (구조) — 테이블 · 키 · 인덱스. 값 목록(CHECK)은 V2__payment_values.sql 에 있습니다.
-- Payment 는 누가 · 얼마를 · 어떤 주문에 결제했는지만 압니다. 상품 · 좌석 · 재고는 모릅니다 (교차 FK 없음)
-- 🔴 마이그레이션은 이 파일(V1 구조)과 V2(값) 두 개만 둡니다 — 구조를 바꾸면 이 파일을 고치고 DB 스키마를 비운 뒤 다시 올립니다.

CREATE TABLE payment (
    id                    BIGSERIAL PRIMARY KEY,
    idempotency_key       UUID         NOT NULL UNIQUE,   -- ★ 재결제 방지 — (order_type, order_ref) 로 결정적으로 만듭니다
    pg_order_id           VARCHAR(64)  NOT NULL UNIQUE,   -- 토스 orderId — prepare 때 우리가 발급
    payment_key           VARCHAR(200) UNIQUE,            -- 토스 paymentKey (최대 200자) — 승인에 성공하면 채움 · NULL = 승인 전
    requested_payment_key VARCHAR(200),                   -- 승인 요청에 쓴 paymentKey (84A) — 토스 응답을 못 받아도 조회할 수 있게.
                                                          --   클라이언트가 보낸 검증 전 값이라 UNIQUE 를 걸지 않습니다
    order_type            VARCHAR(20)  NOT NULL,          -- TICKET | GOODS (V2 ck_payment_order_type)
    order_ref             VARCHAR(50)  NOT NULL,          -- booking.id | purchase_order.id
    user_id               BIGINT       NOT NULL,          -- member.users.id (FK 없음 · 스키마 분리)
    amount                INTEGER      NOT NULL,          -- 원 단위 정수 · 호출자가 서버에서 계산한 값
    method                VARCHAR(20),
    status                VARCHAR(20)  NOT NULL,          -- PENDING | APPROVED | DECLINED | CANCELLED (V2 ck_payment_status)
    decline_reason        VARCHAR(100),
    metadata              JSONB,                          -- 호출자가 넘긴 값을 이벤트에 그대로 — Payment 는 해석하지 않음
    requested_at          TIMESTAMPTZ  NOT NULL,
    completed_at          TIMESTAMPTZ                     -- NULL = 미확정
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
