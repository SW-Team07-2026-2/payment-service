-- payment 값 목록 — 컬럼에 들어갈 수 있는 값(Java enum 과 같은 목록)을 CHECK 로 고정합니다. 구조는 V1__payment_schema.sql.
-- 값을 넣거나 빼면 이 파일과 Java 쪽을 함께 고칩니다 — PaymentSchemaTest.checkConstraintValuesMatchJavaEnums 가 둘을 대조합니다.

ALTER TABLE payment ADD CONSTRAINT ck_payment_order_type CHECK (order_type IN ('TICKET','GOODS'));                       -- 요청 DTO 의 @Pattern("TICKET|GOODS")
ALTER TABLE payment ADD CONSTRAINT ck_payment_status     CHECK (status IN ('PENDING','APPROVED','DECLINED','CANCELLED')); -- PaymentEntity.Status
