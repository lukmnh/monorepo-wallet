# Database — GPay Wallet

Persistence layer: one PostgreSQL 16 instance split into **5 schemas** (one per service), plus **Redis** for payment-service locks/limits and auth-service login lockout.

> Source of truth: [db/migration/](db/migration/) (including the hardening migration [V1__0710261200_hardening_constraints.sql](db/migration/V1__0710261200_hardening_constraints.sql)) and the JPA entities. For service context see [architecture.md](architecture.md).

---

## 1. Layout

| Schema | Owner service | Tables | JDBC `currentSchema` |
|---|---|---|---|
| `auth` | auth-service | `users`, `refresh_tokens` | `auth` |
| `wallet` | wallet-service | `wallets`, `mutations` | `wallet` |
| `payment` | payment-service | `transactions`, `topup_requests`, `transfer_requests`, `outbox_events` | `payment` |
| `audit` | audit-service | `audit_logs` | `audit` |
| `notification` | notification-service | `notifications` | `notification` |

Rules:

- A service reads/writes **only its own schema**. Cross-service data is fetched via REST, never via SQL joins.
- Cross-schema relations are **logical only** (UUIDs, no foreign keys). Physical FKs exist only inside a schema.
- All services connect with the same DB user (`DB_USER`), so the separation is by convention, not by privileges.
- PKs: `UUID DEFAULT gen_random_uuid()` (DB) / `GenerationType.UUID` (JPA).
- Money: `NUMERIC(19,2)` ↔ `BigDecimal`. All amount columns have `CHECK (amount > 0)`.
- Timestamps: `TIMESTAMP` (no time zone), filled from the JVM clock (`LocalDateTime.now()`, `@CreationTimestamp`, `@UpdateTimestamp`). Containers run UTC by default.
- Enums are stored as `VARCHAR` (`@Enumerated(EnumType.STRING)`) and guarded by `CHECK (col IN (...))`.
- **Money invariants live in the database**, not only in Java: non-negative balance, ledger maths, one ledger entry per wallet/reference/type, one transaction per user/idempotency key.

---

## 2. Entity-relationship diagram

```mermaid
erDiagram
    %% ---------- auth schema ----------
    USERS {
        uuid id PK
        varchar username UK "50, stored lowercase, UNIQUE lower()"
        varchar email UK "100, stored lowercase, UNIQUE lower()"
        varchar password "bcrypt"
        boolean is_active
        timestamp created_at
        timestamp updated_at
    }
    REFRESH_TOKENS {
        uuid id PK
        uuid user_id FK
        varchar token_hash UK "sha256 hex of opaque token"
        timestamp expires_at
        boolean revoked
        timestamp created_at
    }
    USERS ||--o{ REFRESH_TOKENS : "has (ON DELETE CASCADE)"

    %% ---------- wallet schema ----------
    WALLETS {
        uuid id PK
        uuid user_id UK "-> auth.users.id (logical)"
        numeric balance "CHECK >= 0"
        bigint version "optimistic lock"
        timestamp created_at
        timestamp updated_at
    }
    MUTATIONS {
        uuid id PK
        uuid wallet_id FK "UK(wallet_id, reference_id, type)"
        varchar type "CHECK CREDIT | DEBIT"
        numeric amount "CHECK > 0"
        numeric balance_before
        numeric balance_after "CHECK = before +/- amount"
        varchar reference_id "-> payment.transactions.id (logical)"
        varchar description
        timestamp created_at
    }
    WALLETS ||--o{ MUTATIONS : "ledger"

    %% ---------- payment schema ----------
    TRANSACTIONS {
        uuid id PK
        varchar idempotency_key "UK(user_id, idempotency_key)"
        uuid user_id "-> auth.users.id (logical)"
        varchar type "CHECK TOPUP | TRANSFER"
        varchar status "CHECK PENDING | SUCCESS | FAILED | EXPIRED"
        numeric amount "CHECK > 0"
        varchar description
        varchar failure_reason
        varchar trace_id
        timestamp created_at
        timestamp updated_at
    }
    TOPUP_REQUESTS {
        uuid id PK
        uuid transaction_id FK,UK
        varchar gateway_ref UK
        varchar callback_status
        timestamp webhook_received_at
        timestamp expires_at
        timestamp created_at
    }
    TRANSFER_REQUESTS {
        uuid id PK
        uuid transaction_id FK,UK
        uuid from_user_id "CHECK <> to_user_id"
        uuid to_user_id
        timestamp created_at
    }
    TRANSACTIONS ||--o| TOPUP_REQUESTS : "1:1 if TOPUP"
    TRANSACTIONS ||--o| TRANSFER_REQUESTS : "1:1 if TRANSFER"

    %% ---------- audit schema ----------
    AUDIT_LOGS {
        uuid id PK
        varchar trace_id
        uuid user_id
        uuid transaction_id
        varchar service
        varchar action
        varchar status
        jsonb request_payload
        jsonb response_payload
        text error_message
        bigint duration_ms
        varchar ip_address
        timestamp created_at
    }
```

