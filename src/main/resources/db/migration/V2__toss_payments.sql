-- 토스페이먼츠 전환 (10/28) — paymentKey 는 토스가 결제창 인증 뒤에 발급합니다.
-- prepare 때는 우리가 만든 토스 orderId(pg_order_id)만 있고, payment_key 는 승인에 성공하면 채웁니다.

ALTER TABLE payment ADD COLUMN pg_order_id VARCHAR(64);
UPDATE payment SET pg_order_id = payment_key;                     -- 더미 PG 시절 행 — NOT NULL · UNIQUE 를 걸 수 있게 값만 채움
ALTER TABLE payment ALTER COLUMN pg_order_id SET NOT NULL;
ALTER TABLE payment ADD CONSTRAINT payment_pg_order_id_key UNIQUE (pg_order_id);

ALTER TABLE payment ALTER COLUMN payment_key DROP NOT NULL;       -- NULL = 승인 전
ALTER TABLE payment ALTER COLUMN payment_key TYPE VARCHAR(200);   -- 토스 paymentKey 최대 200자
