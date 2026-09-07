# 인증 서버 분리 구현 가이드

ADR-0001 의 `auth/issue` / `auth/verify` 경계를 실제 프로세스 경계로 올리는 작업 순서입니다.
목표는 **클러스터에 인증 서버 하나**, **API Pod 에는 서명 검증만** 두는 것입니다.

> 이 문서의 코드는 Spring Boot 4.1.1 / Java 21 / Gradle 9.7 에서 컴파일과 테스트를 실제로 통과한 것을
> 옮긴 것입니다. 마지막 "함정" 절을 먼저 읽어 두면 시간을 아낍니다.

---

## 0. 먼저 결정할 것

### 0.1 검증은 각 Pod 에 남는다

"Pod 가 인증 기능을 갖지 않는다"는 **발급**(비밀번호 검증, 서명, Refresh Token 저장)에만 적용합니다.
**검증**(공개키로 JWT 서명 확인)은 각 API Pod 가 직접 합니다. 이유는 ADR-0001 에 이미 있습니다.

- 검증까지 인증 서버에 맡기면 → 요청마다 네트워크 홉 (대안 A, 기각)
- 인증 서버가 헤더만 붙여주면 → 클러스터 내부에서 위조 가능 (대안 B, 기각)

검증은 DB 도 외부 호출도 없는 순수 암호 연산이므로 "인증 서버"라 부를 것이 아닙니다.

### 0.2 프로세스를 나누는 방법 세 가지

| 방법 | 장점 | 단점 |
|---|---|---|
| **A. Gradle 멀티모듈** | API 이미지에 인증 코드가 물리적으로 없음. 엔티티는 한 모듈에서 공유 | `src/` 재배치, 로컬에 프로세스 2개 |
| B. 단일 JAR + 프로파일 | 구조 변경 없음 | API 이미지 안에 인증 코드·개인키 로딩 코드가 그대로 있음. 플래그 하나로 역할이 바뀜 |
| C. 별도 저장소 | 가장 확실한 분리 | `users`, `users_account` 엔티티를 두 곳에서 관리 |

이 가이드는 **A** 기준입니다. B 로 가더라도 3절 이후의 코드는 패키지만 다르고 그대로 씁니다.

### 0.3 라이브러리

- 검증: `spring-boot-starter-oauth2-resource-server` — `NimbusJwtDecoder` 내장, JWKS 캐싱·재조회 내장
- 발급: `spring-security-oauth2-jose` — `NimbusJwtEncoder`. Spring Authorization Server 는 쓰지 않습니다
  (Password Grant 가 표준에서 제거되어 이메일+비밀번호 로그인에 Authorization Code + PKCE 와 로그인 페이지가 필요해집니다)
- 키: RS256. 개인키는 인증 서버만, 공개키는 `/.well-known/jwks.json` 으로 공개

---

## 1. 모듈 재배치

### 1.1 디렉터리

~~~text
IEUM_BE/
├── settings.gradle          include 'ieum-domain', 'ieum-auth', 'ieum-api'
├── build.gradle             subprojects 공통 설정
├── ieum-domain/             entities + repositories (java-library)
│   └── src/main/java/com/hwannee/ieum/{common,users,stores,orders,reviews,registration}
├── ieum-auth/               인증 서버 (boot)
│   └── src/main/java/com/hwannee/ieum/
│       ├── IeumAuthApplication.java
│       └── auth/issue/...
└── ieum-api/                API 서버 (boot)
    └── src/main/java/com/hwannee/ieum/
        ├── IeumApplication.java
        └── auth/verify/...
~~~

이동은 `git mv` 로 하면 히스토리가 이어집니다.

~~~bash
cd IEUM_BE
B=src/main/java/com/hwannee/ieum
mkdir -p ieum-domain/src/main/java/com/hwannee/ieum ieum-api/src/main/java/com/hwannee/ieum ieum-api/src/main/resources ieum-api/src/test/java/com/hwannee/ieum
for p in common orders registration reviews stores users; do git mv $B/$p ieum-domain/src/main/java/com/hwannee/ieum/$p; done
git mv $B/IeumApplication.java ieum-api/src/main/java/com/hwannee/ieum/
git mv src/main/resources/application.yaml ieum-api/src/main/resources/
git mv src/test/java/com/hwannee/ieum/IeumApplicationTests.java ieum-api/src/test/java/com/hwannee/ieum/
rm -rf src
~~~