### Logical (cross-schema) references

```
auth.users.id ─┬─< wallet.wallets.user_id              (1:1, unique)
               ├─< payment.transactions.user_id
               ├─< payment.transfer_requests.from_user_id / to_user_id
               ├─< audit.audit_logs.user_id
               └─< notification.notifications.user_id  (recipient)

payment.transactions.id ─┬─< wallet.mutations.reference_id   (as VARCHAR; 1 CREDIT for topup, DEBIT + CREDIT for transfer)
                         ├─< audit.audit_logs.transaction_id
                         └─< notification.notifications.transaction_id  (1 for topup, 2 for transfer)

payment.outbox_events.id  ≈  notification.notifications.event_id   (correlation)

payment.transactions.trace_id  ≈  audit.audit_logs.trace_id   (X-Trace-Id correlation)
```

None of these are enforced; orphans are possible (e.g. a user deleted in `auth` keeps their wallet).

---

## 3. Tables

### 3.1 `auth.users`

Migration: [V1__0506260839_init_table_users.sql](db/migration/V1__0506260839_init_table_users.sql) · Entity: [Users.java](auth/src/main/java/com/gpay/auth/entity/Users.java)

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | UUID | N | `gen_random_uuid()` | PK. Used as JWT `sub` and as `user_id` across all services |
| `username` | VARCHAR(50) | N | | Stored lowercase; login identifier |
| `email` | VARCHAR(100) | N | | Stored lowercase |
| `password` | VARCHAR(255) | N | | BCrypt hash. Input limited to 72 UTF-8 bytes (BCrypt limit) |
| `is_active` | BOOLEAN | N | `TRUE` | `false` → login 403 (only after a correct password), refresh rejected and all sessions revoked |
| `created_at` / `updated_at` | TIMESTAMP | N | `NOW()` | |

Constraints / indexes: `UNIQUE(username)`, `UNIQUE(email)`, `uq_users_username_lower`, `uq_users_email_lower` (unique on `lower()`; case-variant duplicates are rejected by the DB even if a caller skips normalization).

Concurrent register with the same identity: the loser hits the unique index at commit → `DataIntegrityViolationException` → HTTP 409.

### 3.2 `auth.refresh_tokens`

Migration: [V1__0506260841__init_table_refresh_token.sql](db/migration/V1__0506260841__init_table_refresh_token.sql) · Entity: [RefreshToken.java](auth/src/main/java/com/gpay/auth/entity/RefreshToken.java)

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | UUID | N | `gen_random_uuid()` | PK |
| `user_id` | UUID | N | | FK → `auth.users(id)` ON DELETE CASCADE |
| `token_hash` | VARCHAR(255) | N | | UNIQUE; SHA-256 hex of the raw token. Raw token never stored |
| `expires_at` | TIMESTAMP | N | | now + `JWT_REFRESH_EXPIRY_DAYS` |
| `revoked` | BOOLEAN | N | `FALSE` | Set on rotation, logout, reuse detection, inactive user |
| `created_at` | TIMESTAMP | N | `NOW()` | |

