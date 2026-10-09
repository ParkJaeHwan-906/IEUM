# IEUM_FE

이음(IEUM) 프론트엔드. React 19 + TypeScript + Vite, 라우팅은 react-router-dom.

## 실행

```bash
npm install
cp .env.example .env
npm run dev        # http://localhost:3000 (백엔드 CORS 허용 origin과 동일)
npm run build      # tsc -b && vite build
npm run lint       # oxlint
```

## 환경 변수

| 변수 | 기본값 | 설명 |
|---|---|---|
| `VITE_AUTH_BASE_URL` | `http://localhost:8081` | 인증 서버 (ieum-auth) |
| `VITE_API_BASE_URL` | `http://localhost:8080` | API 서버 (ieum-api) |
| `VITE_USE_MOCK` | `true` | `true` 이면 매장·상품·예약을 브라우저 내 더미 데이터로 처리 |

## API 연동 상태

| 영역 | 상태 | 비고 |
|---|---|---|
| 회원가입 `POST /auth/signup` | 실제 연동 | Notion API 명세서 기준 |
| 로그인 `POST /auth/login` | 실제 연동 | 토큰은 localStorage `ieum.tokens` |
| 토큰 재발급 `POST /auth/refresh` | 실제 연동 | API 서버 401 시 1회 자동 재발급, 요청 직렬화 |
| 로그아웃 `POST /auth/logout` | 실제 연동 | |
| 매장 목록 `GET /api/stores` | 실제 연동 | 영업 중 매장 최신순 최대 50개. 홈·매장 목록 |
| 매장 상세 `GET /api/stores/{storeUid}` | 실제 연동 | |
| 매장 상품 `GET /api/stores/{storeUid}/items` | 실제 연동 | 홈 상품 목록은 매장별 호출을 합쳐서 표시 |
| 상품 상세 `GET /api/items/{itemUid}` | 실제 연동 | |
| 예약 생성 `POST /api/orders` | 실제 연동 | `Idempotency-Key` 는 예약 시도마다 1개 발급, 실패 후 재시도·연타 시 같은 키 재사용, 수량 변경 또는 성공 시 새 키 |
| 내 예약 `GET /api/orders/me` | 실제 연동 | 픽업 가능 상태면 응답의 `pickupCode`(6자리) 표시 |
| 예약 취소 `POST /api/orders/{orderId}/cancel` | 실제 연동 | 승인 대기·준비 중일 때만 버튼 노출 |
| 매장 등록 `POST /api/owner/stores` | 실제 연동 | |
| 내 매장 `GET /api/owner/stores/me` | 실제 연동 | |
| 영업시간 변경 `PATCH /api/owner/stores/{storeUid}` | 실제 연동 | |
| 영업 종료 `POST /api/owner/stores/{storeUid}/shutdown` | 실제 연동 | 진행 중 예약이 있으면 409 |
| 상품 등록 `POST /api/owner/stores/{storeUid}/items` | 실제 연동 | |
| 수량 조정 `PATCH /api/owner/stores/{storeUid}/items/{itemUid}/quantity` | 실제 연동 | 이미 예약된 수량 미만이면 409 |
| 판매 마감 `POST /api/owner/stores/{storeUid}/items/{itemUid}/close` | 실제 연동 | |
| 상품별 예약 `GET /api/owner/stores/{storeUid}/items/{itemUid}/orders[?state=]` | 실제 연동 | 점주 센터 예약 관리 탭의 상태 필터 |
| 예약 승인·준비 완료·거절 `POST /api/owner/orders/{orderId}/approve` `/ready` `/reject` | 실제 연동 | 상태별 버튼 노출, 거절은 승인 대기만 |
| 픽업 확인 `POST /api/owner/orders/{orderId}/pickup` | 실제 연동 | body `{ pickupCode }`, 소비자가 보여 준 6자리 입력 |

오류 응답은 RFC 7807 `application/problem+json` 의 `detail` 을 그대로 화면에 표시합니다. 503 은 `Retry-After` 를 읽을 수 있으면 대기 시간을 함께 안내합니다.

### 실제 백엔드로 실행

1. `IEUM_BE` 에서 MySQL·Redis 를 띄우고 인증 서버(8081)와 API 서버(8080)를 모두 실행합니다. 두 서버의 CORS 허용 origin 기본값은 `http://localhost:3000` 입니다 (`CORS_ALLOWED_ORIGINS`).
2. `.env` 에서 `VITE_USE_MOCK=false` 로 바꾸고 `npm run dev` 로 3000 포트에서 실행합니다. 다른 포트를 쓰면 CORS 에 막힙니다.
3. 점주 계정으로 매장·상품을 등록하고, 소비자 계정으로 예약한 뒤 점주 센터 예약 관리 탭에서 승인 → 준비 완료 → 픽업 코드 입력 순서로 진행합니다.

### 더미 모드

더미 모드에서는 로그인 페이지 하단의 "데모 소비자 / 데모 점주" 버튼으로 인증 서버 없이 화면을 둘러볼 수 있습니다.
인메모리 DB 는 백엔드와 같은 규칙을 따릅니다: 상품당 진행 중 예약 1건, 취소는 승인 대기·준비 중만, 거절은 승인 대기만, 준비 완료 시 픽업 코드 발급 후 픽업 시 검증, 예약된 수량 미만으로 수량 조정 불가, 진행 중 예약이 있으면 영업 종료 불가, 승인 대기 5분·픽업 대기 15분 경과 시 자동 취소·만료.
더미 데이터는 localStorage `ieum.mock.v2` 에 저장되며 상단 배너의 "더미 초기화" 로 되돌립니다.

## 구조

```
src/
  api/        http 클라이언트, 인증·매장·예약·점주 API (mock/real 분기)
  auth/       토큰 저장소, AuthContext
  mocks/      더미 seed 와 인메모리 DB
  pages/      Home, Stores, StoreDetail, ItemDetail, Orders, Login, Signup, Profile, Owner
  components/ Layout(헤더·하단 탭), ItemCard, StoreCard, ui
  lib/        포맷 유틸
  styles/     global.css (모바일 우선, 900px 이상 데스크톱 레이아웃)
  types/      백엔드 DTO 와 동일한 타입
```
