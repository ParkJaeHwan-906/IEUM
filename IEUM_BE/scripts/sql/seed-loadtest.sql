-- 부하 테스트 시드. 점주 1명, 가게 1개, 재고 100 인 상품 1개, 소비자 N명을 만든다.
-- 다시 실행하면 기존 부하 테스트 데이터를 지우고 새로 만든다 (이메일 도메인 @loadtest.ieum 기준).
--
-- 실행:  mysql -h 127.0.0.1 -P 3306 -u <MYSQL_USER> -p <MYSQL_DATABASE> < scripts/sql/seed-loadtest.sql
-- 전제:  두 서버를 한 번 기동해 Hibernate(ddl-auto=update) 가 테이블을 만든 뒤에 실행한다.
--
-- 고정 값 (k6 스크립트에서 그대로 사용)
--   점주   email owner@loadtest.ieum / password1 / account uid 11111111-1111-1111-1111-111111111111
--   가게   uid 22222222-2222-2222-2222-222222222222
--   상품   uid 33333333-3333-3333-3333-333333333333, initial_quantity 100
--   소비자 email consumer{1..N}@loadtest.ieum / password1
--
-- TODO(3단계 Redis): 시드 후 stock:{item_id} 키를 100 으로 SET 하는 단계 추가 (또는 API 서버 기동 시 워밍업)

SET @consumers = 1000;
SET @password_hash = '$2a$10$g.wCArFuHKZzC20xuWdkR.mkW.8Zzp9GCKGy4mR7UCQ.rGm0jLiDm';  -- BCrypt("password1")
SET @owner_uid = '11111111-1111-1111-1111-111111111111';
SET @store_uid = '22222222-2222-2222-2222-222222222222';
SET @item_uid  = '33333333-3333-3333-3333-333333333333';
SET SESSION cte_max_recursion_depth = 100000;

-- 기존 부하 테스트 데이터 제거 (FK 순서)
DELETE o FROM users_orders o
  JOIN stores_items i ON i.id = o.store_item_id
  JOIN stores s ON s.id = i.store_id
  JOIN users_account a ON a.id = s.user_account_id
  JOIN users u ON u.id = a.user_id
 WHERE u.email LIKE '%@loadtest.ieum';

DELETE o FROM users_orders o
  JOIN users_account a ON a.id = o.user_account_id
  JOIN users u ON u.id = a.user_id
 WHERE u.email LIKE '%@loadtest.ieum';

DELETE i FROM stores_items i
  JOIN stores s ON s.id = i.store_id
  JOIN users_account a ON a.id = s.user_account_id
  JOIN users u ON u.id = a.user_id
 WHERE u.email LIKE '%@loadtest.ieum';

DELETE s FROM stores s
  JOIN users_account a ON a.id = s.user_account_id
  JOIN users u ON u.id = a.user_id
 WHERE u.email LIKE '%@loadtest.ieum';

DELETE a FROM users_account a
  JOIN users u ON u.id = a.user_id
 WHERE u.email LIKE '%@loadtest.ieum';

DELETE FROM users WHERE email LIKE '%@loadtest.ieum';

-- 점주
INSERT INTO users (name, tel, email, created_at, updated_at)
VALUES ('부하점주', '01900000000', 'owner@loadtest.ieum', NOW(6), NOW(6));

INSERT INTO users_account (user_id, user_type, uid, nickname, password, created_at, updated_at)
SELECT id, 'BUSINESS_OWNER', @owner_uid, 'lt-owner', @password_hash, NOW(6), NOW(6)
  FROM users WHERE email = 'owner@loadtest.ieum';

-- 가게
INSERT INTO stores (user_account_id, uid, name, logo_img_url, store_type, open_at, close_at, shutdown_at, created_at, updated_at)
SELECT id, @store_uid, '부하 테스트 가게', NULL, 'BAKERY', '09:00:00', '22:00:00', NULL, NOW(6), NOW(6)
  FROM users_account WHERE uid = @owner_uid;

-- 상품 (재고 100)
INSERT INTO stores_items (store_id, uid, item_img_url, name, original_price, sale_price,
                          initial_quantity, remaining_quantity, last_order_time, version, created_at, updated_at)
SELECT id, @item_uid, NULL, '부하 테스트 상품', 10000, 5000,
       100, 100, DATE_ADD(NOW(6), INTERVAL 7 DAY), 0, NOW(6), NOW(6)
  FROM stores WHERE uid = @store_uid;

-- 소비자 N명
INSERT INTO users (name, tel, email, created_at, updated_at)
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < @consumers
)
SELECT CONCAT('소비자', n), CONCAT('019', LPAD(n, 8, '0')), CONCAT('consumer', n, '@loadtest.ieum'), NOW(6), NOW(6)
  FROM seq;

INSERT INTO users_account (user_id, user_type, uid, nickname, password, created_at, updated_at)
SELECT u.id, 'CONSUMER', UUID(),
       CONCAT('lt-consumer-', SUBSTRING_INDEX(SUBSTRING_INDEX(u.email, '@', 1), 'consumer', -1)),
       @password_hash, NOW(6), NOW(6)
  FROM users u
 WHERE u.email LIKE 'consumer%@loadtest.ieum';

SELECT (SELECT COUNT(*) FROM users WHERE email LIKE '%@loadtest.ieum')          AS users_seeded,
       (SELECT remaining_quantity FROM stores_items WHERE uid = @item_uid)       AS item_remaining;
