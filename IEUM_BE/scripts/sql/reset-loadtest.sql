-- 부하 테스트 한 라운드가 끝난 뒤 다음 라운드를 위해 상품 재고와 주문만 되돌린다. 사용자·가게·상품은 유지.
-- 실행:  mysql -h 127.0.0.1 -P 3306 -u <MYSQL_USER> -p <MYSQL_DATABASE> < scripts/sql/reset-loadtest.sql
--
-- 실행 전에 이번 라운드의 결과를 먼저 기록한다:
--   SELECT order_state, COUNT(*), SUM(quantity) FROM users_orders o JOIN stores_items i ON i.id = o.store_item_id
--    WHERE i.uid = '33333333-3333-3333-3333-333333333333' GROUP BY order_state;
--   SELECT initial_quantity, remaining_quantity FROM stores_items WHERE uid = '33333333-3333-3333-3333-333333333333';
--   불변식: initial = remaining + sum(active.quantity) + sum(PICKED_UP.quantity). 1단계에서는 이것이 깨지는 것이 정상(초과 예약)
--
-- TODO(3단계 Redis): stock:{item_id} 키도 100 으로 되돌리는 단계 추가

SET @item_uid = '33333333-3333-3333-3333-333333333333';

DELETE o FROM users_orders o
  JOIN stores_items i ON i.id = o.store_item_id
 WHERE i.uid = @item_uid;

UPDATE stores_items
   SET remaining_quantity = initial_quantity,
       version = 0,
       last_order_time = DATE_ADD(NOW(6), INTERVAL 7 DAY)
 WHERE uid = @item_uid;

SELECT initial_quantity, remaining_quantity, version FROM stores_items WHERE uid = @item_uid;