두 앱의 메인 클래스를 모두 `com.hwannee.ieum` 패키지에 두면 `@SpringBootApplication` 의 기본 스캔 범위가
`ieum-domain` JAR 안의 엔티티·리포지토리까지 자동으로 덮습니다. `@EntityScan`, `@EnableJpaRepositories` 가 필요 없습니다.

### 1.2 Gradle

`settings.gradle`

~~~groovy
rootProject.name = 'IEUM'
include 'ieum-domain'
include 'ieum-auth'
include 'ieum-api'
~~~

루트 `build.gradle` — 플러그인은 `apply false`, 공통 설정은 `subprojects` 로

~~~groovy
plugins {
    id 'org.springframework.boot' version '4.1.1' apply false
    id 'io.spring.dependency-management' version '1.1.7' apply false
}

subprojects {
    apply plugin: 'java'
    apply plugin: 'io.spring.dependency-management'
    group = 'com.hwannee'
    version = '0.0.1-SNAPSHOT'
    java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
    repositories { mavenCentral() }
    dependencyManagement {
        imports { mavenBom org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES }
    }
    dependencies {
        compileOnly 'org.projectlombok:lombok'
        annotationProcessor 'org.projectlombok:lombok'
        testCompileOnly 'org.projectlombok:lombok'
        testAnnotationProcessor 'org.projectlombok:lombok'
        testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
    }
    tasks.named('test') { useJUnitPlatform() }
}
~~~

`ieum-domain/build.gradle`

~~~groovy
plugins { id 'java-library' }
dependencies {
    api 'org.springframework.boot:spring-boot-starter-data-jpa'
    runtimeOnly 'com.mysql:mysql-connector-j'
}
~~~

`ieum-api/build.gradle`

~~~groovy
plugins { id 'org.springframework.boot' }
dependencies {
    implementation project(':ieum-domain')
    implementation 'org.springframework.boot:spring-boot-starter-webmvc'
    implementation 'org.springframework.boot:spring-boot-starter-security'
    implementation 'org.springframework.boot:spring-boot-starter-oauth2-resource-server'
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'
    developmentOnly 'org.springframework.boot:spring-boot-devtools'
    testImplementation 'org.springframework.boot:spring-boot-starter-security-test'
    testImplementation 'org.springframework.boot:spring-boot-starter-webmvc-test'
    testImplementation 'org.springframework.boot:spring-boot-starter-data-jpa-test'
    testImplementation 'org.springframework.boot:spring-boot-starter-data-redis-test'
}
// .env 는 저장소 루트에 있으므로 거기서 실행 (bootRun 기본 workingDir 은 모듈 디렉터리)
tasks.named('bootRun') { workingDir = rootProject.projectDir }
~~~

`ieum-auth/build.gradle`

~~~groovy
plugins { id 'org.springframework.boot' }
dependencies {
    implementation project(':ieum-domain')
    implementation 'org.springframework.boot:spring-boot-starter-webmvc'
    implementation 'org.springframework.boot:spring-boot-starter-security'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'
    implementation 'org.springframework.security:spring-security-oauth2-jose'
    developmentOnly 'org.springframework.boot:spring-boot-devtools'
    testImplementation 'org.springframework.boot:spring-boot-starter-security-test'
    testImplementation 'org.springframework.boot:spring-boot-starter-webmvc-test'
}
tasks.named('bootRun') { workingDir = rootProject.projectDir }
~~~

여기까지 하고 `./gradlew compileJava` 가 통과하는지 확인한 뒤 다음으로 갑니다.

---

## 2. 도메인 모듈 손보기

`UsersAccountRepository` 가 `JpaRepository<Users, Long>` 로 잘못 선언되어 있습니다. 고치면서 인증 서버가 쓸 조회를 추가합니다.

~~~java
public interface UsersAccountRepository extends JpaRepository<UsersAccount, Long> {
    @Query("select a from UsersAccount a join a.user u where u.email = :email")
    Optional<UsersAccount> findByUserEmail(@Param("email") String email);
    Optional<UsersAccount> findByUid(String uid);
    boolean existsByNickname(String nickname);
}