Indexes: `idx_refresh_tokens_user_id` (revoke-all), `idx_refresh_tokens_expires_at` (cleanup).

Token format: opaque 256-bit random value (base64url, 43 chars). It is not a JWT: it is validated only by hash lookup, so it cannot be presented to resource services as an access token. Tokens issued before this change (JWT format) still work until they expire, because lookup is by hash.

Rotation protocol ([AuthServiceImpl.refreshToken](auth/src/main/java/com/gpay/auth/service/Impl/AuthServiceImpl.java)):

```sql
-- consumeIfActive: atomic compare-and-set; of N concurrent refreshes exactly one gets rowcount 1
UPDATE auth.refresh_tokens SET revoked = true
WHERE token_hash = ? AND revoked = false AND expires_at > now();
```

| rowcount | Token state | Action |
|---|---|---|
| 1 | active | Issue new pair (unless user inactive → revoke all, 401) |
| 0 | already revoked | **Reuse detected** → revoke every token of the user (committed via `noRollbackFor`), 401 |
| 0 | expired | 401 |

Lifecycle: `RefreshTokenCleanupJob` deletes rows with `expires_at < now()` daily (`JWT_CLEANUP_CRON`, default 03:00), using `idx_refresh_tokens_expires_at`. Revoked-but-unexpired rows are kept on purpose: deleting them would turn a replayed stolen token into a plain "invalid token" instead of triggering reuse detection.

### 3.3 `wallet.wallets`

Migration: [V1__0606261108_init_table_wallets.sql](db/migration/V1__0606261108_init_table_wallets.sql) · Entity: [Wallets.java](wallets/src/main/java/com/gpay/wallets/entity/Wallets.java)

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | UUID | N | `gen_random_uuid()` | PK |
| `user_id` | UUID | N | | UNIQUE — exactly one wallet per user |
| `balance` | NUMERIC(19,2) | N | `0.00` | `CHECK (balance >= 0)` — last line of defence against overdraft |
| `version` | BIGINT | N | `0` | JPA `@Version`. Left `null` on new entities so Spring Data uses `persist()`, not `merge()` |
| `created_at` / `updated_at` | TIMESTAMP | N | `NOW()` | `@CreationTimestamp` / `@UpdateTimestamp` |

Access patterns:
- Read: `findByUserId` (balance, mutations).
- Write: `findByUserIdForUpdate` → `SELECT … WHERE user_id = ? FOR UPDATE` before every credit/debit/transfer. Transfers lock two rows in ascending `user_id` order.
- Lazy creation on first `GET balance` / `GET mutations`:
  ```sql
  INSERT INTO wallet.wallets (user_id) VALUES (?) ON CONFLICT (user_id) DO NOTHING;
  ```
  Concurrent first accesses are a no-op instead of a unique violation (which in Postgres would abort the whole transaction).

### 3.4 `wallet.mutations`

Migration: [V1__0606261109_init_table_mutations.sql](db/migration/V1__0606261109_init_table_mutations.sql) · Entity: [Mutations.java](wallets/src/main/java/com/gpay/wallets/entity/Mutations.java)

Append-only balance ledger.

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | UUID | N | PK |
| `wallet_id` | UUID | N | FK → `wallet.wallets(id)` |
| `type` | VARCHAR(20) | N | `CHECK IN ('CREDIT','DEBIT')` |
| `amount` | NUMERIC(19,2) | N | `CHECK (amount > 0)` |
| `balance_before` / `balance_after` | NUMERIC(19,2) | N | `chk_mutations_balance_math`: CREDIT ⇒ after = before + amount, DEBIT ⇒ after = before − amount |
| `reference_id` | VARCHAR(100) | Y | `payment.transactions.id` as text. Top-up → 1 row, transfer → 2 rows (DEBIT on sender, CREDIT on receiver) |
| `description` | VARCHAR(255) | Y | Transfer rows prefixed `Transfer out:` / `Transfer in:` |
| `created_at` | TIMESTAMP | N | |

