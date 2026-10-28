# Testing — GPay Wallet

How the system is tested, from fast unit tests to manual failure drills against the running stack. Each layer has its own command and its own expected result.

- [1. Test layers](#1-test-layers)
- [2. Core unit tests](#2-core-unit-tests)
- [3. End-to-end smoke test](#3-end-to-end-smoke-test)
- [4. Manual scenario flows](#4-manual-scenario-flows)
- [5. Coverage matrix](#5-coverage-matrix)
- [6. Adding tests](#6-adding-tests)

---

## 1. Test layers

| Layer | What it proves | Needs | Command | Time |
|---|---|---|---|---|
| **Core unit tests** | Business rules of every service (money, idempotency, security, state transitions) with all I/O mocked | JDK 21 + Maven | `mvn test -Dtest='!*ApplicationTests' -Dsurefire.failIfNoSpecifiedTests=false` | ~30 s |
| **Context tests** (`*ApplicationTests`) | Spring wiring starts | Postgres, Redis, key files, env vars | `mvn test` with the stack's env | — |
| **Smoke test** | The happy path across all 6 services through public APIs | Running stack, `curl`, `jq` | `./scripts/smoke-test.sh` | ~15 s |
| **Manual scenario flows** | Failure handling: timeouts, outages, races, abuse | Running stack | [§4](#4-manual-scenario-flows) | per scenario |

Unit tests run without Docker. Run them on every change, and run the smoke test before merging.

---

## 2. Core unit tests

JUnit 5 + Mockito + AssertJ (all from `spring-boot-starter-test`). No Spring context, DB, Redis or network: repositories, clients and Redis are mocked, and `TransactionTemplate` runs on a mocked `PlatformTransactionManager`, so callbacks execute inline.

### Run

```bash
# All modules (the *ApplicationTests context tests need a DB, so exclude them)
mvn test -Dtest='!*ApplicationTests' -Dsurefire.failIfNoSpecifiedTests=false

# One module
mvn test -pl payment -Dtest='!*ApplicationTests' -Dsurefire.failIfNoSpecifiedTests=false

# One class / one method
mvn test -pl payment -Dtest=TransferServiceImplTest
mvn test -pl payment -Dtest='TransferServiceImplTest#unknownOutcomeRetriesOnceThenStaysPendingNeverFailed'
```

Expected: `BUILD SUCCESS`, **86 tests, 0 failures**. Reports: `<module>/target/surefire-reports/`.

### What is covered

| Module | Test class | Rules verified |
|---|---|---|
| auth | `AuthServiceImplTest` (16) | Register: lowercase normalization, duplicate username/email, >72-byte password, wallet failure propagates (user rolls back). Login: token pair + counter reset, wrong password counted, **unknown user still runs BCrypt** (no timing leak), inactive revealed only after correct password, lockout checked before password. Refresh: rotation, **reuse of rotated token revokes all sessions**, expired-not-revoked does not, unknown token, inactive user. Logout revokes |
| auth | `LoginAttemptServiceTest` (5) | Lock at limit with TTL as `Retry-After`, per-IP lock, below limit passes, **fail-open** on Redis outage, success clears only the user counter |
| wallets | `WalletServiceImplTest` (10) | Credit adds + ledger row (before/after), **duplicate reference is a no-op**, debit insufficient balance, transfer moves money with DEBIT+CREDIT legs, **locks in ascending user-id order** (deadlock-free), duplicate transfer no-op, insufficient balance changes nothing, self-transfer rejected, lazy wallet creation |
| payment | `TransferServiceImplTest` (8) | SUCCESS + outbox event in one TX, **lost CAS emits no second notification**, 422 → FAILED + daily reservation released, **timeout → 1 retry → stays PENDING (never FAILED)**, daily limit, self-transfer before reserving, idempotent replay, reconciler re-send |
| payment | `TopupServiceImplTest` (4) | Gateway accepted → gatewayRef stored, PENDING (no credit without webhook), gateway 4xx → FAILED, timeout → PENDING, replay skips gateway |
| payment | `WebhookServiceImplTest` (7) | SUCCESS credits + notification, **late SUCCESS on EXPIRED still credits**, FAILED no credit, duplicate delivery no-op, **forged signature rejected before lookup**, amount mismatch, fallback lookup by transactionId |
| payment | `IdempotentReplayTest` (3) | Same request replays current state (scale-insensitive), different amount/type → 422 |
| payment | `LimitsAndIdempotencyTest` (10) | Rate limit allow/block, fail-open; daily cap sends **integer cents** to the Lua script, refusal, **fail-closed** on Redis outage, remaining limit; idempotency lock acquire/contend/fail-open/invalid key |
| payment | `NotificationOutboxServiceImplTest` (2) | Event payload: eventId = outbox PK, recipient, nominal, `IDR`; top-up has no counterparty |
| payment | `OutboxRelaySchedulerTest` (5) | Lease → publish → PUBLISHED; transient error → retry with attempts+1 and future `next_attempt_at`; last attempt → DEAD; 400 → DEAD immediately; nothing due → no-op |
| auditlog | `AuditLogServiceImplTest` (3) | Field mapping + JSON payloads, null payload, unserializable payload stored as `NULL` |
| paymentgateway | `GatewayCoreTest` (4) | `GW-XXXXXXXX` ref + async dispatch, **HMAC signature matches payment's verifier format**, TIMEOUT sends nothing, blank secret fails startup |
| notification | `NotificationServiceImplTest` (6) | Top-up → 1 notification + push, transfer → sender + recipient, **redelivery deduped, no second push**, transfer without recipient → 400, mark-read 404 for others' ids, mark-read idempotent |
| notification | `NotificationTemplatesTest` (3) | Rupiah formatting (`Rp1.250.000`, `Rp10.000,5`), copy text and recipients |

Deliberately **not** unit-tested (covered by the smoke test or §4 instead): controllers, filters, JPA queries, Lua script semantics, `@Async`/`@Scheduled`/`@Transactional` proxies.

---

## 3. End-to-end smoke test

```bash
docker compose up --build -d
./scripts/smoke-test.sh
```

Expected last line: `Result: 24 passed, 0 failed` (exit code 0). It creates two fresh users per run, so it can be repeated.

| Step | Checks |
|---|---|
| 1. Register | alice + bob → 201 |
| 2. Login | 200, access token received |
| 3. Balance | 200, starts at 0 |
| 4. Top-up 50,000 (SUCCESS) | 202 PENDING → settled by webhook → balance 50,000 |
| 5. Idempotency | same key → 200 SUCCESS replay; same key, other amount → 422 |
| 6. Transfer 10,000 | 200 SUCCESS; 9,000,000 → 422 FAILED (insufficient) |
| 7. Mutations | DEBIT, CREDIT newest first |
| 8. Notifications | alice inbox `TRANSFER_SENT,TOPUP_SUCCESS`, body shows `Rp50.000`, unread 2 → read-all → 0; bob gets `TRANSFER_RECEIVED … Rp10.000` |
| 9. Refresh / logout | refresh 200, logout 200, refresh after logout 401 |

Override URLs with `AUTH_URL`, `WALLET_URL`, `PAYMENT_URL`, `NOTIFICATION_URL`. A failure prints the expected and actual values; correlate with `docker compose logs <service>` using the `X-Trace-Id`.

---

## 4. Manual scenario flows

Failure paths the smoke test does not exercise. Setup for every flow:

```bash
AUTH=http://localhost:8081 WALLET=http://localhost:8082 PAYMENT=http://localhost:8083 NOTIF=http://localhost:8086
set -a; source .env; set +a
psql_() { docker compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -c "$1"; }
# Register + login a user and put the token in $TOKEN (see api-examples.md §1–2); a second user's id in $BOB_ID
H=(-H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json')
```

### 4.1 Auth

| # | Scenario | Steps | Expected |
|---|---|---|---|
| A1 | Brute-force lockout | 5× login with a wrong password, then the correct one | 401 ×5, then **429** + `Retry-After` even with the right password |
| A2 | No username enumeration | Wrong password for an existing user vs. a non-existent user | Same 401 message; similar response time |
| A3 | Refresh token reuse | Refresh with token R1 → get R2; refresh with R1 again; then refresh with R2 | 200, **401**, **401** (reuse revoked every session) |
| A4 | Wallet down during register | `docker compose stop wallet-service`; register; start it; register again with the same data | **503**, then 201 (no orphan user was left) |
| A5 | Token tampering | Change one character of the access token, call `/wallet/balance` | 401 |

### 4.2 Top-up

| # | Scenario | Steps | Expected |
|---|---|---|---|
| T1 | Gateway FAILED | `POST /topup` with `"scenario":"FAILED"`, poll `GET /transactions/{id}` | PENDING → **FAILED** with `failureReason`; balance unchanged; **no notification** |
| T2 | Gateway TIMEOUT → expiry | Set `PENDING_EXPIRE_MINUTES=1` and `PAYMENT_SCHEDULER_INTERVAL_MS=10000` in `.env`, recreate payment-service; top up with `"scenario":"TIMEOUT"`; wait ~90 s | **EXPIRED** + reason; no credit, no notification |
| T3 | Late webhook after expiry | Continue T2: send a signed SUCCESS webhook for that transaction ([api-examples: simulate a webhook](api-examples.md#advanced-simulate-a-gateway-webhook)) | **SUCCESS**, wallet credited, `TOPUP_SUCCESS` notification arrives |
| T4 | Duplicate webhook | Send the same signed SUCCESS webhook twice | Both 200; credited **once** (1 CREDIT mutation), 1 notification |
| T5 | Forged webhook | Send a webhook with a wrong `X-Webhook-Signature` | **401**; nothing changes |
| T6 | Tampered amount | Validly signed webhook with a different `amount` | **400** `Amount mismatch` |

### 4.3 Transfer

| # | Scenario | Steps | Expected |
|---|---|---|---|
| X1 | Concurrent duplicate | Fire the same transfer with the same `X-Idempotency-Key` 5× in parallel (`for i in 1 2 3 4 5; do curl … & done; wait`) | One executes; others get the same transaction or **409** (in flight); balance debited **once** |
| X2 | Unknown outcome + reconciliation | `docker compose stop wallet-service`; transfer; start wallet-service; wait ~2–3 min; poll the transaction | **202 PENDING** → reconciler → **SUCCESS**; sender and recipient each get **one** notification |
| X3 | Daily limit | With `DAILY_TRANSFER_LIMIT=20000`, transfer 15,000 then 10,000 | 200, then **422** `Daily transfer limit exceeded. Remaining: 5000.00` |
| X4 | Daily limit released on failure | Transfer more than the balance (422 FAILED), then a valid transfer within the cap | The failed amount did **not** consume the cap |
| X5 | Rate limit | 6 payment calls within one minute (new key each) | 6th → **429** + `Retry-After: 60` |
| X6 | Self transfer | `toUserId` = your own id | **400** |
| X7 | Key reuse, other body | Repeat a key with a different amount | **422** `Idempotency key was already used for a different request` |
| X8 | Ownership | User B calls `GET /transactions/{A's id}` | **404** (not 403: no id probing) |

### 4.4 Notifications

| # | Scenario | Steps | Expected |
|---|---|---|---|
| N1 | Delivery latency | Top-up SUCCESS, then poll `GET /notifications` | Appears within ~2–3 s of the transaction turning SUCCESS |
| N2 | Notification-service outage | `docker compose stop notification-service`; do a top-up; check `psql_ "SELECT status, attempts, next_attempt_at, last_error FROM payment.outbox_events ORDER BY created_at DESC LIMIT 3"`; start the service | Payment still succeeds. The row stays `PENDING` with growing `attempts` and backoff, then becomes `PUBLISHED` and the notification appears. **Nothing is lost** |
| N3 | Dead letter + replay | With the service down, set `OUTBOX_MAX_ATTEMPTS=2` and wait for `DEAD`; start the service; `psql_ "UPDATE payment.outbox_events SET status='PENDING', attempts=0, next_attempt_at=now() WHERE status='DEAD'"` | Row → `PUBLISHED`; notification delivered once |
| N4 | Exactly-once under redelivery | `psql_ "UPDATE payment.outbox_events SET status='PENDING', next_attempt_at=now()"` (re-send everything) | Inbox unchanged: dedupe on `(transaction_id, type)`; ingest answers `created: 0` |
| N5 | Badge + read | `GET /notifications/unread-count`, `PATCH /notifications/{id}/read` twice, `PATCH /notifications/read-all` | Count drops; second read is still 200 (idempotent); read-all returns `updated` |
| N6 | Isolation | User B: `PATCH /notifications/{A's id}/read` | **404**; A's notification stays unread |
| N7 | Internal API guarded | `POST $NOTIF/api/v1/internal/notifications/events` without / with a wrong `X-Internal-Api-Key` | **403** |
| N8 | Push hook | `docker compose logs notification-service \| grep PUSH` after a top-up | One `PUSH userId=… type=TOPUP_SUCCESS` line per notification (logging stub) |

### 4.5 Platform

| # | Scenario | Steps | Expected |
|---|---|---|---|
| P1 | Trace propagation | Send `-H 'X-Trace-Id: drill-123'` on a transfer; `docker compose logs \| grep drill-123` | Same trace id in payment, wallet, audit and (via the outbox) notification logs |
| P2 | Audit trail | `psql_ "SELECT action, status FROM audit.audit_logs WHERE trace_id='drill-123'"` | `TRANSFER` row with the final status |
| P3 | Redis outage | `docker compose stop redis`; login, top-up, transfer | Login and top-up work (fail-open); **transfer fails** (daily cap fails closed, a money control) |
| P4 | Internal endpoints closed | Call `POST $WALLET/api/v1/internal/wallet/credit` without the internal key | **403** |

---

## 5. Coverage matrix

Each business rule mapped to the layer that verifies it.

| Rule | Unit | Smoke | Manual |
|---|:-:|:-:|:-:|
| Register normalizes identity, rejects duplicates | ✅ | ✅ | |
| No user without a wallet | ✅ | | A4 |
| Login lockout / no enumeration | ✅ | | A1, A2 |
| Refresh rotation + reuse detection | ✅ | ✅ | A3 |
| Top-up credited only by a signed webhook | ✅ | ✅ | T5, T6 |
| Late webhook after expiry still credits | ✅ | | T2, T3 |
| Duplicate webhook / credit is a no-op | ✅ | | T4 |
| Idempotent replay + key reuse detection | ✅ | ✅ | X1, X7 |
| Transfer is atomic and deadlock-free | ✅ | ✅ | X1 |
| Unknown outcome → PENDING → reconciled | ✅ | | X2 |
| Daily cap reserve / release, fail-closed | ✅ | | X3, X4, P3 |
| Rate limit, fail-open | ✅ | | X5, P3 |
| Ownership (404 for others' resources) | ✅ (notification) | | X8, N6 |
| Notification with nominal on top-up / transfer success | ✅ | ✅ | N1 |
| Exactly one notification per recipient per transaction | ✅ | | N4, X2 |
| No notification lost when notification-service is down | ✅ | | N2, N3 |
| Inbox, unread badge, mark read | ✅ | ✅ | N5 |
| Gateway signature contract | ✅ | ✅ | T5 |
| Audit trail and trace propagation | ✅ (mapping) | | P1, P2 |

Known gaps: no automated integration tests against a real Postgres/Redis (JPA queries, Lua scripts, `ON CONFLICT`, `SKIP LOCKED`). The next step is Testcontainers tests for `TransactionRepository.resolvePending`, `OutboxEventRepository.lockDue` and `NotificationRepository.insertIfAbsent`.

---

## 6. Adding tests

- **Place** a unit test next to the class under test, in the same package (e.g. `payment/src/test/java/com/gpay/payment/service/Impl/`). Same package gives access to package-private helpers like `IdempotentReplay`.
- **Style:** `@ExtendWith(MockitoExtension.class)`, constructor-built SUT, AssertJ assertions. Test names describe the rule (`lostCompareAndSetDoesNotEmitSecondNotification`), not the method.
- **Transactions:** pass a mocked `PlatformTransactionManager`; `TransactionTemplate` then runs the callback inline.
- **Redis scripts:** stub `execute(any(RedisScript.class), anyList(), any(Object[].class))`; verify exact args with one `eq(...)` per vararg.
- **New business rule** → add a unit test here, a row in [§5](#5-coverage-matrix), and a smoke check or §4 drill if it crosses services.
