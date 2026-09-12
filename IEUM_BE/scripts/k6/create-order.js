// 부하 테스트: 재고 100 인 상품 하나에 소비자 10,000 명이 동시에 1개씩 예약한다 (todo 2.1 동시성 비교의 공통 시나리오).
// 세 전략(naive / optimistic / redis)을 STOCK_STRATEGY 만 바꿔 같은 스크립트로 돌리고 결과를 비교한다.
//
// 전제:  seed-loadtest.sql 로 소비자 CONSUMERS 명이 들어가 있고, ieum-auth(8081)·ieum-api(8080) 가 떠 있다.
//        .env 에서 SQL_LOG_LEVEL=warn, SQL_BIND_LOG_LEVEL=off 로 두지 않으면 로그 출력이 병목이 되어 지연 수치가 왜곡된다.
// 실행:  IEUM_BE 에서  k6 run scripts/k6/create-order.js
//        VU 수 조정   k6 run -e VUS=50 scripts/k6/create-order.js
// 라운드 사이:  reset-loadtest.sql 로 재고·주문을 되돌린다.
//
// 결과 읽는 법:
//   created 201   성공 건수. 정확히 100 이어야 정상. 1단계(naive)에서는 100 을 넘는 것(초과 예약)이 재현 목표
//   sold out 409  재고 소진 이후의 정상 거절
//   http_req_duration{name:create-order}  예약 요청만의 지연. p(99) 를 기록한다
//   http_req_failed  0 이 아니면 500 이 섞인 것. API 서버 로그 확인
//   DB 불변식은 reset-loadtest.sql 상단 주석의 쿼리로 확인 (initial = remaining + 활성 주문 수량)

import http from 'k6/http'
import { check } from 'k6'
import exec from 'k6/execution'
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const AUTH_URL = __ENV.AUTH_URL || 'http://localhost:8081';
const API_URL = __ENV.API_URL || 'http://localhost:8080';
// seed-loadtest.sql 의 @consumers 와 같아야 한다. 1인 1요청이므로 총 요청 수이기도 하다
const CONSUMERS = Number(__ENV.CONSUMERS || 10000);
// setup 의 로그인 병렬 묶음 크기. 인증 서버 커넥션 풀이 5개라 너무 키우면 커넥션 대기로 로그인이 실패한다
const LOGIN_BATCH = Number(__ENV.LOGIN_BATCH || 25);
// seed-loadtest.sql 의 고정 상품 uid (재고 100)
const ITEM_UID = '33333333-3333-3333-3333-333333333333';

export const options = {
    // 로그인 10,000 건이 setup 에서 끝나야 한다. 기본 60s 로는 부족
    setupTimeout: '10m',
    scenarios: {
        order: {
            // 총 iterations 를 VU 들이 나눠 가진다. 정확히 CONSUMERS 번만 요청되고, 각 반복은 iterationInTest 로 고유 번호를 받는다
            executor: 'shared-iterations',
            // setup 이 돌려준 토큰 배열(약 7MB)이 VU 마다 복사되므로 100 을 넘기지 않는다.
            // 더 올리려면 토큰을 파일로 뽑아 SharedArray 로 읽는 방식으로 바꿔야 한다
            vus: Number(__ENV.VUS || 100),
            iterations: CONSUMERS,
            maxDuration: '10m',
        },
    },
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
    // 통과/실패 판정이 목적이 아니라 태그별 항목을 요약에 따로 찍기 위한 것. 그래서 값은 일부러 느슨하다.
    // 이게 없으면 setup 의 로그인 지연이 http_req_duration 에 섞여 예약 지연을 읽을 수 없다
    thresholds: {
        'http_req_duration{name:create-order}': ['p(99)<60000'],
        'http_req_duration{name:login}': ['p(99)<60000'],
    },
};

// 로그인은 부하 구간에 섞이면 BCrypt 비용 때문에 인증 서버 지연을 재게 되므로 setup 에서 전부 끝낸다.
// setup 은 VU 하나에서 순차 실행이라 하나씩 보내면 15분 가까이 걸려 Access Token TTL 과 충돌한다 → http.batch 로 병렬 전송
export function setup() {
    const tokens = new Array(CONSUMERS);
    for (let start = 0; start < CONSUMERS; start += LOGIN_BATCH) {
        const end = Math.min(start + LOGIN_BATCH, CONSUMERS);
        const requests = [];
        for (let i = start; i < end; i++) {
            requests.push([
                'POST',
                `${AUTH_URL}/auth/login`,
                // 시드의 이메일 규칙 consumer{1..N}@loadtest.ieum / password1
                JSON.stringify({ email: `consumer${i + 1}@loadtest.ieum`, password: 'password1' }),
                { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } },
            ]);
        }
        const responses = http.batch(requests);
        responses.forEach((res, j) => {
            // 토큰이 하나라도 비면 그 반복이 401 로 빠져 성공 건수 해석이 흐려지므로 여기서 중단한다
            if (res.status !== 200) {
                throw new Error(`login failed: consumer${start + j + 1} -> ${res.status} ${res.body}`);
            }
            tokens[start + j] = res.json('accessToken');
        });
    }
    // 반환값이 각 VU 의 default 함수에 data 로 전달된다
    return { tokens };
}

export default function (data) {
    // iterationInTest 는 시나리오 전체에서 0..CONSUMERS-1 로 고유하다. 소비자 한 명이 한 번만 요청하게 하는 핵심.
    // 같은 소비자가 두 번 요청하면 재고 경합이 아니라 DuplicateActiveOrder(409) 검사에 걸린다
    const token = data.tokens[exec.scenario.iterationInTest];
    const res = http.post(
        `${API_URL}/api/orders`,
        // quantity 1 고정. 그래야 "201 이 정확히 100 건" 이라는 기준으로 초과 예약을 바로 읽는다
        JSON.stringify({ itemUid: ITEM_UID, quantity: 1 }),
        {
            headers: {
                'Content-Type': 'application/json',
                Authorization: `Bearer ${token}`,
                // 필수 헤더. 없으면 400. 매 요청 새 값이므로 멱등 처리에는 걸리지 않는다
                'Idempotency-Key': uuidv4(),
            },
            tags: { name: 'create-order' },
        },
    );
    // 둘 다 실패하는 응답(401, 500 등)은 두 체크 모두에서 빠지므로 합이 총 요청 수보다 작으면 그만큼이 오류다
    check(res, {
        'created 201': (r) => r.status === 201,
        'sold out 409': (r) => r.status === 409,
    });
}