Constraints / indexes:
- `uq_mutations_wallet_ref_type UNIQUE (wallet_id, reference_id, type)`: DB-level idempotency. `NULL` references are not deduplicated.
- `idx_mutations_wallet_created (wallet_id, created_at DESC)`: matches `findByWalletIdOrderByCreatedAtDesc`.
- `idx_mutations_reference_id`: reconciliation lookups by payment transaction.

Idempotency in code: `credit`, `debit` and `atomicTransfer` first lock the wallet row(s), then check `existsByWalletIdAndReferenceIdAndType`. Duplicates are skipped and return the current balance. Because the check runs under the row lock, concurrent duplicates are serialized; the unique constraint is the backstop.

### 3.5 `payment.transactions`

Migration: [V1__0606262000_init_table_transactions.sql](db/migration/V1__0606262000_init_table_transactions.sql) · Entity: [Transactions.java](payment/src/main/java/com/gpay/payment/entity/Transactions.java)

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | UUID | N | `gen_random_uuid()` | PK; returned to client as `transactionId` |
| `idempotency_key` | VARCHAR(100) | N | | From `X-Idempotency-Key`. Unique **per user** |
| `user_id` | UUID | N | | Initiator (top-up owner / transfer sender) |
| `type` | VARCHAR(15) | N | | `CHECK IN ('TOPUP','TRANSFER')` |
| `status` | VARCHAR(15) | N | `'PENDING'` | `CHECK IN ('PENDING','SUCCESS','FAILED','EXPIRED')` |
| `amount` | NUMERIC(19,2) | N | | `CHECK (amount > 0)` |
| `description` | VARCHAR(255) | Y | | Defaulted in code (`Top up via payment gateway` / `Transfer`) |
| `failure_reason` | VARCHAR(255) | Y | | Why FAILED/EXPIRED, e.g. `Gateway reported status FAILED`, `No gateway callback within 60 minutes` |
| `trace_id` | VARCHAR(100) | Y | | Request trace id |
| `created_at` / `updated_at` | TIMESTAMP | N | `NOW()` | |

Constraints / indexes:
- `uq_transactions_user_idem UNIQUE (user_id, idempotency_key)`: two users can use the same key; one user cannot reuse a key.
- `idx_transactions_user_created (user_id, created_at DESC)`: user history.
- `idx_transactions_created_at (created_at DESC)`.
- `idx_transactions_pending_topup (created_at) WHERE status='PENDING' AND type='TOPUP'`: partial index for the expiry job.
- `idx_transactions_pending_transfer (created_at) WHERE status='PENDING' AND type='TRANSFER'`: partial index for the transfer reconciler ([V1__0710261500](db/migration/V1__0710261500_pending_transfer_index.sql)).

Both partial indexes stay small because rows drop out as soon as they leave `PENDING`.

Status state machine:

```mermaid
stateDiagram-v2
    [*] --> PENDING : committed before any external call
    PENDING --> SUCCESS : webhook SUCCESS + credit / wallet transfer 200 / reconciler 200
    PENDING --> FAILED : webhook FAILED, gateway 4xx, wallet 4xx (insufficient, wallet not found)
    PENDING --> EXPIRED : expiry job, TOPUP older than PENDING_EXPIRE_MINUTES
    EXPIRED --> SUCCESS : late SUCCESS webhook (gateway took the money)
    SUCCESS --> [*]
    FAILED --> [*]
    EXPIRED --> [*]
```

Rules:
- Every transition out of `PENDING` is a compare-and-set (`UPDATE … WHERE status = 'PENDING'`, `resolvePending`/`expirePendingTopups`), or happens under `SELECT … FOR UPDATE` (webhook). Concurrent actors can't overwrite each other.
- `SUCCESS` and `FAILED` are final. `EXPIRED` can only become `SUCCESS`, and only via a signed webhook.
- Failed transfers **are persisted** (`FAILED` + `failure_reason`). Replaying the same idempotency key returns that stored result.
- A transfer stays `PENDING` only when wallet-service's answer was lost (timeout/5xx); the reconciler re-sends it.

