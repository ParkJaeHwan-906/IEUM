// 부하 테스트용 Access Token 을 미리 뽑아 파일로 남긴다. duplicate-burst.js 가 SharedArray 로 읽는다.
// setup 반환값은 VU 마다 복사되므로 VU 1,000 시나리오에서는 토큰을 setup 으로 넘길 수 없다 (create-order.js 의 VU 100 상한과 같은 이유).
//
// 실행 (IEUM_BE 에서):  k6 run -e USERS=1000 scripts/k6/login-tokens.js
// 출력:                 scripts/k6/out/tokens.json  (out/ 은 .gitignore)
// 주의:                 측정이 Access Token TTL 보다 길면 중간에 401 이 난다. ieum-auth 를 ACCESS_TOKEN_TTL=PT3H 로 띄운다

import http from 'k6/http'

const AUTH_URL = __ENV.AUTH_URL || 'http://localhost:8081';
const USERS = Number(__ENV.USERS || 1000);
const LOGIN_BATCH = Number(__ENV.LOGIN_BATCH || 25);
const OUT = __ENV.OUT || 'scripts/k6/out/tokens.json';

export const options = {
    setupTimeout: '10m',
    scenarios: { noop: { executor: 'shared-iterations', vus: 1, iterations: 1 } },
};

export function setup() {
    const tokens = new Array(USERS);
    for (let start = 0; start < USERS; start += LOGIN_BATCH) {
        const end = Math.min(start + LOGIN_BATCH, USERS);
        const requests = [];
        for (let i = start; i < end; i++) {
            requests.push([
                'POST',
                `${AUTH_URL}/auth/login`,
                JSON.stringify({ email: `consumer${i + 1}@loadtest.ieum`, password: 'password1' }),
                { headers: { 'Content-Type': 'application/json' } },
            ]);
        }
        http.batch(requests).forEach((res, j) => {
            if (res.status !== 200) {
                throw new Error(`login failed: consumer${start + j + 1} -> ${res.status} ${res.body}`);
            }
            tokens[start + j] = res.json('accessToken');
        });
    }
    return { tokens };
}

export default function () {
}

export function handleSummary(data) {
    return {
        [OUT]: JSON.stringify(data.setup_data.tokens),
        stdout: `tokens: ${data.setup_data.tokens.length} -> ${OUT}\n`,
    };
}
