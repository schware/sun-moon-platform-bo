# sun-moon-platform-bo

Back Office for the `sun-moon` platform — operator accounts, Common Code
(공통코드), and Device management, served as `/bo/*` REST endpoints on port
8080.

Java 21, Netty, MyBatis + Flyway on PostgreSQL. No Spring, no DI container:
every wire-up is by hand in [`BoBootstrap`](src/main/java/com/sunmoon/bo/BoBootstrap.java).

## Clone

The kernel is a git submodule, so the plain `git clone` gives you an empty
`core/` and a build that cannot resolve anything:

```bash
git clone --recurse-submodules https://github.com/schware/sun-moon-platform-bo.git
```

Already cloned without it? `git submodule update --init`.

## Run

```bash
./gradlew installDist && ./build/install/sun-moon-platform-bo/bin/sun-moon-platform-bo
```

With no `POSTGRES_JDBC_URL` set, BO runs entirely on in-memory repositories
— a machine with only a JDK runs the whole thing. Set it and BO switches to
PostgreSQL, running Flyway first; a bad URL fails loudly at startup rather
than quietly serving fakes.

| Variable | Default | |
| --- | --- | --- |
| `BO_PORT` | 8080 | falls back to `PORT` |
| `WORKER_THREADS` | cores × 4 | pool endpoints run on |
| `COOKIE_SECURE` | false | must be true behind TLS |
| `POSTGRES_JDBC_URL` | — | absent ⇒ in-memory fakes |
| `BO_ADMIN_USERNAME` / `BO_ADMIN_PASSWORD` | — | seeds the first 전체관리자, only when no operator exists |

## Authorization

Three tiers, decided in ADR-0004 and enforced by `AuthorizedEndpoint`:

1. **전체 관리자** — a super admin passes every check.
2. **화면별 권한** — an operator is granted specific `Screen`s.
3. **화면별 조회 / 신규 / 저장 / 삭제** — and within a screen, specific
   `Action`s.

Every business route in `BoBootstrap.routes()` is wrapped with the screen
and action it needs, so a route that *isn't* protected is visibly
unwrapped in that one table rather than quietly open somewhere else.

Sessions are opaque ids in an `HttpOnly`, `SameSite=Lax` cookie scoped to
`/bo`; passwords are BCrypt at cost 12.

## The kernel

`core/` is [sun-moon-platform-core](https://github.com/schware/sun-moon-platform-core),
included as a Gradle composite build. It supplies listener binding, REST
routing, and the rule that **endpoints never run on a Netty event-loop
thread** — they run on the pool `BoBootstrap` owns, because one blocking
JDBC call on an event loop stalls every other connection that loop serves.

BO imports `com.sunmoon.platform.*` only for kernel classes. It does not
depend on the device-facing runtime; that is a sibling built on the same
kernel, not a layer underneath. See ADR-0014.

## Database

BO owns its own database — migrations here number from V1, and so do the
runtime platform's. Pointing both at one database does not corrupt it:
Flyway refuses to migrate a history table holding migrations it can't
resolve.

```bash
sudo -u postgres createdb -O sunmoon platform_bo
```
