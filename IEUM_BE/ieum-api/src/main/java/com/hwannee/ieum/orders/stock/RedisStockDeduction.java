package com.hwannee.ieum.orders.stock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// 3단계: Redis 를 재고 원장으로 쓰고 Lua 스크립트 한 번으로 "확인 후 차감"을 원자적으로 끝낸다.
// 분산락(SETNX/Redisson)은 채택하지 않는다. 락 TTL·커밋 전 해제 문제가 있고 여전히 직렬화라 처리량이 락 보유 시간에 묶인다.
//
// TODO(3단계 키 설계): stock:{itemId} = remaining. 상품 등록·재고 조정 시 DB 값으로 초기화(SET). 워밍업 전략 필요
// TODO(3단계 Lua deduct):
//   local remaining = tonumber(redis.call('GET', KEYS[1]))
//   if remaining == nil then return -2 end                  -- 키 없음 → DB 에서 적재 후 재시도
//   if remaining < tonumber(ARGV[1]) then return -1 end     -- 재고 부족
//   return redis.call('DECRBY', KEYS[1], ARGV[1])
//   RedisTemplate.execute(DefaultRedisScript<Long>) 로 실행. 스크립트는 resources/redis/*.lua 로 두고 SHA 캐시
// TODO(3단계 Lua restore): 예약당 1회만 복구. restored:{orderId} 키를 SETNX 로 먼저 잡고 성공한 경우에만 INCRBY
// TODO(3단계 정합성): Redis 차감 성공 후 DB 저장(UsersOrders)이 실패하면 보상 복구. 여기부터 문제가 "동시성"에서 "Redis↔DB 정합성"으로 옮겨 간다
//   - 주기적으로 stock:{itemId} 와 SQL 불변식(initial = remaining + active + pickedUp) 을 대조하는 Reconciliation Job
//   - 어긋남 건수를 지표로 노출 (README Reservation Correctness 대시보드)
@Component
@ConditionalOnProperty(name = "ieum.stock.strategy", havingValue = "redis")
public class RedisStockDeduction implements StockDeductionStrategy {

    @Override
    public void deduct(Long itemId, int quantity) {
        throw new UnsupportedOperationException("3단계에서 구현");
    }

    @Override
    public void restore(Long itemId, int quantity) {
        throw new UnsupportedOperationException("3단계에서 구현");
    }
}
