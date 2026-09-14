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
| 매장·상품 조회 | 더미 | `VITE_USE_MOCK=false` 로 바꾸면 `GET /api/stores/{uid}`, `/items` 등 실제 경로 호출 |
| 예약 생성·조회·취소 | 더미 | 실제 모드에서는 `Idempotency-Key` 헤더 포함 |
| 점주 매장·상품 등록, 예약 승인·준비·픽업 | 더미 | 실제 모드 경로는 `src/api/owner.ts` 참고 |

더미 모드에서는 로그인 페이지 하단의 "데모 소비자 / 데모 점주" 버튼으로 인증 서버 없이 화면을 둘러볼 수 있습니다.
더미 데이터는 localStorage `ieum.mock.v1` 에 저장되며 상단 배너의 "더미 초기화" 로 되돌립니다.

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
