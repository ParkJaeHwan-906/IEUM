# TODO

IEUM 백엔드 작업 목록. 완료된 항목은 체크하고, 배경 설명이 필요한 결정은 [ADR](./adr)에 남깁니다.

---

## 진행 상황

- [x] 도메인 엔티티 및 리포지토리 정의
- [x] 로컬 개발 환경 (Docker Compose - MySQL 8.4, Redis 7.4)
- [x] 환경변수 외부화 (`.env` + `application.yaml` 플레이스홀더)
- [x] 인증/인가 — 인증 서버 분리, 단위 테스트, Postman 통합 확인, ADR-0002 (2026-09-12 완료)
- [~] **예약 도메인 로직** ← 현재 단계 (선결 과제 결정 완료, 구현 착수 전)
- [ ] 부하 테스트 및 관측
- [ ] V2 - Kafka 예약 대기열
- [ ] V3 - Kubernetes Scale-out

---

## 1. 인증 / 인가

설계 배경과 대안 검토는 [ADR-0001](./adr/0001-authentication.md), 구현 순서와 함정은
[구현 가이드](./guides/auth-implementation-guide.md) 참조.

인증 서버를 별도 프로세스로 분리하기로 했습니다 (Kubernetes 에서 인증 서버 1개, API Pod 는 서명 검증만).
저장소는 세 모듈로 나뉘었고, 로컬에서는 `ieum-auth`(8081) 와 `ieum-api`(8080) 를 둘 다 띄웁니다.

~~~text
ieum-domain/   엔티티·리포지토리 (공유)
ieum-auth/     인증 서버 — com.hwannee.ieum.auth.issue
ieum-api/      API 서버  — com.hwannee.ieum.auth.verify
~~~

- [x] [ADR-0002](./adr/0002-auth-server-split.md) 작성 — ADR-0001 의 "프로세스는 나누지 않는다"를 대체하는 기록
- [x] 두 서버 기동 후 Postman 으로 signup → login → API 호출 → refresh → logout 확인 (2026-09-12)

### 1.1 기반

- [x] Gradle 멀티모듈 분리 (`ieum-domain`, `ieum-auth`, `ieum-api`)
- [x] `SecurityConfig` 작성
  - [x] 인증 서버 — `anyRequest().denyAll()`, `/auth/*`·JWKS·health 만 `permitAll`
  - [x] API 서버 — `anyRequest().authenticated()`, 상품 조회·health 만 `permitAll`
  - [x] `SessionCreationPolicy.STATELESS` (양쪽)
  - [x] CSRF 비활성 (양쪽)
  - [x] CORS 설정 (양쪽, `CORS_ALLOWED_ORIGINS`)
