# TODO

IEUM 백엔드 작업 목록. 완료된 항목은 체크하고, 배경 설명이 필요한 결정은 [ADR](./adr)에 남깁니다.

---

## 진행 상황

- [x] 도메인 엔티티 및 리포지토리 정의
- [x] 로컬 개발 환경 (Docker Compose - MySQL 8.4, Redis 7.4)
- [x] 환경변수 외부화 (`.env` + `application.yaml` 플레이스홀더)
- [ ] **인증/인가** ← 현재 단계
- [ ] 예약 도메인 로직
- [ ] 부하 테스트 및 관측
- [ ] V2 - Kafka 예약 대기열
- [ ] V3 - Kubernetes Scale-out

---

## 1. 인증 / 인가

설계 배경과 대안 검토는 [ADR-0001](./adr/0001-authentication.md) 참조.

현재 `spring-boot-starter-security` 의존성은 있으나 `SecurityConfig` 가 없어,
모든 엔드포인트가 Spring Security 기본 설정(자동 생성 비밀번호 + httpBasic)으로 막혀 있는 상태입니다.

### 1.1 기반

- [ ] `SecurityConfig` 작성
  - [ ] `anyRequest().authenticated()` — default-deny
  - [ ] `permitAll` 허용 목록 명시 (회원가입, 로그인, 상품 조회, actuator health)
  - [ ] `SessionCreationPolicy.STATELESS`
  - [ ] CSRF 비활성 (세션을 쓰지 않으므로)
  - [ ] CORS 설정 (프론트엔드 오리진)
- [ ] JWT 라이브러리 선정 — `spring-boot-starter-oauth2-resource-server` vs `jjwt`
  - 전자는 검증기가 내장이라 직접 구현할 코드가 적음
- [ ] 서명 키 관리 — 대칭키(HS256) vs 비대칭키(RS256)
  - 인증 서버 분리 가능성을 열어두려면 RS256
  - 키는 `.env` 로 주입, 저장소에 커밋 금지

### 1.2 패키지 구조

ADR-0001 에 따라 발급과 검증을 분리합니다.

- [ ] `auth/issue/` — 자격 증명 검증 후 토큰 발급 (나중에 분리 가능한 유일한 부분)
- [ ] `auth/verify/` — 서명 검증 및 `SecurityContext` 주입 (각 인스턴스 내장)

### 1.3 계정

- [ ] 회원가입 API
  - [ ] **`UsersAccount.password` 가 평문 컬럼입니다.** `BCryptPasswordEncoder` 적용 필요
  - [ ] 컬럼 길이 확인 — BCrypt 해시는 60자, 현재 `length = 100` 이므로 충분
  - [ ] `UserType` 별 가입 분기 (CONSUMER / BUSINESS_OWNER)
  - [ ] BUSINESS_OWNER 는 `BusinessRegistration` 검증 절차 필요 여부 결정
- [ ] 로그인 API — Access Token + Refresh Token 발급
- [ ] `UserType` → Spring Security 권한(`ROLE_*`) 매핑
- [ ] `UsersAccount.uid` 를 토큰 subject 로 사용할지, `id` 를 쓸지 결정
  - `uid` 는 36자 UUID 이므로 내부 PK 노출을 피할 수 있음

### 1.4 토큰 수명 주기

- [ ] Refresh Token 저장소 — Redis (이미 인프라에 있음)
- [ ] 토큰 재발급 API
- [ ] 로그아웃 — Refresh Token 폐기
- [ ] Access Token 블랙리스트 필요 여부 결정
  - stateless JWT 는 만료 전 강제 무효화가 불가능. 수명을 짧게(15분 내외) 가져가는 것으로 대체 가능한지 검토

### 1.5 인가

- [ ] `@EnableMethodSecurity` 활성화
- [ ] 역할 검사 — `@PreAuthorize` 로 BUSINESS_OWNER 전용 API 제한
  - 상품 등록/수정/삭제, 주문 승인, 픽업 완료 처리
- [ ] 소유권 검사를 서비스 계층 규약으로 정립
  - `UsersOrders` — 취소/조회는 본인만
  - `StoresItems` — 수정은 해당 `Stores` 의 점주만
  - `ItemsReviews` — 수정/삭제는 작성자만
  - `ReviewReports` — 처리는 ADMIN 만
- [ ] **주문 생성 시 사용자 식별자는 요청 본문이 아닌 토큰에서 추출**
  - 본문의 `userId` 를 신뢰하면 멱등 키 정책(`userId + Idempotency-Key`)이 무력화됨
- [ ] 인증된 사용자를 컨트롤러에 주입하는 방식 결정 (`@AuthenticationPrincipal` 등)

### 1.6 예외 처리

- [ ] 401 / 403 을 `ProblemDetail` 로 응답
  - `spring.mvc.problemdetails.enabled: true` 는 이미 켜져 있음
  - 단, `AuthenticationEntryPoint` / `AccessDeniedHandler` 는 필터 계층이라 별도 처리 필요
- [ ] 인증 실패 사유를 응답에 과도하게 노출하지 않기 (계정 존재 여부 등)

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
- [ ] `JPA_DDL_AUTO` 를 `validate` 로 전환하고 스키마 마이그레이션 도구 도입 검토 (Flyway)
- [ ] Testcontainers 기반 통합 테스트

---

## 4. 관측 및 확장

- [ ] Actuator + Micrometer
- [ ] Prometheus / Grafana
- [ ] 불변식 검증 지표 — 초과 예약 0건, 재고 복구 1회
- [ ] k6 부하 테스트 시나리오 (재고 100 / 요청 10,000)
- [ ] 병목 측정 결과를 근거로 V2 착수 여부 판단
