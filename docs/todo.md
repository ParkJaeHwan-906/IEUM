# TODO

IEUM 백엔드 작업 목록. 완료된 항목은 체크하고, 배경 설명이 필요한 결정은 [ADR](./adr)에 남깁니다.

---

## 진행 상황

- [x] 도메인 엔티티 및 리포지토리 정의
- [x] 로컬 개발 환경 (Docker Compose - MySQL 8.4, Redis 7.4)
- [x] 환경변수 외부화 (`.env` + `application.yaml` 플레이스홀더)
- [~] **인증/인가** ← 현재 단계 (인증 서버·API 서버 코드 완료, 테스트·통합 확인·ADR-0002 진행 중)
- [ ] 예약 도메인 로직
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

- [ ] ADR-0002 작성 — ADR-0001 의 "프로세스는 나누지 않는다"를 수정하는 기록

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

- [ ] `spring-security-test` 로 인가 규칙 검증
- [ ] 토큰 없이 보호된 엔드포인트 호출 시 401
- [ ] 권한 없는 역할로 호출 시 403
- [ ] 타인의 리소스 접근 시 403

---

## 2. 예약 도메인

### 2.1 선결 과제

- [ ] **`OrderState` 와 README 의 예약 상태가 불일치합니다.** 정리 필요
  - 코드: `PENDING`, `APPROVED`, `READY_FOR_PICKUP`, `PICKED_UP`, `CANCELED`
  - README: `RESERVED`, `COMPLETED`, `CANCELLED`, `EXPIRED`
  - 특히 README 의 핵심인 **`EXPIRED`(TTL 만료) 상태가 코드에 없습니다.** 만료 시 재고 복구 로직이 여기에 걸립니다
  - 점주 승인 절차(`APPROVED`)를 유지할지, README 대로 즉시 확정 모델로 갈지 결정
- [ ] 재고 필드 설계 — `StoresItems` 에 `initialStock` / `availableStock` 확인
- [ ] `UsersOrders.@Version` 낙관적 락과 Redis 기반 재고 차감의 역할 분담 정리
  - 재고는 Redis, 상태 전이는 JPA 로 나눌 것인지

### 2.2 구현

- [ ] Service / Controller 계층 (현재 엔티티와 리포지토리만 존재)
- [ ] Redis Lua Script 기반 원자적 재고 차감
- [ ] Idempotency-Key 처리 (24시간 보존)
- [ ] 동일 사용자 + 동일 상품 중복 활성 예약 방지
- [ ] TTL 기반 예약 만료
- [ ] Sorted Set + Expiry Worker (Keyspace Notification 에 의존하지 않음)
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
- [ ] k6 부하 테스트 시나리오 (재고 100 / 요청 10,000)
- [ ] 병목 측정 결과를 근거로 V2 착수 여부 판단