public interface UsersRepository extends JpaRepository<Users, Long> {
    boolean existsByEmail(String email);
    boolean existsByTel(String tel);
}
~~~

`@Param` 은 꼭 붙입니다. `ieum-domain` 에는 Boot 플러그인이 없어서 `-parameters` 컴파일 옵션이 자동으로 들어가지 않습니다.

---

## 3. 인증 서버 (`ieum-auth`, 패키지 `auth.issue`)

### 3.1 설정 프로퍼티

~~~java
@ConfigurationProperties(prefix = "ieum.auth")
public record AuthProperties(
        String issuer,                                   // 토큰 iss. API 서버 issuer-uri 와 동일해야 함
        String privateKey,                               // Base64 PKCS#8 DER. 비우면 임시 키
        @DefaultValue("PT15M") Duration accessTokenTtl,
        @DefaultValue("P14D") Duration refreshTokenTtl) {}
~~~

등록은 `@ConfigurationPropertiesScan` 이 아니라 **`@EnableConfigurationProperties(AuthProperties.class)`** 를
키 설정 클래스에 붙이는 방식으로 합니다. 이유는 함정 절 참조.

### 3.2 서명 키

~~~java
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class JwtKeyConfig {

    @Bean
    public RSAKey signingKey(AuthProperties props) throws Exception {
        KeyPair pair = StringUtils.hasText(props.privateKey())
                ? loadFromPkcs8(props.privateKey()) : generateEphemeral();   // 임시 키는 WARN 로그
        return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate())
                .keyIDFromThumbprint()               // kid 를 공개키 지문에서 파생 → 별도 설정 불필요
                .algorithm(JWSAlgorithm.RS256)
                .keyUse(KeyUse.SIGNATURE)
                .build();
    }

    @Bean
    public JwtEncoder jwtEncoder(RSAKey key) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(key)));
    }

    static KeyPair loadFromPkcs8(String base64) throws Exception {
        byte[] der = Base64.getMimeDecoder().decode(base64);        // 줄바꿈 섞여도 허용
        KeyFactory kf = KeyFactory.getInstance("RSA");
        RSAPrivateCrtKey priv = (RSAPrivateCrtKey) kf.generatePrivate(new PKCS8EncodedKeySpec(der));
        RSAPublicKey pub = (RSAPublicKey) kf.generatePublic(
                new RSAPublicKeySpec(priv.getModulus(), priv.getPublicExponent()));   // 공개키는 개인키에서 복원
        return new KeyPair(pub, priv);
    }

    private static KeyPair generateEphemeral() throws Exception {
        log.warn("ieum.auth.private-key 가 비어 있어 임시 서명 키를 생성합니다. "
                + "재시작하면 발급된 토큰이 전부 무효화되며, 인증 서버를 둘 이상 띄우면 검증이 실패합니다. "
                + "scripts/gen-jwt-key.sh 로 키를 만들어 JWT_PRIVATE_KEY 에 넣으세요.");
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}
~~~

import 주의: `KeyUse` 는 `com.nimbusds.jose.jwk.KeyUse` 입니다 (`com.nimbusds.jose.KeyUse` 아님).

키 생성 스크립트 (`scripts/gen-jwt-key.sh`):

~~~bash
# genpkey 의 DER 출력은 PKCS#1 이므로 pkcs8 로 한 번 더 감싼다
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
  | openssl pkcs8 -topk8 -nocrypt -outform DER \
  | openssl base64 -A
~~~

결과가 `MIIEv...` 로 시작하면 PKCS#8 입니다. `MIIEp...` / `MIIEo...` 로 시작하면 PKCS#1 이라 Java 가 못 읽습니다.

### 3.3 JWKS 엔드포인트

~~~java
@RestController
public class JwkSetController {
    private final Map<String, Object> publicJwks;
    public JwkSetController(RSAKey key) { this.publicJwks = new JWKSet(key).toJSONObject(true); } // true = 공개키만
    @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic()).body(publicJwks);
    }
}
~~~

테스트에서 `$.keys[0].d` 가 없는지 꼭 확인하세요. `d` 가 있으면 개인키가 새는 겁니다.

