*🇰🇷 Korean version: [DESIGN_kr.md](DESIGN_kr.md)*

# sun-moon-platform-bo — Design

As-built design of this repository as of 2026-09-09. This describes what
exists and runs, and states plainly what doesn't — the "Known gaps"
section at the end is part of the design, not an afterthought.

For *why* each decision was made, see [`docs/adr/`](adr/) here and in
[`sun-moon-java-platform`](https://github.com/schware/sun-moon-java-platform/tree/master/docs/adr),
where the ADRs predating the split live.

---

## 1. What this is

Back Office for the `sun-moon` platform: the administrative surface for
operator accounts, Common Code (공통코드), and Device master data. One JVM
process, one HTTP listener, `/bo/*` REST endpoints, no Spring anywhere
(ADR-0002).

It used to be three route groups inside `sun-moon-java-platform`'s single
runtime. It is its own service now because the port scheme allocates one
container per service and because BO is internal-only while the runtime it
came from is device-facing — a difference in exposure that should not
share a JVM (ADR-0014).

## 2. Principles that actually drive the design

1. **Administration is not workload.** BO manages the data the platform
   runs on; it does not serve devices, hold connections, or run batch. Its
   traffic is a handful of operators, so its scaling story is "one
   instance is enough" and its design optimises for auditability instead.
2. **No framework magic.** No DI container, no annotation scanning.
   Everything is wired by hand in one composition root
   (`BoBootstrap.java`).
3. **Ports and adapters, with fakes as the default.** Every external
   dependency sits behind a domain-owned interface with two
   implementations: an in-memory fake wired in by default and covered by
   tests, and a real adapter that compiles but is **not** live-verified
   here (ADR-0003, ADR-0005). `./gradlew run` works on a machine with
   nothing but a JDK.
4. **Authorization is structural, not scattered.** Every protected route
   is wrapped at the point it is registered, so an unprotected route is
   visible in one table rather than absent from a file nobody reads.
5. **Say what is verified.** Claims here distinguish "tested against a
   running server" from "compiles". See §10.

## 3. Runtime topology

One process, one listener. The Netty groups — one boss thread, a worker
group at Netty's default of 2 × cores — come from the kernel.

```
                        ┌──────────────── one JVM ────────────────┐
                        │                                          │
  Operators   ──8080────┼─▶ HTTP listener "BO"                     │
  (LAN only)            │     /health  /metrics  /bo/*             │
                        │            │                             │
                        │            ▼                             │
                        │     bo-worker-N pool ──▶ repositories    │
                        └──────────────────────────────────────────┘
```

| Port | Serves | Env override |
|---|---|---|
| 8080 | `/health`, `/metrics`, `/bo/*` | `BO_PORT` (falls back to `PORT`) |

8080 is BO's slot in the family-wide scheme (ADR-0013). There is no
WebSocket listener: `BoBootstrap` passes `null` where the kernel's HTTP
initializer takes a WebSocket handler factory, which is how a REST-only
listener is expressed.

**Threading.** Event-loop threads run the codec and the router only.
`RestEndpoint.handle()` is dispatched to a bounded worker pool
(`bo-worker-N`, sized by `WORKER_THREADS`, default `cores × 4`), because
endpoints call repositories and JDBC blocks — a query inline on the event
loop would stall every connection that thread serves (ADR-0010). Pool
saturation answers 503; an endpoint that throws answers 500.

**Exposure.** BO is internal-only for now. Note that Docker's published
ports bypass UFW, so restricting BO to the LAN is a matter of what the
container binds, not what the firewall allows.

## 4. Layering

Hexagonal, expressed as packages under `com.sunmoon.bo`:

```
core/                 the kernel, a git submodule (com.sunmoon.platform.*)
                        listener binding, REST routing, off-event-loop
                        execution, MyBatis/Flyway/Micrometer/OTel wiring
web/                  inbound adapters — the /bo/* endpoints
  AuthorizedEndpoint    the permission decorator
  commoncode/, device/  the screens
domain/               the model + the ports it owns (interfaces only)
  operator/ commoncode/ device/
persistence/          outbound adapters — MyBatis+Postgres (real), in-memory (fake)
auth/                 session store, BCrypt hashing
BoBootstrap.java      composition root — the only place implementations are chosen
BoConfig.java         the three environment-driven settings
```

**Dependency rule.** `domain` depends on nothing but the JDK. `web` and
`persistence` both depend on `domain`, never on each other. `BoBootstrap`
is the single place that knows which adapter implements which port.

**The boundary with the kernel is checkable.** Every
`com.sunmoon.platform.*` import in this repository names a kernel class —
`grep -rho 'com\.sunmoon\.platform\.[a-zA-Z.]*' src` lists them, and the
list is short. BO does not depend on the device-facing runtime; that is a
sibling built on the same kernel, not a layer underneath.

## 5. Request lifecycle

```
TCP → HttpServerCodec → HttpObjectAggregator → RestRequestRouter
    → [worker pool] → AuthorizedEndpoint → concrete RestEndpoint
    → domain port → adapter
```

- **Routing** (kernel) matches on `RouteKey(HttpMethod, path)`, query
  string stripped (ADR-0008). No path variables: a target row's key
  travels in the request body (`DELETE`) or query string (`?group=`,
  `?type=`). Unmatched → 404.
- **Authorization** is a decorator, so endpoints never mention auth. It
  resolves the session cookie, then allows the call if the operator is a
  super admin, or if their permission for that `Screen` allows that
  `Action`. Otherwise 401 (no session) or 403 (session, no permission).
- **Endpoints** implement the kernel's single-method interface
  (`RestEndpoint: FullHttpRequest → FullHttpResponse`), parse and validate
  their body, call domain ports, and return JSON.

## 6. Authentication — server-side sessions, not JWT

Login issues an opaque session id stored server-side and returned in an
`HttpOnly` + `SameSite=Lax` cookie scoped to `/bo`. Passwords are BCrypt
hashes (cost 12, `at.favre.lib:bcrypt`).

Sessions were chosen over JWT specifically so a compromised or offboarded
operator can be revoked *immediately* by deleting one record — an admin
panel wants that, and JWT's stateless advantage doesn't pay off for a
service that runs as one instance (ADR-0004). Permissions are loaded once
at login and cached in the session rather than re-read per request.

| Endpoint | Purpose |
|---|---|
| `POST /bo/auth/login` | verify credentials, create session, set cookie |
| `POST /bo/auth/logout` | delete the session server-side, expire the cookie |
| `GET /bo/auth/me` | current operator + accessible screens (drives the Frontend menu) |

**First-operator bootstrap.** BO requires a login to manage operators, so
`BoBootstrap` seeds one super admin from `BO_ADMIN_USERNAME` /
`BO_ADMIN_PASSWORD` — but only when no operator exists, and never with a
guessable default: if the variables are unset it logs a warning and seeds
nothing.

## 7. Permission model — three tiers

1. **Super admin** (`Operator.superAdmin`) — bypasses all checks.
2. **Per screen** — `Screen` is a code-defined enum (`COMMON_CODE`,
   `DEVICE`, `OPERATOR`), not a table: the screen list changes with
   deployments, not by an operator's action.
3. **Per action within a screen** — four independent flags on
   `OperatorScreenPermission`: `canView` / `canCreate` / `canSave` /
   `canDelete` (조회 / 신규 / 저장 / 삭제).

Each HTTP method maps to exactly one action, which is why CRUD needs
method-aware routing:

| Method | Action | Semantics |
|---|---|---|
| `GET` | `VIEW` | list (optionally filtered) |
| `POST` | `CREATE` | insert; 409 if the key exists |
| `PUT` | `SAVE` | update; 404 if the key doesn't exist |
| `DELETE` | `DELETE` | delete by key in the body |

`create` and `save` are deliberately distinct operations, not an upsert,
because 신규 and 저장 are distinct permissions.

## 8. Screens

| Screen | Endpoints | Model |
|---|---|---|
| Common Code | `/bo/common-code` (+`?group=`) | `CommonCode(groupCode, code, name, sortOrder, active)`, PK `(groupCode, code)` |
| Device | `/bo/devices` (+`?type=`) | `Device(deviceId, name, deviceType, location, active)`, PK `deviceId` |
| Operator | — | `OPERATOR` exists as an enum value with no endpoints behind it (§11, gap 2) |

`Device.deviceType` is expected to reference a `DEVICE_TYPE` Common Code
but is **not** a foreign key: Common Codes are runtime-editable, and a
dangling type should not make a device row unreadable.

**Devices are master data only.** Connection state, handshake and protocol
belong to a separate **Device Server** that does not exist yet (ADR-0004).
BO never touches live connections.

## 9. Persistence and data model

MyBatis (annotation-mapped SQL) over HikariCP over PostgreSQL, with Flyway
for schema — all four wired by the kernel, which supplies the mechanism
and none of the migrations (ADR-0014). Postgres replaced Oracle because it
is deployable on free-tier hosting and Oracle is not (ADR-0005).

Each aggregate follows the same three-file shape: a `*Row` mutable POJO
for MyBatis to populate, a `*Mapper` interface holding the SQL, and a
`MyBatis*Repository` translating rows to domain records.

| Migration | Table | Key |
|---|---|---|
| `V1` | `operators`, `operator_screen_permissions` | `id`; `(operator_id, screen)` |
| `V2` | `common_codes` | `(group_code, code)` |
| `V3` | `devices` | `device_id` |

**BO owns its own database.** Migrations here number from V1, and so do
`sun-moon-java-platform`'s. Pointing both services at one database does
not corrupt it — Flyway refuses to migrate a history table holding applied
migrations it cannot resolve locally — but each needs its own.

Connection settings come from `POSTGRES_JDBC_URL` / `POSTGRES_USER` /
`POSTGRES_PASSWORD` / `POSTGRES_POOL_SIZE`.

**None of this has run against a live database.** See §11, gap 1.

## 10. Configuration reference

| Variable | Default | Purpose |
|---|---|---|
| `BO_PORT` | `PORT`, else `8080` | the HTTP listener |
| `WORKER_THREADS` | `cores × 4` | pool endpoints run on (ADR-0010) |
| `COOKIE_SECURE` | `false` | `Secure` on the session cookie — must be `true` behind TLS |
| `BO_ADMIN_USERNAME` / `BO_ADMIN_PASSWORD` | none | first super admin, seeded only if no operator exists |
| `POSTGRES_JDBC_URL` | none | **the switch**: set → real MyBatis/Postgres adapters + Flyway; unset → in-memory fakes |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_POOL_SIZE` | `app` / `app` / `5` | credentials and pool size |

## 11. Testing strategy

12 tests, all passing.

**Live HTTP tests.** `BoAuthTest`, `CommonCodeCrudTest` and
`DeviceCrudTest` start the real kernel runtime on test ports and drive it
with `java.net.http.HttpClient`, including its cookie manager. Nothing is
mocked; these exercise the actual Netty pipeline, routing, session
cookies, and permission checks.

Beyond the tests, the packaged `installDist` distribution was driven
end-to-end after the split: `/health` up, `/bo/devices` 401
unauthenticated, login setting the session cookie, `/bo/auth/me` returning
the operator, Common Code round-tripping create and list.

Deliberately absent: any test of the MyBatis adapters. Testcontainers
would be the natural tool and needs Docker, which this environment doesn't
have (ADR-0003).

## 12. Known gaps

Ordered by what would bite first in production.

1. **No live database verification.** All mappers and all three
   migrations compile but have never executed against a real PostgreSQL
   instance. What *is* verified is that the switch engages and fails
   loudly: running the packaged app with `POSTGRES_JDBC_URL` pointing at
   nothing exits with a HikariCP failure rather than silently falling back
   to fakes.
2. **No Operator management screen.** Operators and their permissions can
   only be created by the startup seed or by calling the repository
   directly (`InMemoryOperatorRepository.grantPermission`, test-only). The
   `OPERATOR` screen enum value exists with nothing behind it — which
   means tier 2 and tier 3 of the permission model are enforced but not
   yet *administrable*.
3. **`SessionStore` has no real adapter.** Only `InMemorySessionStore`
   exists, so sessions die with the process and cannot be shared across
   instances — every deploy logs everyone out. A `RedisSessionStore` is
   the intended real adapter.
4. **No CORS handling.** Required once a Frontend runs on its own origin
   and calls BO with `credentials: include`.
5. **No Frontend.** BO is an API; the screens it describes are curl calls
   today.
6. **The Dockerfile has never been built** — no Docker here (ADR-0003).
   The packaged `installDist` output it runs *is* verified to boot and
   serve.
7. **Not deployed.** The remaining steps need `sudo` on the target
   server — creating the database and restricting 8080 to the LAN.

## 13. Decision index

ADRs written after the split live here; earlier ones live in
`sun-moon-java-platform` and are linked from its
[design document](https://github.com/schware/sun-moon-java-platform/blob/master/docs/DESIGN.md).

| ADR | Decision |
|---|---|
| [0014](adr/0014-split-the-kernel-from-the-domains-built-on-it.md) | Split the kernel out; BO becomes its own repository and container |

Directly relevant earlier decisions: ADR-0004 (BO scope, session auth,
3-tier permissions), ADR-0006 (BO auth built and verified), ADR-0008
(Common Code CRUD, method-aware routing), ADR-0009 (Device CRUD),
ADR-0010 (endpoints off the event loop), ADR-0013 (the port scheme).

Career-strategy context for why this repository exists lives in the
separate `Alignment` repository.