### 3.6 `payment.topup_requests`

Migration: [V1__0606262001_init_table_topup_request.sql](db/migration/V1__0606262001_init_table_topup_request.sql) · Entity: [TopupRequest.java](payment/src/main/java/com/gpay/payment/entity/TopupRequest.java)

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | UUID | N | PK |
| `transaction_id` | UUID | N | UNIQUE, FK → `payment.transactions(id)` (1:1) |
| `gateway_ref` | VARCHAR(100) | Y | `GW-XXXXXXXX` from gateway; primary webhook lookup key, `uq_topup_requests_gateway_ref`. NULL until the gateway answers; set by whichever comes first (initiate response or webhook) via `… WHERE gateway_ref IS NULL`. If NULL, the webhook falls back to `transaction_id` |
| `callback_status` | VARCHAR(50) | Y | Raw webhook status |
| `webhook_received_at` | TIMESTAMP | Y | |
| `expires_at` | TIMESTAMP | N | now + `PENDING_EXPIRE_MINUTES`. Not used by the scheduler (it uses `transactions.created_at`) |
| `created_at` | TIMESTAMP | N | |

Indexes: `idx_topup_requests_expires_at`.

### 3.7 `payment.transfer_requests`

Migration: [V1__0606262002_init_table_transfer_request.sql](db/migration/V1__0606262002_init_table_transfer_request.sql) · Entity: [TransferRequest.java](payment/src/main/java/com/gpay/payment/entity/TransferRequest.java)

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | UUID | N | PK |
| `transaction_id` | UUID | N | UNIQUE, FK → `payment.transactions(id)` (1:1) |
| `from_user_id` | UUID | N | Same as `transactions.user_id`. `CHECK (from_user_id <> to_user_id)` |
| `to_user_id` | UUID | N | Recipient |
| `created_at` | TIMESTAMP | N | |

Indexes: `idx_transfer_requests_from_user_id`, `idx_transfer_requests_to_user_id`.

### 3.8 `audit.audit_logs`

Migration: [V1__0706261301_init_table_audit_log.sql](db/migration/V1__0706261301_init_table_audit_log.sql) · Entity: [AuditLog.java](auditlog/src/main/java/com/gpay/auditlog/entity/AuditLog.java)

Append-only event log written via `POST /api/v1/internal/audit`.

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | UUID | N | PK |
| `trace_id` | VARCHAR(100) | Y | Correlates with service logs and `payment.transactions.trace_id` |
| `user_id` | UUID | Y | |
| `transaction_id` | UUID | Y | |
| `service` | VARCHAR(50) | N | e.g. `payment-service` |
| `action` | VARCHAR(100) | N | `TOPUP_INITIATED`, `TOPUP_WEBHOOK`, `TRANSFER` |
| `status` | VARCHAR(50) | Y | Transaction status at event time |
| `request_payload` / `response_payload` | JSONB | Y | Mapped as `String` with `@JdbcTypeCode(SqlTypes.JSON)`, so it binds as `jsonb`. Payloads Jackson can't serialize are stored as `NULL` with a warning (`toString()` would not be valid JSON) |
| `error_message` | TEXT | Y | |
| `duration_ms` | BIGINT | Y | |
| `ip_address` | VARCHAR(45) | Y | IPv6-sized. Currently always null (callers pass `null`) |
| `created_at` | TIMESTAMP | N | |

Indexes: `idx_audit_logs_trace_id`, `idx_audit_logs_user_id`, `idx_audit_logs_transaction_id`, `idx_audit_logs_created_at DESC`.

No retention/partitioning; grows unbounded.

### 3.9 `payment.outbox_events`

Migration: [V1__1010261002_init_table_outbox_events.sql](db/migration/V1__1010261002_init_table_outbox_events.sql) · Entity: [OutboxEvent.java](payment/src/main/java/com/gpay/payment/entity/OutboxEvent.java)

