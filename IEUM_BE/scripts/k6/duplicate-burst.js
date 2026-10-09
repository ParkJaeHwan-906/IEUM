// 부하 테스트: 동시 사용자 1,000 명이 같은 상품에 100 번씩 예약을 보낸다 (총 100,000 요청).
// 한 사용자의 요청을 BURST 개씩 http.batch 로 동시에 보내므로, 같은 사용자의 요청이 서버 안에서 실제로 겹친다.
// 이 겹침이 "중복 활성 예약" 검사의 틈을 드러낸다. 매 요청 새 Idempotency-Key 이므로 멱등 처리에는 걸리지 않는다 (같은 버튼을 여러 번 누른 상황).
//
// 전제:  seed-loadtest.sql 을 @consumers = 1000, @stock = 1000 으로 실행. login-tokens.js 로 tokens.json 생성.
//        ieum-api 는 SQL_LOG_LEVEL=warn, SQL_BIND_LOG_LEVEL=off.
// 첫 요청만 VU 마다 CONNECT_SPREAD_MS(기본 5ms) 씩 늦춘다. Windows 데스크톱은 listen backlog 가 200 으로 묶여 4,000 연결이 한 순간에 오면
// "connection refused" 가 나기 때문 (server.tomcat.accept-count 를 올려도 OS 상한에 걸림). 1,000 명이 약 5초에 걸쳐 연결한 뒤에는 keep-alive 로 재사용한다.
// 실행:  IEUM_BE 에서  k6 run scripts/k6/duplicate-burst.js
//        조정        k6 run -e REQUESTS=20 -e BURST=4 scripts/k6/duplicate-burst.js
//
// 결과 읽는 법:
//   order_created      201. 정확히 @stock 이어야 정상 (사용자 수 = 재고이므로 한 사람당 정확히 1건)
//   order_sold_out     409 재고 소진
//   order_duplicate    409 이미 진행 중인 예약
//   order_contention   503 재시도 상한 또는 락 대기 초과
//   order_other        그 밖의 응답. 0 이 아니면 서버 로그 확인
//   DB 에서 "사용자당 활성 예약 2건 이상" 이 0 인지 반드시 확인한다 (reset-loadtest.sql 상단 쿼리)

import http from 'k6/http'
import { Counter } from 'k6/metrics'
import { SharedArray } from 'k6/data'
import { sleep } from 'k6'
import exec from 'k6/execution'
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const API_URL = __ENV.API_URL || 'http://localhost:8080';
const TOKENS = __ENV.TOKENS || './out/tokens.json';
const REQUESTS = Number(__ENV.REQUESTS || 100);
const BURST = Number(__ENV.BURST || 4);
const CONNECT_SPREAD_MS = Number(__ENV.CONNECT_SPREAD_MS || 5);
const ITEM_UID = '33333333-3333-3333-3333-333333333333';

const tokens = new SharedArray('tokens', () => JSON.parse(open(TOKENS)));
const USERS = Number(__ENV.USERS || tokens.length);

const created = new Counter('order_created');
const soldOut = new Counter('order_sold_out');
const duplicate = new Counter('order_duplicate');
const contention = new Counter('order_contention');
const other = new Counter('order_other');

export const options = {
    scenarios: {
        burst: {
            executor: 'per-vu-iterations',
            vus: USERS,
            iterations: Math.ceil(REQUESTS / BURST),
            maxDuration: '4h',
        },
    },
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
    thresholds: {
        'http_req_duration{name:create-order}': ['p(99)<600000'],
    },
};

export default function () {
    if (exec.vu.iterationInScenario === 0) {
        sleep((exec.vu.idInTest - 1) * CONNECT_SPREAD_MS / 1000);
    }
    const token = tokens[exec.vu.idInTest - 1];
    const requests = [];
    for (let i = 0; i < BURST; i++) {
        requests.push([
            'POST',
            `${API_URL}/api/orders`,
            JSON.stringify({ itemUid: ITEM_UID, quantity: 1 }),
            {
                headers: {
                    'Content-Type': 'application/json',
                    Authorization: `Bearer ${token}`,
                    'Idempotency-Key': uuidv4(),
                },
                tags: { name: 'create-order' },
                responseCallback: http.expectedStatuses(201, 409),
            },
        ]);
    }
    http.batch(requests).forEach((res) => classify(res));
}

function classify(res) {
    if (res.status === 201) {
        created.add(1);
    } else if (res.status === 409 && res.body && res.body.includes('재고')) {
        soldOut.add(1);
    } else if (res.status === 409 && res.body && res.body.includes('진행 중인 예약')) {
        duplicate.add(1);
    } else if (res.status === 503) {
        contention.add(1);
    } else {
        other.add(1, { status: String(res.status) });
        if (__ENV.DEBUG) {
            console.warn(`${res.status} ${res.error || ''} ${(res.body || '').slice(0, 160)}`);
        }
    }
}
