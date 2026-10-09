# ADR-0005. 스키마 관리 — Flyway 마이그레이션 + Hibernate validate

- 상태: **결정** (2026-10-09)
- 날짜: 2026-10-09
- 관계: [ADR-0002](./0002-auth-server-split.md) 의 멀티모듈 구조(두 서버가 `ieum-domain` 엔티티를 공유) 위에서 스키마를 누가 만드는지 정한다

## 배경

지금까지 스키마는 `spring.jpa.hibernate.ddl-auto=update` 가 만들었다. 2026-10-09 하루에만 `users_orders` 에 컬럼 2개(`idempotency_key`, `pickup_code`),
unique 1개, 인덱스 1개가 추가되었고 모두 서버 기동 시 Hibernate 가 조용히 반영했다. 이 방식의 문제는 다음과 같다.

- **무엇이 언제 바뀌었는지 기록이 없다.** 로컬 DB 를 Hibernate 가 새로 만든 스키마와 비교해 보니 `pickup_code` 와 인덱스가 빠져 있었다. 어느 서버를 마지막으로 띄웠는지에 따라 DB 모양이 달라진다
- **`update` 는 추가만 한다.** 컬럼 이름 변경·삭제·타입 축소, MySQL `ENUM` 에 값 추가(`order_state` 는 `enum(...)` 컬럼) 는 반영하지 않거나 의도와 다르게 반영한다
- **두 서버가 같은 DB 를 `update` 한다.** 인증 서버와 API 서버가 동시에 뜨면 같은 ALTER 를 경쟁적으로 시도할 수 있다
- 운영에서 `update` 를 켤 수 없으니, 운영 스키마를 만들 방법이 따로 필요해진다

## 결정

1. **Flyway 를 도입하고 `ddl-auto` 기본값을 `validate` 로 바꾼다.** Hibernate 는 스키마를 만들지 않고 엔티티와 맞는지만 기동 시 검사한다. 어긋나면 서버가 뜨지 않는다
2. **마이그레이션은 `ieum-domain/src/main/resources/db/migration` 에 둔다.** 엔티티가 있는 모듈이 스키마도 가진다. 두 서버가 같은 클래스패스 스크립트를 읽고, 동시에 떠도 Flyway 의 DB 잠금으로 한쪽만 적용한다 (실측: API 서버가 V2 를 적용, 뒤에 뜬 인증 서버는 "up to date")
3. **V1 은 기준선, V2 부터 변경분.** V1 은 Flyway 도입 직전 로컬 DB 와 같은 스키마(Hibernate 가 빈 DB 에 만든 DDL 을 `mysqldump --no-data` 로 떠서 V2 분을 뺀 것), V2 는 `pickup_code` 와 `(order_state, created_at)` 인덱스
4. **기존 DB 는 `baseline-on-migrate: true`, `baseline-version: 1`.** 테이블은 있는데 이력 테이블이 없는 DB 는 V1 을 적용한 것으로 표시하고 V2 부터 적용한다. 빈 DB 는 V1 → V2 를 그대로 실행한다
5. **통합 테스트도 `validate`.** Testcontainers 의 빈 MySQL 에 Flyway 가 V1·V2 를 적용한 뒤 Hibernate 가 검증한다. 엔티티만 바꾸고 마이그레이션을 빠뜨리면 통합 테스트가 기동 단계에서 실패한다

Hibernate 가 생성한 FK·UK 이름(`FK7bopbnohmu08ou6g1o4ayqi7a` 등) 은 V1 에 그대로 두었다. 기존 DB 와 이름이 같아야 이후 마이그레이션이 두 DB 모두에서 같은 이름으로 제약을 다룰 수 있다.

## 검토한 대안

- **Liquibase** — XML/YAML 변경 세트와 롤백 정의가 강점이지만, 이 프로젝트의 변경은 대부분 MySQL 한 종류에 대한 짧은 ALTER 라 SQL 파일 그대로가 읽기 쉽다
- **`update` 유지 + 운영만 수동 DDL** — 개발과 운영의 스키마 생성 경로가 갈라진다. 오늘처럼 로컬 DB 가 조용히 어긋나는 일이 반복된다
- **엔티티 생성 DDL(`create`) 을 매번 덤프** — 변경 이력이 아니라 최종 모양만 남아 기존 데이터가 있는 DB 에 적용할 수 없다

## 규칙

- 엔티티를 바꾸면 같은 커밋 묶음에 `V{n}__설명.sql` 을 추가한다. 이미 적용된 파일은 고치지 않는다 (Flyway 체크섬 검증이 기동을 막는다)
- 로컬에서 `JPA_DDL_AUTO=update` 로 우회하지 않는다. 필요하면 `validate` 실패 메시지가 무엇이 빠졌는지 알려 준다
- `ENUM` 컬럼(`order_state`, `user_type`, `store_type`) 에 값을 추가할 때는 `ALTER TABLE … MODIFY COLUMN … enum(...)` 마이그레이션이 필요하다

## 검증 (2026-10-09)

- 빈 DB(Testcontainers): `Successfully applied 2 migrations … now at version v2`, 이후 Hibernate validate 통과, 통합 테스트 25건 통과
- 기존 로컬 DB: `Successfully baselined schema with version: 1` → `Migrating schema to version "2 - add pickup code and pending timeout index"` → API 서버 기동. 인증 서버는 `Schema is up to date` 로 기동