Transactional outbox. A row is inserted **in the same DB transaction** that moves a payment to `SUCCESS` (webhook TX for top-ups, `resolvePending` TX for transfers), so the event exists if and only if the status change committed. `OutboxRelayScheduler` delivers it to notification-service.

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | UUID | N | PK, assigned in Java; sent downstream as `eventId` |
| `aggregate_id` | UUID | N | `payment.transactions.id`. `UNIQUE (aggregate_id, event_type)`: inserted with `ON CONFLICT DO NOTHING` |
| `event_type` | VARCHAR(50) | N | `TOPUP_SUCCEEDED`, `TRANSFER_SUCCEEDED` |
| `payload` | JSONB | N | Full event as sent (`PaymentSucceededEvent`) |
| `status` | VARCHAR(15) | N | `PENDING` → `PUBLISHED` \| `DEAD` (CHECK) |
| `attempts` | INT | N | Failed deliveries so far |
| `next_attempt_at` | TIMESTAMP | N | Due time; also the claim lease (now + 60 s) while a relay is sending |
| `last_error` | VARCHAR(500) | Y | Last delivery error |
| `trace_id` | VARCHAR(100) | Y | Restored into the MDC when relaying |
| `created_at` / `published_at` | TIMESTAMP | N / Y | |

Indexes: partial `idx_outbox_events_due (next_attempt_at) WHERE status = 'PENDING'`.

`DEAD` rows (10 failed attempts, or rejected with 400) need a manual replay: `UPDATE payment.outbox_events SET status='PENDING', attempts=0, next_attempt_at=now() WHERE status='DEAD';`. Published rows are kept (no purge job yet).

### 3.10 `notification.notifications`

Migration: [V1__1010261001_init_table_notifications.sql](db/migration/V1__1010261001_init_table_notifications.sql) · Entity: [Notification.java](notification/src/main/java/com/gpay/notification/entity/Notification.java)

In-app inbox, one row per recipient. Title/body are rendered at ingest and stored, so the inbox shows the same text as the push.

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | UUID | N | PK, assigned in Java |
| `event_id` | UUID | N | `payment.outbox_events.id` (correlation) |
| `user_id` | UUID | N | Recipient |
| `type` | VARCHAR(30) | N | `TOPUP_SUCCESS`, `TRANSFER_SENT`, `TRANSFER_RECEIVED` (CHECK) |
| `title` / `body` | VARCHAR(100) / VARCHAR(255) | N | e.g. `Top up berhasil` / `Saldo Rp50.000 sudah masuk ke GPay kamu.` |
| `amount`, `currency` | NUMERIC(19,2), VARCHAR(3) | N | `CHECK (amount > 0)`; currency `IDR` |
| `transaction_id` | UUID | N | Deep-link target. `UNIQUE (transaction_id, type)` = dedupe key for redelivered events |
| `is_read`, `read_at` | BOOLEAN, TIMESTAMP | N, Y | |
| `created_at` | TIMESTAMP | N | When the payment succeeded (`occurredAt`), not when the event arrived |

Indexes: `idx_notifications_user_created (user_id, created_at DESC)` for the inbox; partial `idx_notifications_user_unread (user_id) WHERE is_read = FALSE` for the badge.

---

## 4. Redis keyspace

Client: `StringRedisTemplate`, all values are strings. Every read-modify-write runs as **one Lua script** (atomic, and no key is ever left without a TTL).

| Key pattern | Service | Value | TTL | Purpose |
|---|---|---|---|---|
| `idempotency:lock:{userId}:{idempotencyKey}` | payment | random owner token | 60 s | Blocks concurrent duplicates only. `SET NX EX`; released with compare-and-delete (only by its owner) when the request ends |
| `rate:payment:{userId}:{epochMinute}` | payment | counter | 60 s | Max `PAYMENT_RATE_LIMIT_PER_MINUTE` (5) topup+transfer calls per minute |
| `daily:transfer-cents:{userId}:{yyyy-MM-dd}` | payment | integer cents reserved today | 25 h | `DAILY_TRANSFER_LIMIT`. Reserved atomically before the transfer (`INCRBY`, roll back if over the cap); released (`DECRBY`, only if the key exists) when the transfer definitively fails |
| `login:fail:user:{username}` | auth | failed-login counter | `LOGIN_LOCK_MINUTES` (15 min) from first failure | ≥ `LOGIN_MAX_ATTEMPTS_PER_USER` (5) → login locked; deleted on successful login |
| `login:fail:ip:{ip}` | auth | failed-login counter | 15 min | ≥ `LOGIN_MAX_ATTEMPTS_PER_IP` (20) → login locked for that IP |