### 3.4 Access Token 발급

~~~java
@Component
public class AccessTokenIssuer {
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_NICKNAME = "nickname";
    // 생성자에서 JwtEncoder, RSAKey.getKeyID(), AuthProperties 주입

    public String issue(String uid, UserType role, String nickname) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer()).subject(uid).id(UUID.randomUUID().toString())
                .issuedAt(now).expiresAt(now.plus(props.accessTokenTtl()))
                .claim(CLAIM_ROLE, role.name()).claim(CLAIM_NICKNAME, nickname)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
~~~

`sub` 는 `UsersAccount.uid` 입니다. 내부 PK 를 노출하지 않습니다.

### 3.5 Refresh Token (Redis)

~~~java
@Component
public class RefreshTokenStore {
    private static final String KEY_PREFIX = "auth:refresh:";
    // StringRedisTemplate, AuthProperties, SecureRandom

    public String issue(String uid) {
        byte[] b = new byte[32]; random.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        redis.opsForValue().set(key(token), uid, props.refreshTokenTtl());
        return token;
    }
    public Optional<String> consume(String token) {              // 한 번만 소비 = 회전
        return Optional.ofNullable(redis.opsForValue().getAndDelete(key(token)));
    }
    public void revoke(String token) { redis.delete(key(token)); }
    private static String key(String token) { return KEY_PREFIX + sha256Hex(token); }   // 해시만 저장
}
~~~

`getAndDelete` 는 Redis 6.2+ 의 GETDEL 입니다. Compose 의 7.4 에서 동작합니다.

### 3.6 서비스

- `SignupService.signup(...)`: `UserType.ADMIN` 은 거부 → 이메일/전화/닉네임 중복 검사 → `Users` 저장 → `UsersAccount` 저장 (`passwordEncoder.encode`). `@Transactional`.
  unique 제약 경합은 `DataIntegrityViolationException` 으로 잡아 409 로 응답.
- `AuthService.login(email, raw)`: 계정이 **없어도** BCrypt 비교를 한 번 수행해 응답 시간 차이를 없앱니다.

  ~~~java
  UsersAccount account = repo.findByUserEmail(email).orElse(null);
  String hash = account != null ? account.getPassword() : dummyHash;   // dummyHash = encoder.encode("x") 를 생성자에서
  boolean ok = encoder.matches(raw, hash);
  if (account == null || !ok) throw new AuthException.InvalidCredentials();
  ~~~

- `refresh(token)`: `store.consume` → uid 로 계정 재조회(역할 변경 반영) → 새 쌍 발급
- `logout(token)`: `store.revoke`

### 3.7 컨트롤러와 예외

`POST /auth/signup`(201), `/auth/login`, `/auth/refresh`, `/auth/logout`(204). DTO 는 record + Bean Validation.
비밀번호는 `@Size(min = 8, max = 72)` — BCrypt 는 72바이트까지만 봅니다.

예외는 상태 코드를 가진 base 클래스 하나로 모읍니다.

~~~java
public abstract class AuthException extends RuntimeException {
    private final HttpStatus status; /* ... */
    public static final class InvalidCredentials extends AuthException { /* 401, "이메일 또는 비밀번호가 올바르지 않습니다" */ }
    public static final class InvalidRefreshToken extends AuthException { /* 401 */ }
    public static final class DuplicateAccount extends AuthException { /* 409 */ }
    public static final class UnsupportedUserType extends AuthException { /* 400 */ }
}
~~~

`@RestControllerAdvice` 에서 `ErrorResponse.builder(ex, status, detail).build()` 를 반환하면 `ProblemDetail` 로 나갑니다.
Bean Validation 400 은 `spring.mvc.problemdetails.enabled: true` 가 이미 처리하므로 손대지 않습니다.

### 3.8 SecurityConfig (인증 서버)

~~~java
http.csrf(AbstractHttpConfigurer::disable)
    .cors(Customizer.withDefaults())
    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
    .authorizeHttpRequests(a -> a
        .requestMatchers(HttpMethod.POST, "/auth/signup", "/auth/login", "/auth/refresh", "/auth/logout").permitAll()
        .requestMatchers(HttpMethod.GET, "/.well-known/jwks.json").permitAll()
        .requestMatchers(HttpMethod.GET, "/actuator/health/**").permitAll()
        .anyRequest().denyAll())                      // 인증 서버는 자기 토큰을 검증하지 않으므로 denyAll
    .exceptionHandling(e -> e.authenticationEntryPoint(handlers).accessDeniedHandler(handlers));