- [x] JWT 라이브러리 — 검증은 `spring-boot-starter-oauth2-resource-server`, 발급은 `spring-security-oauth2-jose` 의 `NimbusJwtEncoder`
- [x] 서명 키 — RS256. `JwtKeyConfig` 가 `JWT_PRIVATE_KEY`(Base64 PKCS#8) 를 읽고 공개키를 복원
  - 키가 없으면 임시 키 생성 + WARN. 재시작 시 토큰 무효
  - [x] `scripts/gen-jwt-key.sh` 작성 (`IEUM_BE/scripts/`, 출력이 `MIIEv` 로 시작해야 PKCS#8)
  - [x] 무중단 키 교체 — `JWT_PREVIOUS_KEYS`(쉼표 구분) 의 공개 부분을 JWKS 에 함께 공개. 서명은 현재 키만
    - 절차: 직전 키를 `JWT_PREVIOUS_KEYS` 로 옮기고 새 키를 `JWT_PRIVATE_KEY` 에 → 재시작 → `ACCESS_TOKEN_TTL` 경과 후 `JWT_PREVIOUS_KEYS` 비움
- [x] JWKS 공개 — `GET /.well-known/jwks.json`, 공개키만 노출 확인
- [x] `AuthExceptionAdvice` — `ProblemDetailAuthHandlers` 가 넘긴 401/403 과 `AuthException`·unique 경합(409) 을 `ProblemDetail` 로 변환
  - `@RestControllerAdvice` 여야 함. `@RestController` 로 두면 막힌 경로가 401 이 아니라 200 빈 본문으로 나감 (실제로 겪음)
  - `AuthenticationException` / `AccessDeniedException` / `RSAKey` 는 이름이 같은 JDK 클래스가 있어 자동 import 확인 필요
- [x] `spring-boot-starter-actuator` 추가 (양쪽)

### 1.2 패키지 구조

- [x] `ieum-auth` / `auth/issue/` — `config`·`jwt`·`token`·`service`(`SignupService`, `AuthService`)·`exception`·`web`(`AuthController`, Advice, DTO)
  - [x] `SignupService` 를 `auth/issue/service/` 로 이동
- [x] `ieum-api` / `auth/verify/` — `config`(`SecurityConfig`, `CorsConfig`, `WebMvcSecurityConfig`)·`web`(`ProblemDetailAuthHandlers`, `SecurityExceptionAdvice`)·`principal`(`AuthenticatedUser`, `@CurrentUser`, `CurrentUserArgumentResolver`)

### 1.3 계정

- [x] 회원가입 API — `POST /auth/signup` 201, 응답은 `{ uid }` 만 (내부 PK 비노출)
  - [x] `UsersAccount.password` 는 `SignupService` 에서 `BCryptPasswordEncoder` 로 인코딩해 저장
  - [x] 컬럼 길이 확인 — BCrypt 해시는 60자, 현재 `length = 100` 이므로 충분
  - [x] 요청 DTO 검증 길이를 컬럼에 맞춤 (`name` 10, `tel` 11, `email` 100, `nickname` 20, `password` 8~72)
  - [ ] `UserType` 별 가입 분기 (CONSUMER / BUSINESS_OWNER)
  - [ ] BUSINESS_OWNER 는 `BusinessRegistration` 검증 절차 필요 여부 결정
  - [ ] ADMIN 계정 생성 경로 결정 — 회원가입은 ADMIN 을 400 으로 거부하므로 현재 만들 방법이 없음
    - 후보: 기동 시 환경변수로 시드(`ApplicationRunner`) / DB 직접 승격 / 기존 ADMIN 만 호출 가능한 승격 API
- [x] 로그인 API — `POST /auth/login`, `AuthService.login`
  - [x] 계정이 없어도 dummy hash 로 BCrypt 비교를 한 번 수행해 응답 시간 차이를 없앰
- [x] `UserType` → Spring Security 권한(`ROLE_*`) 매핑 — API 서버 `JwtGrantedAuthoritiesConverter`, `role` 클레임에 `ROLE_` 접두. `hasRole("BUSINESS_OWNER")` 로 검사
- [x] 토큰 subject 는 `UsersAccount.uid` (내부 PK 비노출)
- [x] `UsersAccountRepository` 가 `Users` 타입으로 잘못 선언되어 있던 것을 수정, 조회 메서드 추가

### 1.4 토큰 수명 주기

- [x] Refresh Token 저장소 — `RefreshTokenStore`. Redis 에 SHA-256 해시 키로 저장, `getAndDelete`(GETDEL) 로 한 번만 소비
- [x] 토큰 재발급 API — `POST /auth/refresh` (consume → uid 로 계정 재조회 → 새 쌍 발급)
- [x] 로그아웃 — `POST /auth/logout` (`RefreshTokenStore.revoke`, 유효 여부와 무관하게 204)
- [ ] Access Token 블랙리스트 필요 여부 결정
  - stateless JWT 는 만료 전 강제 무효화가 불가능. 수명을 짧게(15분 내외) 가져가는 것으로 대체 가능한지 검토
  - 대안: 역할별 수명 차등 (ADMIN 5분 등). `AccessTokenIssuer` 에서 role 에 따라 TTL 분기
- [ ] uid 기준 세션 인덱스 — `auth:sessions:{uid}` SET 에 토큰 해시 보관
  - 현재는 토큰 해시로만 키를 잡아 "이 사용자의 Refresh Token 전부" 조회가 불가능 (SCAN 필요 → 운영 불가)
  - 발급 시 `SADD`, 소비/폐기 시 `SREM`, 강제 로그아웃 시 `SMEMBERS` → `DEL`
  - 계정 정지·비밀번호 변경 시 강제 로그아웃, 동시 로그인 수 제한의 선결 과제
- [ ] Refresh Token 재사용 탐지
  - 이미 소비된 토큰이 다시 오면 탈취로 간주하고 해당 uid 세션 전부 폐기 (위 인덱스 필요)
  - 소비된 해시를 짧은 TTL 로 `auth:refresh:used:{hash}` 에 남겨 두면 감지 가능
- [ ] 로그인 시도 속도 제한 — 이메일·IP 기준. `dummyHash` 타이밍 방어의 보완
- [ ] 운영 Redis 설정
  - AOF 또는 RDB 영속화 — 재시작 시 Refresh Token 이 전부 사라지면 전원 재로그인
  - `rename-command KEYS ""` — 조회는 `SCAN` 만 허용
- [ ] 클라이언트 토큰 보관 규약 문서화 — Access Token 은 메모리, Refresh Token 은 HttpOnly 쿠키 권장. `Authorization` 헤더는 로그에 남기지 않기

### 1.5 인가

- [x] `@EnableMethodSecurity` 활성화 (API 서버 `SecurityConfig`)
- [ ] 역할 검사 — `@PreAuthorize` 로 BUSINESS_OWNER 전용 API 제한
  - 상품 등록/수정/삭제, 주문 승인, 픽업 완료 처리
- [ ] 소유권 검사를 서비스 계층 규약으로 정립
  - `UsersOrders` — 취소/조회는 본인만
  - `StoresItems` — 수정은 해당 `Stores` 의 점주만
  - `ItemsReviews` — 수정/삭제는 작성자만
  - `ReviewReports` — 처리는 ADMIN 만
- [ ] **주문 생성 시 사용자 식별자는 요청 본문이 아닌 토큰에서 추출**
  - 본문의 `userId` 를 신뢰하면 멱등 키 정책(`userId + Idempotency-Key`)이 무력화됨
- [x] 인증된 사용자를 컨트롤러에 주입하는 방식 — `@CurrentUser AuthenticatedUser` (uid·role·nickname). permitAll 경로에서 쓰면 401

### 1.6 예외 처리

- [x] 401 / 403 을 `ProblemDetail` 로 응답 — 인증 서버 `AuthExceptionAdvice`, API 서버 `SecurityExceptionAdvice` (401 에 `WWW-Authenticate: Bearer`)
  - `spring.mvc.problemdetails.enabled: true` 는 이미 켜져 있음
  - 단, `AuthenticationEntryPoint` / `AccessDeniedHandler` 는 필터 계층이라 별도 처리 필요
- [x] 인증 실패 사유를 응답에 과도하게 노출하지 않기 — 로그인 실패는 단일 메시지, API 401 은 서명/만료 사유 미노출, 로그아웃은 항상 204
  - [x] 타이밍 방어 — 계정이 없어도 `dummyHash` 로 BCrypt 비교를 한 번 수행해 응답 시간 차이 제거
- [ ] Bean Validation 400 에 필드별 오류 포함 여부 결정 — 현재는 Spring 기본 detail `Invalid request content.` 만 나감

### 1.7 테스트

Docker 없이 도는 테스트만 두었다 (`@WebMvcTest` + 순수 단위). `contextLoads` 는 MySQL·Redis 가 필요하므로
패키지 필터로 제외해 실행한다: `./gradlew :ieum-auth:test :ieum-api:test --tests "com.hwannee.ieum.auth.*"`

- [x] `ieum-auth` / `JwtKeyConfigTest` — PKCS#8 로드·임시 키 fallback·JWKS 에 개인키 성분 없음·중복 previous-key 건너뛰기
- [x] `ieum-auth` / `AccessTokenIssuerTest` — 발급 → 공개키 검증 왕복, 다른 키 `BadJwtException`, 다른 issuer `JwtValidationException`
- [x] `ieum-auth` / `AuthControllerTest` — `@WebMvcTest`, 서비스 `@MockitoBean`. JWKS 공개 범위·로그인 성공/실패·검증 400·회원가입 201/409/400·refresh 401·logout 204·나머지 경로 401
- [x] `ieum-api` / `SecurityConfigTest` — `@WebMvcTest` + `JwtDecoder` 모킹, 테스트 소스의 `ProbeController` 로 검증
  - [x] 토큰 없이 보호된 엔드포인트 호출 시 401 (`WWW-Authenticate: Bearer`, `application/problem+json`)
  - [x] 없는 경로도 401 (default-deny)
  - [x] 잘못된 토큰 401, 사유 미노출
  - [x] 유효 토큰 → `@CurrentUser` 주입
  - [x] 권한 없는 역할로 호출 시 403 / 역할 일치 200
  - [x] permitAll 경로에서 `@CurrentUser` 사용 시 401
  - 잘못된 토큰 모킹은 `BadJwtException` 이어야 함. `JwtException` 은 `AuthenticationServiceException` 으로 감싸져 경로가 다름
  - `@WebMvcTest` 는 `@Configuration`·`@Component` 를 스캔하지 않으므로 `SecurityConfig`·`ProblemDetailAuthHandlers` 는 `@Import` 필수
  - Spring Boot 4: `@WebMvcTest` 는 `org.springframework.boot.webmvc.test.autoconfigure`, `@MockitoBean` 은 `org.springframework.test.context.bean.override.mockito`
- [ ] 타인의 리소스 접근 시 403 — 소유권 검사(1.5) 구현 후

---

## 2. 예약 도메인

### 2.1 선결 과제

- [x] 예약 상태 모델 — **점주 승인 절차를 유지한다** (2026-09-12 결정). 코드의 `OrderState` 가 기준이고 README 를 코드에 맞춘다
  - 코드: `PENDING → APPROVED → READY_FOR_PICKUP → PICKED_UP`, 어디서든 `CANCELED`
  - [x] `EXPIRED` 위치 — **`READY_FOR_PICKUP` 진입 후 15분 미픽업** (2026-09-12 결정). 노쇼 방지와 빠른 회전이 목적이며 만료 시 재고를 복구한다
    - `lastOrderTime` 과는 무관. `PENDING` 과 `APPROVED` 에는 만료가 없고 점주의 승인·취소로만 빠져나간다
    - [ ] 미승인 `PENDING` 이 방치되면 재고가 잠긴 채 남는다 — 점주 미응답 시 자동 취소를 둘지, 운영 알림으로 갈지 결정 필요
  - 재고 흐름: 예약 생성(`PENDING`) 시 차감 → `PICKED_UP` 이면 소진 확정 → `CANCELED`·`EXPIRED` 이면 복구. 복구는 주문당 정확히 1회
  - [x] README 의 상태 표를 코드에 맞게 수정 (`EXPIRED` 포함 6개 상태, 불변식의 필드명도 `initialQuantity`/`remainingQuantity` 로)
- [x] 재고 필드 — `StoresItems.initialQuantity` / `remainingQuantity` (`initial_quantity` / `remaining_quantity`). `decreaseQuantity` / `increaseQuantity` 에 하한·상한 검사 있음
- [x] 동시성 제어 방식 — 포트폴리오 목적으로 **세 단계를 모두 구현하고 같은 시나리오(재고 100 / 요청 10,000)로 비교 측정**한다
  1. 잠금 없음 — `remainingQuantity` 조회 후 차감. 초과 예약이 실제로 발생하는 것을 먼저 보인다
  2. `@Version` 낙관적 락 — `StoresItems.version` 충돌 시 `OptimisticLockException`. 재시도 정책과 함께. 정합성은 맞지만 실패율·재시도 폭주·DB 병목을 측정한다
  3. Redis — Lua Script 로 원자적 차감 (README 설계). Redis 를 재고 원장으로 쓰고 DB 는 결과를 기록
     - 분산락(SETNX/Redisson)은 채택하지 않음. 락 TTL·커밋 전 해제 문제가 있고 여전히 직렬화라 처리량 상한이 락 보유 시간에 묶임. 문서에 절충안으로만 한 줄 언급
     - 문제가 "동시성"에서 "Redis↔DB 정합성"으로 옮겨 감 → 2.2 의 재고 복구 멱등성·Expiry Worker·Reconciliation 이 그 답
  - (선택) 2 와 3 사이에 조건부 UPDATE 한 문장(`SET remaining = remaining - ? WHERE id = ? AND remaining >= ?`) 을 중간 데이터 포인트로 추가. `@Version` 없이도 정합성이 맞고 재시도가 없어, 낙관적 락의 비용이 어디서 오는지 분리해 보여 줌
  - 측정 항목: 최종 `remainingQuantity`, 성공 건수(정확히 100 이어야 함), p99 지연, DB 커넥션 대기, 재시도 횟수
  - 각 단계는 프로파일 또는 전략 인터페이스로 갈아 끼울 수 있게 두고 결과를 [ADR-0003](./adr/0003-stock-deduction-concurrency.md) 에 남긴다 (2026-09-12 작성, 1단계까지 기록)

### 2.2 구현

- [~] Service / Controller 계층 — 뼈대 생성 (2026-09-12). `ieum-api` / `orders/` 아래 `config`·`stock`·`exception`·`service`·`web`
  - `StockDeductionStrategy` 인터페이스 + `Naive`/`OptimisticLock`/`Redis` 구현체. `STOCK_STRATEGY` 로 하나만 빈 등록
  - `OrderService` — create(판매 조건·중복 활성 예약 검사 포함, 멱등키는 TODO), findMine, cancel(소비자, uid 조건 조회로 타인 주문은 404), approve/readyForPickup/pickUp(점주, 서비스 계층 소유권 검사)
  - `OrderController`(`/api/orders`, CONSUMER) / `OwnerOrderController`(`/api/owner/orders`, BUSINESS_OWNER 클래스 레벨 `@PreAuthorize`)
  - 도메인: `OrderState.EXPIRED`·`ACTIVE` 집합, `UsersOrders.readyAt`·전이 가드·`expire()`, `InvalidOrderStateException`, 리포지토리 조회 메서드
  - 남은 TODO 는 코드의 `// TODO(...)` 주석에 있음. 라벨은 이 문서의 절 번호와 맞춤
  - [x] 1단계 k6 시나리오로 초과 예약 재현 후 결과 기록 (2026-09-12) — 201 이 1,996건, 초과 예약 1,896건, 최종 remaining 0. 전문은 [performance/2026-09-12-stage1-naive.md](./performance/2026-09-12-stage1-naive.md)
    - SQL 로그(`debug`/`trace`)가 켜진 채 측정됨. 지연 비교용으로 `SQL_LOG_LEVEL=warn`, `SQL_BIND_LOG_LEVEL=off` 로 한 번 더 돌려 기록에 덧붙인다
    - `Thread.sleep` 없이도 재현되므로 넣지 않는다
  - [ ] **2단계 낙관적 락 + 재시도 계층** ← 다음 작업
    - [ ] 0. 1단계를 `SQL_LOG_LEVEL=warn`, `SQL_BIND_LOG_LEVEL=off` 로 재측정해 performance 기록에 덧붙임 (지연 비교의 기준선)
    - [ ] 1. 재시도 계층 — `OrderService.create` 를 감싸는 별도 빈 (`orders/service/OrderCreateRetrier` 또는 유사)
      - 왜 별도 빈인가: `create` 가 `@Transactional` 이라 충돌은 커밋 시점에 프록시 밖으로 `ObjectOptimisticLockingFailureException` 으로 나온다. 같은 빈 안에서 catch 해 재호출하면 프록시를 거치지 않아 새 트랜잭션이 열리지 않는다
      - 컨트롤러는 `OrderService` 가 아니라 이 빈을 호출. `naive`·`redis` 전략에서는 충돌이 없어 한 번에 통과하므로 전략과 무관하게 같은 경로를 탄다
      - 수동 루프로 시작 (spring-retry 는 AOP 가 한 겹 더 생겨 계측이 흐려짐). 필요해지면 교체
      - 재시도 대상은 `ObjectOptimisticLockingFailureException` 만. `InsufficientStock`·`DuplicateActiveOrder` 등 `ApiException` 은 확정된 답이므로 즉시 반환
      - 재시도마다 새 트랜잭션이 재고를 다시 읽으므로, 그 사이 재고가 0 이 되면 `InsufficientStock` 으로 끝난다 (재고 없는데 재시도 반복하지 않음)
      - 첫 시도가 롤백되면 주문도 저장되지 않으므로 재시도에서 `DuplicateActiveOrder` 오탐은 없다
    - [ ] 2. 설정값 — `OrderProperties` 에 `retry.maxAttempts` (기본 3), `retry.backoff` (기본 `PT0.01S`, 지터 포함 여부 결정). `.env.example` 에 `STOCK_RETRY_MAX_ATTEMPTS`, `STOCK_RETRY_BACKOFF`
    - [ ] 3. 최종 실패 응답 결정 — 현재 `ApiExceptionAdvice` 는 409 "요청이 몰려 처리하지 못했습니다". 503 + `Retry-After` 가 의미상 맞는지 검토. 재시도 계층 이후 이 핸들러에 도달하는 것은 소진된 요청뿐이어야 함
    - [ ] 4. 계측 — `micrometer-registry-prometheus` 추가, `management.endpoints.web.exposure.include=health,prometheus`, `/actuator/prometheus` 접근 정책 결정 (permitAll 또는 별도 포트)
      - 카운터 `order.create.attempts` (tag: `outcome` = success | conflict | exhausted), 히스토그램 또는 tag 로 "성공까지 걸린 시도 횟수"
      - HikariCP 는 actuator 가 자동 노출 (`hikaricp.connections.pending`, `hikaricp.connections.acquire`). 측정 중 스크랩할 방법 결정 (Prometheus 컨테이너 vs 측정 직후 `curl` 로 스냅샷)
    - [ ] 5. 테스트 — 재시도 빈 단위 테스트: 두 번 충돌 후 성공이면 3회 호출, 상한 초과면 예외 그대로 전파, `InsufficientStock` 은 재시도 없이 즉시 전파, 카운터 값 검증
    - [ ] 6. 측정 — `STOCK_STRATEGY=optimistic` 으로 같은 k6 시나리오. 기록할 것: 201 이 정확히 100 인지, 최종 `remaining_quantity`, 소진(exhausted) 건수, 시도 횟수 분포, `hikaricp.connections.pending` 최대, p99, 처리량
      - `performance/<날짜>-stage2-optimistic.md` 에 1단계와 같은 형식으로, ADR-0003 결과 표 2단계 행 갱신
      - ADR-0003 에 "Version 으로 해결되는 것(초과 예약)과 남는 것(재고가 있는데 답을 못 주는 소진 실패, 실패 시도의 DB 비용, 재고 행 밖의 불변식, 처리량 상한)" 을 측정 수치와 함께 기록
    - [ ] (선택) 7. 조건부 UPDATE 한 문장 — `StoresItemsRepository` 의 TODO(2.1 선택 단계). `@Version` 없이 재시도도 없는 중간 데이터 포인트. 시간이 허락하면 같은 형식으로 측정
- [~] 가게·상품 API — 뼈대 생성 (2026-09-12). `ieum-api` / `stores/` 아래 `exception`·`service`·`web`
  - 점주: `POST /api/owner/stores`, `GET /api/owner/stores/me`, `POST /api/owner/stores/{storeUid}/items` (`OwnerStoreController`, BUSINESS_OWNER)
  - 공개: `GET /api/stores/{storeUid}`, `GET /api/stores/{storeUid}/items`, `GET /api/items/{itemUid}` (permitAll 경로)
  - 공통 예외 부모 `common/exception/ApiException` + `common/web/ApiExceptionAdvice`. `OrderException`·`StoreException` 이 상속
  - `Stores.changeLogoImgUrl` 추가, `StoresRepository`·`StoresItemsRepository` 조회 메서드 추가
  - 남은 것(TODO 주석): 영업 종료, 영업 시간 수정, 재고 조정, 판매 종료, 상품별 예약 현황, 지역별 조회(위치 컬럼 설계 선행)
- [x] 부하 테스트 SQL 시드 — `IEUM_BE/scripts/sql/seed-loadtest.sql` (점주 1·가게 1·재고 100 상품 1·소비자 N, 기본 10,000 — 1인 1요청으로 "재고 100 / 요청 10,000" 을 맞춤. 소비자 1,000 이면 중복 활성 예약 검사가 요청 대부분을 걸러 재고 경합이 사라진다), `reset-loadtest.sql` (라운드 간 재고·주문 초기화). 실행은 호스트 mysql 이 아니라 `docker exec -i ieum-mysql mysql --default-character-set=utf8mb4` 파이프 (스크립트 상단 주석)
  - 고정 uid: 점주 `1111…`, 가게 `2222…`, 상품 `3333…`. 비밀번호는 전부 `password1`
  - 재실행 가능. 테이블은 서버를 한 번 기동해 Hibernate 가 만든 뒤여야 함
- [ ] Redis Lua Script 기반 원자적 재고 차감
- [ ] Idempotency-Key 처리 (24시간 보존)
- [x] 예약 생성 전제 조건 (2026-09-12) — 영업 종료·`lastOrderTime` 경과 시 `ItemNotOnSale`, 동일 사용자 + 동일 상품 활성 예약이 있으면 `DuplicateActiveOrder`. 재고 차감 전에 검사. `OrderServiceTest` 로 검증
  - DB 조회 기반이라 동시 요청 사이의 틈은 남아 있음. 3단계에서 중복 검사를 Lua 스크립트 안으로 옮겨 닫는다
- [x] `OrderState.EXPIRED` 추가, `UsersOrders.expire()` 전이 — `READY_FOR_PICKUP` 에서만 허용. 그 외 상태에서 예외로 둘지 무시할지는 Expiry Worker 구현 시 결정
- [x] `UsersOrders` 에 `ready_at` 컬럼 추가 — `readyForPickup()` 호출 시 기록. 만료 판정 기준
- [ ] TTL 기반 예약 만료 — `ready_at + 15분`. 시간은 설정값(`PICKUP_TTL`, 기본 `PT15M`)
- [ ] Sorted Set + Expiry Worker (Keyspace Notification 에 의존하지 않음) — `readyForPickup()` 시 `ZADD` (score = 만료 시각), 워커가 `ZRANGEBYSCORE` 로 지난 것을 꺼내 `expire()` + 재고 복구
- [ ] 재고 복구 멱등성 (예약당 1회)
- [ ] Reconciliation Job — 지연·누락 만료 탐지
- [ ] 픽업 코드 발급 및 검증

---

## 3. 공통

- [ ] 전역 예외 처리 (`@RestControllerAdvice` + `ProblemDetail`)
- [ ] 요청/응답 DTO 및 검증 (`spring-boot-starter-validation` 의존성 추가 필요)
- [ ] API 문서화 — OpenAPI vs Spring REST Docs
  - [x] 인증 API 명세는 우선 Notion 에 수기 작성 (이음 > API 명세서, 2026-09-10). 예약·상품 API 구현 시 이어서 추가
  - 코드 기반 문서 도구 도입 여부는 예약 도메인 착수 후 결정
- [ ] `JPA_DDL_AUTO` 를 `validate` 로 전환하고 스키마 마이그레이션 도구 도입 검토 (Flyway)
- [ ] Testcontainers 기반 통합 테스트

---

## 4. 관측 및 확장

- [ ] Actuator + Micrometer
- [ ] Prometheus / Grafana
- [ ] 불변식 검증 지표 — 초과 예약 0건, 재고 복구 1회
- [x] k6 부하 테스트 시나리오 (재고 100 / 요청 10,000) — `IEUM_BE/scripts/k6/create-order.js` (2026-09-12). 로그인은 `setup()` 에서 `http.batch` 로 병렬 처리, `shared-iterations` 로 소비자 1인 1요청
  - k6 는 호스트에 winget 으로 설치 (v2.2.0). 서버가 호스트에서 돌고 있어 컨테이너 k6 보다 변수가 적다. 컨테이너로 옮길 때는 `AUTH_URL`/`API_URL` 을 `host.docker.internal` 로
  - `setup()` 반환값은 VU 마다 복사되므로 VU 100 을 상한으로 둔다. 더 올리려면 토큰을 파일로 뽑아 `SharedArray` 로 읽는 방식으로
  - 라운드 기록 순서: k6 요약 → DB 확인 쿼리 → 실행 조건 → 그 다음에 `reset-loadtest.sql`
- [ ] 병목 측정 결과를 근거로 V2 착수 여부 판단