Notes:
- **Replays are answered from Postgres** (`uq_transactions_user_idem`), not Redis. A replay therefore always returns the transaction's *current* state, and losing Redis never causes a double charge.
- Redis failure policy: idempotency lock, rate limit and login lockout **fail open** (logged at ERROR); the daily transfer cap **fails closed** (request errors), because it is a money control.
- Day boundaries for the daily cap use the JVM clock (UTC in the containers).
- The old key formats (`idempotency:{userId}:{key}` response cache, `daily:transfer:*` decimal strings) are no longer read; they expire on their own.

---

## 5. Transactions & locking

| Operation | Service | DB transaction scope | Locks |
|---|---|---|---|
| Register / login / logout | auth | One `@Transactional` per call | none |
| Refresh | auth | One TX; `noRollbackFor = InvalidTokenException` so reuse-revocation commits | row lock taken by the conditional `UPDATE` |
| Credit (top-up) | wallets | Lock wallet → duplicate check → update balance → insert mutation | `FOR UPDATE` on 1 wallet row |
| Debit | wallets | Same as credit, plus balance check | `FOR UPDATE` on 1 wallet row |
| Atomic transfer | wallets | One TX: duplicate check, both legs + 2 mutations | `FOR UPDATE` on 2 rows, ascending `user_id` order |
| Register | auth | One TX: insert user (flushed) → call wallet-service create → commit; any failure rolls the user back | unique indexes |
| Create / lazy-create wallet | wallets | `INSERT … ON CONFLICT DO NOTHING` then read (idempotent) | none |
| Top-up initiate | payment | TX1 commit PENDING rows → gateway call (no TX) → conditional `gateway_ref` update | none |
| Webhook | payment | One TX: lookup → `SELECT transactions … FOR UPDATE` → wallet credit (idempotent) → status | row lock on the transaction |
| Transfer | payment | TX1 commit PENDING rows → wallet call (no TX) → TX2: `resolvePending` compare-and-set + outbox insert (only if it won) | none (wallet locks its rows) |
| Outbox relay | payment | TX: `SELECT … FOR UPDATE SKIP LOCKED` due rows + lease → commit → HTTP per event (no TX) → mark published/failed | row locks only during the claim |
| Notification ingest | notification | One TX: `INSERT … ON CONFLICT (transaction_id, type) DO NOTHING` per recipient; push after commit | unique index |
| Expire stale top-ups | payment | Single conditional bulk `UPDATE … WHERE status='PENDING'` | waits on rows a webhook has locked |
| Reconcile transfers | payment | Per transaction: wallet call (no TX) → `resolvePending` | none |
| Refresh-token cleanup | auth | Single `DELETE … WHERE expires_at < now()` | none |

payment-service never holds a DB transaction (or Hikari connection) while waiting on another service, except in the webhook, where the row lock must cover the credit. `open-in-view` is disabled in every service.

---

## 6. Migrations

- Files live in [db/migration/](db/migration/) and are mounted into the postgres container at `/docker-entrypoint-initdb.d`.
- Postgres runs them **once**, in filename order, only when the `postgres_data` volume is empty. Changes to existing files or new files are **not** applied to an existing volume.
- No migration tool (Flyway/Liquibase) is used, by decision. Files are named `V1__<ddMMyyHHmm>_<what>.sql`, so filename order is chronological. New files must be written to be re-runnable where possible (`IF NOT EXISTS`) and applied by hand on existing volumes.
- Services run `spring.jpa.hibernate.ddl-auto: validate`; startup fails if entities and tables drift.

