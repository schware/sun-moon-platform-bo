*🇬🇧 English version: [DESIGN.md](DESIGN.md)*

# sun-moon-platform-bo — 설계서

2026-09-09 기준, 이 저장소의 as-built 설계다. 실제로 존재하고 동작하는
것을 설명하며, 없는 것은 없다고 분명히 적는다 — 마지막의 "알려진 공백"
절은 나중에 덧붙인 게 아니라 설계의 일부다.

각 결정의 *이유*는 여기 [`docs/adr/`](adr/)와, 분리 이전의 ADR이 남아 있는
[`sun-moon-java-platform`](https://github.com/schware/sun-moon-java-platform/tree/master/docs/adr)에
있다.

---

## 1. 이것이 무엇인가

`sun-moon` platform의 Back Office다. 운영자 계정, 공통코드, Device 기준
정보를 관리하는 관리자용 면(面). JVM 프로세스 하나, HTTP listener 하나,
`/bo/*` REST endpoint, 그리고 **Spring을 전혀 쓰지 않는다**(ADR-0002).

원래는 `sun-moon-java-platform`의 단일 runtime 안에 있던 세 묶음의
route였다. 지금 별도 service인 이유는 두 가지다 — port 체계가 service당
container 하나를 전제하고, BO는 내부 전용인데 나온 쪽 runtime은 장비를
마주하기 때문이다. 노출 경계가 다른 둘이 같은 JVM을 쓸 이유는 없다
(ADR-0014).

## 2. 실제로 설계를 이끄는 원칙

1. **관리는 workload가 아니다.** BO는 platform이 돌아가는 데 쓰는 데이터를
   관리할 뿐, 장비를 상대하지도 connection을 붙들지도 batch를 돌리지도
   않는다. 트래픽은 운영자 몇 명이므로 확장 이야기는 "instance 하나면
   충분하다"로 끝나고, 설계는 대신 추적 가능성 쪽으로 최적화한다.
2. **Framework의 마법 없음.** DI container 없음, annotation scanning
   없음. 모든 결선은 composition root(`BoBootstrap.java`) 한 곳에서
   손으로 한다.
3. **Ports and adapters, 기본값은 fake.** 모든 외부 의존은 domain이
   소유한 interface 뒤에 있고 구현이 둘이다 — 기본으로 결선되고 테스트가
   덮는 in-memory fake, 그리고 컴파일은 되지만 여기서 **라이브 검증되지
   않은** 실제 adapter(ADR-0003, ADR-0005). JDK만 있는 머신에서
   `./gradlew run`이 그대로 동작한다.
4. **권한 검사는 구조로 건다.** 보호되는 route는 등록되는 그 자리에서
   감싸인다. 그래서 보호되지 않은 route는 아무도 안 읽는 파일에서 빠져
   있는 게 아니라, 한 표(表) 안에서 눈에 띈다.
5. **검증된 것만 검증됐다고 쓴다.** "실행 중인 서버 대상으로 테스트됨"과
   "컴파일됨"을 구분한다. §10 참고.

## 3. Runtime 구성

프로세스 하나, listener 하나. Netty group — boss 1 thread, worker group은
Netty 기본값인 코어 수 × 2 — 은 kernel이 준다.

```
                        ┌──────────────── JVM 하나 ────────────────┐
                        │                                          │
  운영자      ──8080────┼─▶ HTTP listener "BO"                     │
  (LAN 전용)            │     /health  /metrics  /bo/*             │
                        │            │                             │
                        │            ▼                             │
                        │     bo-worker-N pool ──▶ repository      │
                        └──────────────────────────────────────────┘
```

| Port | 제공 | 환경변수 |
|---|---|---|
| 8080 | `/health`, `/metrics`, `/bo/*` | `BO_PORT` (없으면 `PORT`) |

8080은 계열 전체 port 체계에서 BO의 자리다(ADR-0013). WebSocket listener는
없다. `BoBootstrap`이 kernel의 HTTP initializer가 받는 WebSocket handler
factory 자리에 `null`을 넘기는데, 그게 REST 전용 listener를 표현하는
방식이다.

**Thread 모델.** Event-loop thread는 codec과 router만 돌린다.
`RestEndpoint.handle()`은 상한이 있는 worker pool(`bo-worker-N`,
`WORKER_THREADS`, 기본 코어 수 × 4)로 넘긴다. Endpoint가 repository를
호출하고 JDBC는 블로킹하기 때문이다 — event loop 위에서 쿼리를 그대로
돌리면 그 thread가 맡은 모든 connection이 멈춘다(ADR-0010). Pool이 포화되면
503, endpoint가 throw하면 500이다.

**노출.** BO는 당분간 내부 전용이다. 한 가지 주의: Docker가 publish한
port는 UFW를 우회한다. 그래서 BO를 LAN으로 묶는 것은 방화벽이 막아주는
문제가 아니라 container가 무엇에 bind하느냐의 문제다.

## 4. 계층 구조

Hexagonal, `com.sunmoon.bo` 아래 package로 표현된다:

```
core/                 kernel, git submodule (com.sunmoon.platform.*)
                        listener bind, REST routing, event loop 밖 실행,
                        MyBatis/Flyway/Micrometer/OTel 결선
web/                  inbound adapter — /bo/* endpoint
  AuthorizedEndpoint    권한 decorator
  commoncode/, device/  화면들
domain/               모델 + 그것이 소유한 port (interface만)
  operator/ commoncode/ device/
persistence/          outbound adapter — MyBatis+Postgres(실제), in-memory(fake)
auth/                 session store, BCrypt 해싱
BoBootstrap.java      composition root — 구현을 고르는 유일한 곳
BoConfig.java         환경변수 세 개
```

**의존 규칙.** `domain`은 JDK 외에 아무것도 의존하지 않는다. `web`과
`persistence`는 둘 다 `domain`을 의존하고 서로는 의존하지 않는다.
`BoBootstrap`이 어떤 adapter가 어떤 port를 구현하는지 아는 유일한 곳이다.

**Kernel과의 경계는 확인 가능하다.** 이 저장소에 남은
`com.sunmoon.platform.*` import는 전부 kernel 클래스를 가리킨다 —
`grep -rho 'com\.sunmoon\.platform\.[a-zA-Z.]*' src` 한 줄로 목록이
나오고, 그 목록은 짧다. BO는 장비를 마주하는 runtime을 의존하지 않는다.
그쪽은 같은 kernel 위에 선 형제이지 아래 깔린 계층이 아니다.

## 5. 요청 처리 흐름

```
TCP → HttpServerCodec → HttpObjectAggregator → RestRequestRouter
    → [worker pool] → AuthorizedEndpoint → 구체 RestEndpoint
    → domain port → adapter
```

- **Routing**(kernel)은 `RouteKey(HttpMethod, path)`로 매칭하고 query
  string은 떼어낸다(ADR-0008). Path variable은 없다. 대상 행의 key는
  request body(`DELETE`)나 query string(`?group=`, `?type=`)으로 온다.
  매칭 실패는 404.
- **권한 검사**는 decorator라서 endpoint는 auth를 언급하지 않는다. Session
  cookie를 풀고, 운영자가 전체 관리자이거나 해당 `Screen`에 대한 권한이
  그 `Action`을 허용하면 통과시킨다. 아니면 401(session 없음) 또는
  403(session은 있으나 권한 없음).
- **Endpoint**는 kernel의 단일 method interface(`RestEndpoint:
  FullHttpRequest → FullHttpResponse`)를 구현하고, body를 파싱·검증하고,
  domain port를 호출하고, JSON을 돌려준다.

## 6. 인증 — JWT가 아니라 서버 측 session

로그인하면 서버에 저장되는 불투명한 session id를 발급하고, `/bo`로 범위를
제한한 `HttpOnly` + `SameSite=Lax` cookie로 돌려준다. 비밀번호는 BCrypt
해시(cost 12, `at.favre.lib:bcrypt`)다.

JWT가 아니라 session을 고른 이유는 딱 하나다. 탈취되었거나 퇴사한 운영자를
레코드 하나 지워서 *즉시* 끊을 수 있어야 하기 때문이다 — 관리자 화면은
그걸 원하고, instance 하나로 도는 service에서 JWT의 무상태 이점은 값을
못 한다(ADR-0004). 권한은 요청마다 다시 읽지 않고 로그인 시 한 번 읽어
session에 담는다.

| Endpoint | 목적 |
|---|---|
| `POST /bo/auth/login` | 자격 확인, session 생성, cookie 설정 |
| `POST /bo/auth/logout` | 서버 측 session 삭제, cookie 만료 |
| `GET /bo/auth/me` | 현재 운영자 + 접근 가능한 화면 (Frontend 메뉴를 그린다) |

**첫 운영자 문제.** 운영자를 관리하려면 로그인해야 하므로,
`BoBootstrap`이 `BO_ADMIN_USERNAME` / `BO_ADMIN_PASSWORD`로 전체 관리자
하나를 심는다. 단 운영자가 하나도 없을 때만, 그리고 추측 가능한 기본값은
절대 쓰지 않는다 — 변수가 없으면 경고만 남기고 아무것도 심지 않는다.

## 7. 권한 모델 — 3단계

1. **전체 관리자**(`Operator.superAdmin`) — 모든 검사를 통과한다.
2. **화면별** — `Screen`은 테이블이 아니라 코드로 정의된 enum
   (`COMMON_CODE`, `DEVICE`, `OPERATOR`)이다. 화면 목록은 운영자의
   행위가 아니라 배포로 바뀐다.
3. **화면 안의 행위별** — `OperatorScreenPermission`의 독립된 flag 네 개:
   `canView` / `canCreate` / `canSave` / `canDelete`
   (조회 / 신규 / 저장 / 삭제).

HTTP method 하나가 행위 하나에 정확히 대응한다. CRUD에 method를 구분하는
routing이 필요했던 이유가 이것이다:

| Method | Action | 의미 |
|---|---|---|
| `GET` | `VIEW` | 목록 (필터 선택) |
| `POST` | `CREATE` | 삽입, key가 있으면 409 |
| `PUT` | `SAVE` | 수정, key가 없으면 404 |
| `DELETE` | `DELETE` | body의 key로 삭제 |

`create`와 `save`를 upsert 하나로 합치지 않고 일부러 나눠 둔 것은, 신규와
저장이 서로 다른 권한이기 때문이다.

## 8. 화면

| 화면 | Endpoint | 모델 |
|---|---|---|
| 공통코드 | `/bo/common-code` (+`?group=`) | `CommonCode(groupCode, code, name, sortOrder, active)`, PK `(groupCode, code)` |
| Device | `/bo/devices` (+`?type=`) | `Device(deviceId, name, deviceType, location, active)`, PK `deviceId` |
| 운영자 | — | `OPERATOR`는 enum 값만 있고 뒤에 endpoint가 없다 (§11, 공백 2) |

`Device.deviceType`은 `DEVICE_TYPE` 공통코드를 가리키도록 의도했지만
foreign key는 **아니다**. 공통코드는 런타임에 편집되는 값이고, 타입 하나가
떠 있다고 device 행 자체를 못 읽게 되면 안 된다.

**Device는 기준 정보일 뿐이다.** 접속 상태, handshake, protocol은 아직
존재하지 않는 별도의 **Device Server**의 몫이다(ADR-0004). BO는 살아 있는
connection을 건드리지 않는다.

## 9. 영속성과 데이터 모델

MyBatis(annotation SQL) → HikariCP → PostgreSQL, schema는 Flyway. 넷 다
kernel이 결선한다. Kernel은 메커니즘을 주고 migration은 하나도 갖지
않는다(ADR-0014). Oracle 대신 Postgres인 이유는 무료 등급에 올릴 수 있어서
다(ADR-0005).

Aggregate마다 파일 세 개가 같은 모양이다 — MyBatis가 채울 가변 POJO
`*Row`, SQL을 담은 `*Mapper` interface, 행을 domain record로 옮기는
`MyBatis*Repository`.

| Migration | 테이블 | Key |
|---|---|---|
| `V1` | `operators`, `operator_screen_permissions` | `id`; `(operator_id, screen)` |
| `V2` | `common_codes` | `(group_code, code)` |
| `V3` | `devices` | `device_id` |

**BO는 자기 database를 갖는다.** 여기 migration도 V1부터고
`sun-moon-java-platform`의 것도 V1부터다. 둘을 한 database에 물려도 깨지지는
않는다 — Flyway가 로컬에서 해석 못 하는 적용 이력을 보면 migration을
거부한다 — 다만 각자 하나씩 필요하다.

접속 설정은 `POSTGRES_JDBC_URL` / `POSTGRES_USER` / `POSTGRES_PASSWORD` /
`POSTGRES_POOL_SIZE`에서 온다.

**이 중 어느 것도 살아 있는 database 대상으로 돌아본 적이 없다.** §11의
공백 1 참고.

## 10. 설정 참조

| 변수 | 기본값 | 용도 |
|---|---|---|
| `BO_PORT` | `PORT`, 없으면 `8080` | HTTP listener |
| `WORKER_THREADS` | 코어 수 × 4 | endpoint가 도는 pool (ADR-0010) |
| `COOKIE_SECURE` | `false` | Session cookie의 `Secure` — TLS 뒤에서는 반드시 `true` |
| `BO_ADMIN_USERNAME` / `BO_ADMIN_PASSWORD` | 없음 | 첫 전체 관리자, 운영자가 하나도 없을 때만 |
| `POSTGRES_JDBC_URL` | 없음 | **스위치**: 설정 → 실제 MyBatis/Postgres adapter + Flyway, 미설정 → in-memory fake |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_POOL_SIZE` | `app` / `app` / `5` | 자격과 pool 크기 |

## 11. 테스트 전략

12개, 전부 통과.

**라이브 HTTP 테스트.** `BoAuthTest`, `CommonCodeCrudTest`,
`DeviceCrudTest`는 실제 kernel runtime을 테스트 port에 띄우고
`java.net.http.HttpClient`(cookie manager 포함)로 두드린다. Mock은 없다.
실제 Netty pipeline, routing, session cookie, 권한 검사를 그대로 지난다.

테스트와 별개로, 분리 직후 패키징된 `installDist` 배포본을 끝까지 몰아봤다
— `/health` 응답, 미인증 `/bo/devices` 401, 로그인 시 session cookie 설정,
`/bo/auth/me`가 운영자 반환, 공통코드 생성·조회 왕복.

일부러 없는 것: MyBatis adapter 테스트. Testcontainers가 자연스러운
도구인데 Docker가 필요하고 이 환경에는 없다(ADR-0003).

## 12. 알려진 공백

프로덕션에서 먼저 물릴 순서대로.

1. **Database 라이브 검증이 없다.** 모든 mapper와 migration 셋이 컴파일은
   되지만 실제 PostgreSQL 위에서 돌아본 적이 없다. 검증된 것은 스위치가
   제대로 물리고 **시끄럽게** 실패한다는 것 — 패키징된 앱을 아무것도 없는
   `POSTGRES_JDBC_URL`로 띄우면 조용히 fake로 돌아가지 않고 HikariCP
   예외로 죽는다.
2. **운영자 관리 화면이 없다.** 운영자와 권한은 기동 시 seed로 만들거나
   repository를 직접 호출(`InMemoryOperatorRepository.grantPermission`,
   테스트 전용)해야만 생긴다. `OPERATOR` enum 값은 있는데 뒤가 비어 있다
   — 즉 권한 모델의 2·3단계는 강제되지만 아직 *관리할 수단이 없다*.
3. **`SessionStore`에 실제 adapter가 없다.** `InMemorySessionStore`뿐이라
   session이 프로세스와 함께 죽고 instance 간 공유가 안 된다 — 배포할
   때마다 전원 로그아웃이다. `RedisSessionStore`가 의도한 실제 adapter다.
4. **CORS 처리가 없다.** Frontend가 자기 origin에서 `credentials: include`로
   BO를 부르는 순간 필요해진다.
5. **Frontend가 없다.** BO는 API고, 여기서 말하는 화면은 오늘 기준 curl
   호출이다.
6. **Dockerfile을 한 번도 build해본 적이 없다** — 여기 Docker가
   없다(ADR-0003). 그것이 실행할 `installDist` 산출물이 뜨고 응답하는
   것*은* 검증됐다.
7. **배포되지 않았다.** 남은 단계는 대상 서버에서 `sudo`가 필요하다 —
   database 생성과 8080의 LAN 제한.

## 13. 결정 색인

분리 이후의 ADR은 여기에, 그 이전 것은 `sun-moon-java-platform`에 있고
그쪽 [설계서](https://github.com/schware/sun-moon-java-platform/blob/master/docs/DESIGN_kr.md)에서
링크된다.

| ADR | 결정 |
|---|---|
| [0014](adr/0014-split-the-kernel-from-the-domains-built-on-it.md) | Kernel 분리, BO는 자기 저장소와 container를 갖는다 |

직접 관련된 이전 결정: ADR-0004(BO 범위, session 인증, 3단계 권한),
ADR-0006(BO 인증 구현·검증), ADR-0008(공통코드 CRUD, method 구분 routing),
ADR-0009(Device CRUD), ADR-0010(endpoint를 event loop 밖에서),
ADR-0013(port 체계).

이 저장소가 존재하는 커리어 전략상의 맥락은 별도의 `Alignment` 저장소에
있다.
