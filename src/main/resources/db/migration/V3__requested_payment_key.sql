-- 11/04 (84A) 승인 결과를 모를 때 — 토스를 부르기 전에 승인 요청에 쓴 paymentKey 를 남깁니다.
-- 토스가 응답하지 않아 503 이 나도 이 값이 커밋되어 있어, 정리(cancel) 때 토스를 paymentKey 로 조회할 수 있습니다
-- (주문서형 시크릿 키 gsk 는 orderId 조회 API 를 부를 수 없음).
-- 클라이언트가 보낸 검증 전 값이라 UNIQUE 를 걸지 않습니다 — 남의 키를 먼저 넣어 그 결제를 막지 못하게. 검증된 키는 승인 때 payment_key 에.
ALTER TABLE payment ADD COLUMN requested_payment_key VARCHAR(200);