Execution order:

```
V1__0506260838_init_schema_auth.sql
V1__0506260839_init_table_users.sql
V1__0506260841__init_table_refresh_token.sql
V1__0606261105_init_schema_wallet.sql
V1__0606261108_init_table_wallets.sql
V1__0606261109_init_table_mutations.sql
V1__0606261959_init_schema_payment.sql
V1__0606262000_init_table_transactions.sql
V1__0606262001_init_table_topup_request.sql
V1__0606262002_init_table_transfer_request.sql
V1__0706261300_init_schema_audit.sql
V1__0706261301_init_table_audit_log.sql
V1__0710261200_hardening_constraints.sql      # constraints, per-user idempotency, index cleanup, failure_reason
V1__0710261500_pending_transfer_index.sql     # partial index for the transfer reconciler
V1__1010261000_init_schema_notification.sql
V1__1010261001_init_table_notifications.sql   # notification inbox
V1__1010261002_init_table_outbox_events.sql   # payment transactional outbox
```

Applying new migrations:

```bash
# Fresh environment (destroys data): runs every file automatically
docker compose down -v && docker compose up --build

# Existing volume: apply each new file once, in order, by hand
set -a; source .env; set +a
for f in db/migration/V1__0710261200_hardening_constraints.sql \
         db/migration/V1__0710261500_pending_transfer_index.sql \
         db/migration/V1__1010261000_init_schema_notification.sql \
         db/migration/V1__1010261001_init_table_notifications.sql \
         db/migration/V1__1010261002_init_table_outbox_events.sql; do
  docker exec -i postgres psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 < "$f"
done
```

On an existing volume the migration first lowercases `auth.users.username/email`. It fails if case-variant duplicates already exist (e.g. `Alice` and `alice`), or if existing rows violate a new `CHECK`/`UNIQUE`. Resolve those rows first.

Deploy order: **migration first, then services.** The new `Transactions.failureReason` mapping fails `ddl-auto: validate` if the column is missing.

---

## 7. Hardening status

| Item | Status |
|---|---|
| Ledger `CHECK`s (amount > 0, balance maths, enum values) | ✅ `V1__0710261200` |
| DB-level ledger idempotency `UNIQUE (wallet_id, reference_id, type)` | ✅ + code checks under the row lock |
| Per-user idempotency `UNIQUE (user_id, idempotency_key)` | ✅ + Redis keys scoped by user |
| Case-insensitive unique username/email | ✅ `lower()` unique indexes + normalization in auth-service |
| `UNIQUE (gateway_ref)` | ✅ |
| Query-shaped indexes (mutation history, pending-topup partial, user history) | ✅ |
| Drop indexes duplicating UNIQUE constraints | ✅ |
| `failure_reason` on transactions | ✅ set by webhook (FAILED) and scheduler (EXPIRED) |
| `jsonb` audit payload mapping | ✅ `@JdbcTypeCode(SqlTypes.JSON)` |
| Race-safe lazy wallet create | ✅ `INSERT … ON CONFLICT DO NOTHING` |
| `TransferRequest` explicit `schema = "payment"` | ✅ |
| Persist FAILED transfers with reason | ✅ outcome recorded after commit-first; unknown outcomes reconciled |
| Atomic daily transfer cap | ✅ Lua reserve/release in integer cents |
| Refresh-token cleanup job | ✅ `RefreshTokenCleanupJob` (expired rows only) |
| Partial index for pending-transfer reconciler | ✅ `V1__0710261500` |
| Migration versioning tool | ⛔ not adopted by decision (Flyway excluded); manual apply on existing volumes |
| Separate DB role per schema (`audit` INSERT-only) | ⏳ open |
| `TIMESTAMPTZ` + `Instant` | ⏳ open: must change DB and entities in one release (`validate`) |
| `mutations.reference_id` as `UUID` | ⏳ open |
| Audit retention / partitioning | ⏳ open |