~~~

`PasswordEncoder` 빈은 `BCryptPasswordEncoder`. `ProblemDetailAuthHandlers` 는 4.3 의 것을 `auth.issue.web` 패키지에 같은 내용으로 둡니다.

CORS 는 `CorsConfigurationSource` 빈으로 `ieum.cors.allowed-origins` 를 읽습니다. 같은 `config` 패키지에 둡니다.

~~~java
@Configuration
public class CorsConfig {
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${ieum.cors.allowed-origins}") List<String> allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
~~~

API 서버 쪽 CORS 는 메서드에 `PUT`, `PATCH`, `DELETE` 를, 헤더에 `Idempotency-Key` 를 추가합니다.

### 3.9 application.yaml (인증 서버)

~~~yaml
server.port: ${AUTH_SERVER_PORT:8081}
ieum:
  auth:
    issuer: ${JWT_ISSUER:http://localhost:8081}
    private-key: ${JWT_PRIVATE_KEY:}
    access-token-ttl: ${ACCESS_TOKEN_TTL:PT15M}
    refresh-token-ttl: ${REFRESH_TOKEN_TTL:P14D}
  cors.allowed-origins: ${CORS_ALLOWED_ORIGINS:http://localhost:3000}
~~~

datasource / redis 블록은 API 서버 것을 복사하되 Hikari 풀은 작게(`AUTH_DB_POOL_SIZE:5`).

---

## 4. API 서버 (`ieum-api`, 패키지 `auth.verify`)

### 4.1 application.yaml

~~~yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: ${AUTH_JWKS_URI:http://localhost:8081/.well-known/jwks.json}
          issuer-uri: ${JWT_ISSUER:http://localhost:8081}
ieum:
  cors.allowed-origins: ${CORS_ALLOWED_ORIGINS:http://localhost:3000}
~~~

- `jwk-set-uri` 가 있으면 Boot 는 OIDC discovery 를 하지 않고, `issuer-uri` 는 `iss` 검증에만 씁니다.
- JWKS 는 **첫 토큰 검증 시점**에 가져오므로 API 서버는 인증 서버 없이도 기동합니다.
- 모르는 `kid` 가 오면 `NimbusJwtDecoder` 가 JWKS 를 다시 받습니다. 키 교체에 재배포가 필요 없습니다.

### 4.2 SecurityConfig (API 서버)

~~~java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_NICKNAME = "nickname";

    @Bean
    SecurityFilterChain chain(HttpSecurity http, ProblemDetailAuthHandlers handlers,
                              JwtAuthenticationConverter converter) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.GET, "/api/stores/**", "/api/items/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/actuator/health/**").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o
                .jwt(j -> j.jwtAuthenticationConverter(converter))
                .authenticationEntryPoint(handlers)
                .accessDeniedHandler(handlers))
            .exceptionHandling(e -> e.authenticationEntryPoint(handlers).accessDeniedHandler(handlers));
        return http.build();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(CLAIM_ROLE);   // 단일 문자열 클레임도 처리됨
        authorities.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
~~~

`authenticationEntryPoint` 는 `oauth2ResourceServer` 와 `exceptionHandling` **양쪽에** 지정해야 합니다.
전자는 토큰이 있는데 잘못된 경우, 후자는 토큰이 아예 없는 경우를 담당합니다.

### 4.3 401/403 을 ProblemDetail 로

필터 계층은 MVC 바깥이라 `@ExceptionHandler` 가 안 잡힙니다. `HandlerExceptionResolver` 로 위임하면 됩니다.

~~~java
@Component
public class ProblemDetailAuthHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final HandlerExceptionResolver resolver;
    public ProblemDetailAuthHandlers(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver r) { this.resolver = r; }
    public void commence(HttpServletRequest req, HttpServletResponse res, AuthenticationException ex) { resolver.resolveException(req, res, null, ex); }
    public void handle(HttpServletRequest req, HttpServletResponse res, AccessDeniedException ex) { resolver.resolveException(req, res, null, ex); }
}

@RestControllerAdvice
public class SecurityExceptionAdvice {
    @ExceptionHandler(AuthenticationException.class)
    ErrorResponse unauthenticated(AuthenticationException ex) {
        return ErrorResponse.builder(ex, HttpStatus.UNAUTHORIZED, "유효한 토큰이 필요합니다")
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer").build();
    }
    @ExceptionHandler(AccessDeniedException.class)
    ErrorResponse forbidden(AccessDeniedException ex) {
        return ErrorResponse.builder(ex, HttpStatus.FORBIDDEN, "접근 권한이 없습니다").build();
    }
}
~~~

이 방식은 Jackson `ObjectMapper` 를 직접 쓰지 않습니다. Boot 4 는 Jackson 3(`tools.jackson.*`)이 기본이라 패키지가 바뀌었는데, 이 방식은 그 영향을 받지 않습니다.
`@PreAuthorize` 실패도 `AccessDeniedException` 이라 같은 어드바이스가 처리합니다.

### 4.4 인증 사용자 주입

~~~java
public record AuthenticatedUser(String uid, UserType role, String nickname) {
    public static AuthenticatedUser from(Jwt jwt) {
        return new AuthenticatedUser(jwt.getSubject(),
                UserType.valueOf(jwt.getClaimAsString(SecurityConfig.CLAIM_ROLE)),
                jwt.getClaimAsString(SecurityConfig.CLAIM_NICKNAME));
    }
}

@Target(PARAMETER) @Retention(RUNTIME) public @interface CurrentUser {}

@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {
    public boolean supportsParameter(MethodParameter p) {
        return p.hasParameterAnnotation(CurrentUser.class) && AuthenticatedUser.class.isAssignableFrom(p.getParameterType());
    }
    public Object resolveArgument(...) {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken t)
            return AuthenticatedUser.from(t.getToken());
        throw new AuthenticationCredentialsNotFoundException("인증된 사용자가 없습니다");  // permitAll 경로에서 쓴 경우
    }
}
~~~

`WebMvcConfigurer.addArgumentResolvers` 에 등록합니다. 주문 생성에서 사용자 식별은 `user.uid()` 만 씁니다.

---

## 5. 테스트

### 5.1 API 서버 — `@WebMvcTest` + `JwtDecoder` 모킹

서명 검증만 가짜로 바꾸고, 권한 매핑·default-deny·응답 형식은 실제 필터 체인을 태웁니다.

~~~java
@WebMvcTest(controllers = ProbeController.class)   // 테스트 소스에 둔 표본 컨트롤러
@Import({SecurityConfig.class, ProblemDetailAuthHandlers.class, SecurityExceptionAdvice.class,
        CurrentUserArgumentResolver.class, WebMvcSecurityConfig.class})
@TestPropertySource(properties = "ieum.cors.allowed-origins=http://localhost:3000")
class SecurityConfigTest {
    @MockitoBean JwtDecoder jwtDecoder;

    // given(jwtDecoder.decode("consumer-token")).willReturn(Jwt.withTokenValue("t").header("alg","RS256")
    //     .subject("uid").claim("role","CONSUMER").claim("nickname","n").issuedAt(now).expiresAt(now+60s).build());
    // given(jwtDecoder.decode("bad")).willThrow(new BadJwtException("서명 불일치"));   ← JwtException 아님!
}
~~~

확인할 것 7가지: permitAll 경로 200 / 토큰 없음 401 + `WWW-Authenticate: Bearer` + `application/problem+json` /
없는 경로도 401 (default-deny) / 잘못된 토큰 401 / 유효 토큰 → `@CurrentUser` 값 / 역할 불일치 403 / 역할 일치 200.

### 5.2 인증 서버

- `AccessTokenIssuerTest` (순수 단위): 임시 키로 발급 → `NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey())` + `JwtValidators.createDefaultWithIssuer` 로 검증. 다른 키로는 실패. Base64 PKCS#8 로드 시 공개키 일치.
- `AuthControllerTest` (`@WebMvcTest`, 서비스는 `@MockitoBean`): JWKS 에 `d` 없음 / 로그인 성공 응답 형태 / 실패 401 메시지 / 검증 400 / 나머지 경로 401.

`@Import` 에 `JwtKeyConfig` 를 넣고 `ieum.auth.private-key=` 를 비워 두면 임시 키로 돌아갑니다.

---

## 6. 환경변수 (`.env.example` 에 추가)

~~~properties
#AUTH_SERVER_PORT=8081
#JWT_ISSUER=http://localhost:8081
#JWT_PRIVATE_KEY=            # scripts/gen-jwt-key.sh 출력. 비우면 임시 키
#ACCESS_TOKEN_TTL=PT15M
#REFRESH_TOKEN_TTL=P14D
#AUTH_DB_POOL_SIZE=5
#AUTH_JWKS_URI=http://localhost:8081/.well-known/jwks.json   # K8s 에서는 인증 서버 Service 주소
#CORS_ALLOWED_ORIGINS=http://localhost:3000
~~~

---

## 7. 함정 — 실제로 걸렸던 것들

| 증상 | 원인 | 해결 |
|---|---|---|
| `package org.springframework.boot.test.autoconfigure.web.servlet does not exist` | Boot 4 에서 슬라이스 테스트 패키지 이동 | `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` |
| `@WebMvcTest` 에서 `No qualifying bean of type AuthProperties` | `@ConfigurationPropertiesScan` 은 슬라이스 테스트에 적용되지 않음 | `@EnableConfigurationProperties(AuthProperties.class)` 를 설정 클래스에 |
| 잘못된 토큰 테스트가 401 이 아니라 `AuthenticationServiceException` | `JwtException` 은 인증 실패로 번역되지 않음 | 테스트에서 `BadJwtException` 을 던질 것 (실제 `NimbusJwtDecoder` 도 이걸 던짐) |
| `JWT_PRIVATE_KEY` 로드 시 `InvalidKeySpecException` | `openssl genpkey -outform DER` 출력이 PKCS#1 | `openssl pkcs8 -topk8 -nocrypt -outform DER` 로 변환 |
| `bootRun` 에서 `.env` 를 못 읽음 | 멀티모듈에서 `bootRun` 의 작업 디렉터리가 모듈 디렉터리 | `tasks.named('bootRun') { workingDir = rootProject.projectDir }` |
| `@Query` 의 `:email` 바인딩 실패 | `ieum-domain` 에 `-parameters` 옵션이 없음 | `@Param("email")` 명시 |
| `KeyUse` import 오류 | 패키지가 `com.nimbusds.jose.jwk.KeyUse` | import 경로 수정 |
| 막힌 경로가 401 이 아니라 **200 + 빈 본문** | `ProblemDetailAuthHandlers` 가 예외를 `HandlerExceptionResolver` 로 넘겼는데 받아 줄 `@ExceptionHandler` 가 없음 | `AuthenticationException` / `AccessDeniedException` 핸들러가 있는 `@RestControllerAdvice` 를 반드시 함께 둘 것 (3.7 `AuthExceptionAdvice`, 4.3 `SecurityExceptionAdvice`) |
| `contextLoads` 실패 (`Communications link failure`) | MySQL 이 안 떠 있음 | `docker compose up -d` + `.env` 준비. 코드 문제 아님 |
| Gradle 이 `java` 를 못 찾음 | 이 PC 는 PATH 에 java 가 없음 | `JAVA_HOME=%USERPROFILE%\.jdks\azul-21.0.11` 지정 |

---

## 8. 작업 순서 요약

1. 1절 모듈 재배치 → `compileJava` 통과 확인
2. 2절 리포지토리 수정
3. 3절 인증 서버: 키 → JWKS → 발급기 → Refresh 저장소 → 서비스 → 컨트롤러 → SecurityConfig 순
4. 4절 API 서버: yaml → SecurityConfig → 예외 → `@CurrentUser`
5. 5절 테스트 → `./gradlew test`
6. `docker compose up -d` 후 두 서버 `bootRun`, `signup → login → API 호출` 로 끝까지 확인
7. ADR-0002 작성 (ADR-0001 의 "프로세스는 나누지 않는다"를 수정하는 기록), `todo.md` 갱신
