# GPay Wallet — Microservices

Wallet system built from Spring Boot microservices: registration and login, top-up through a (mock) payment gateway, P2P transfer, balance and mutation history, with idempotency, rate limiting and audit logging.

**Stack:** Java 21 · Spring Boot 3.5 · PostgreSQL 16 · Redis 7 · Docker Compose

## Documentation

| Document | Read it to… |
|---|---|
| [docs/getting-started.md](docs/getting-started.md) | Run the apps step by step, verify, day-to-day commands, run one service from Maven/IDE, upgrade, troubleshoot |
| [docs/testing.md](docs/testing.md) | Run the core unit tests and smoke test, follow manual failure drills, see the coverage matrix |
| [docs/api-examples.md](docs/api-examples.md) | Call every endpoint with copy-paste `curl` examples and real responses |
| [architecture.md](architecture.md) | Understand services, flows, security model, configuration |
| [database.md](database.md) | Understand schemas, constraints, Redis keys, migrations |

## Services

| Service | Port | Responsibility |
|---|---|---|
| auth-service | 8081 | Register (also creates the wallet), login with brute-force lockout, refresh, logout; signs RS256 tokens |
| wallet-service | 8082 | Balance, mutation history; internal credit/debit/transfer with row locking |
| payment-service | 8083 | Top-up, transfer, gateway webhook; idempotency, rate and daily limits, background jobs |
| audit-service | 8084 | Internal audit log |
| mock-gateway | 8085 | Simulated payment gateway (`SUCCESS` / `FAILED` / `TIMEOUT`) |
| notification-service | 8086 | In-app inbox + push for successful top-up / transfer (with nominal), fed by payment's transactional outbox |
| postgres / redis | 5432 / 6379 | Storage; host ports configurable in `.env` |

## Quick start

Requires Docker Compose v2, `openssl`, `curl` and `jq`. Full explanation of each step: [docs/getting-started.md](docs/getting-started.md).

```bash
# 1. Generate the JWT key pair (keys/ is git-ignored)
./scripts/generate-jwt-keys.sh

# 2. Create .env with random secrets
cp .env.example .env
for var in DB_PASSWORD REDIS_PASSWORD INTERNAL_API_KEY MOCK_GATEWAY_SECRET; do
  sed -i "s|^${var}=.*|${var}=$(openssl rand -hex 32)|" .env   # macOS: sed -i ''
done

# 3. Build and start
docker compose up --build -d

# 4. Verify end to end (expects: Result: 24 passed, 0 failed)
./scripts/smoke-test.sh
```

If port 5432 or 6379 is already in use on your machine, set `POSTGRES_HOST_PORT` / `REDIS_HOST_PORT` in `.env` before step 3.

## Project layout

```
auth/  wallets/  payment/  auditlog/  paymentgateway/  notification/   # one Maven module per service
db/migration/                                           # SQL run by Postgres on first start
scripts/generate-jwt-keys.sh                            # RS256 key pair → keys/
scripts/smoke-test.sh                                   # end-to-end check via the public API
docs/                                                   # getting started, API examples
docker-compose.yaml  .env.example
```

## Technical decisions & trade-offs

| Decision | Why | Trade-off |
|---|---|---|
| Pessimistic locking (`SELECT … FOR UPDATE`) on wallets | Correctness over throughput for money | Hot wallets serialize; very high volume needs sharding or per-wallet queues |
| Lock both wallets in ascending user-id order | `A→B` and `B→A` at the same time cannot deadlock | Negligible |
| Money invariants as DB constraints | A code bug cannot double-credit or write impossible ledger rows | Violations surface as 500s; migrations need care |
| Idempotency: DB `UNIQUE (user_id, key)` + short Redis lock | DB is the source of truth for replays; Redis only stops concurrent duplicates | Redis outage → fail-open on the lock, the DB still guarantees exactly-once |
| Commit `PENDING` before calling gateway/wallet | Webhooks always find the transaction; no DB connection held during HTTP calls | Needs reconciliation for unknown outcomes (implemented) |
| Transfer outcome unknown → stay `PENDING` + reconciler | Never mark `FAILED` something that may have moved money | Reconciler retries until wallet-service answers |
| RS256 tokens (private key only in auth) | A compromised wallet/payment service cannot mint tokens | Key distribution and rotation needed |
| Login lockout in Redis (per user + per IP) | Stops password guessing; unknown usernames lock too (no enumeration) | Attacker can lock a known username for 15 min; fails open if Redis is down |
| Notifications via transactional outbox + idempotent consumer | A success is never notified twice or lost, even if notification-service is down | Delivery is near-real-time (2 s poll), not instant; push is a logging stub until FCM is wired |
| Async audit with MDC propagation | Audit never slows or breaks payments; logs stay correlated | Events lost if audit-service is down (no queue/outbox) |
| HMAC-SHA256 webhooks, constant-time compare | Rejects forged callbacks without a timing oracle | Shared secret rotation is manual |
