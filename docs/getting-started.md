# Getting Started

How to run GPay Wallet locally, check that it works, and operate it day to day.
To try the API by hand once it is running, see [api-examples.md](api-examples.md).

- [1. Prerequisites](#1-prerequisites)
- [2. Run with Docker Compose](#2-run-with-docker-compose)
- [3. Day-to-day commands](#3-day-to-day-commands)
- [4. Run one service from Maven or your IDE](#4-run-one-service-from-maven-or-your-ide)
- [5. Upgrading an existing environment](#5-upgrading-an-existing-environment)
- [6. Operations](#6-operations)
- [7. Troubleshooting](#7-troubleshooting)

---

## 1. Prerequisites

| Tool | Version | Needed for |
|---|---|---|
| Docker + Docker Compose | Compose v2 (`docker compose`) | Running the stack |
| openssl | any | Generating the JWT key pair and secrets |
| curl, jq | any | Smoke test and API examples |
| JDK + Maven | 21 + 3.9 | Only if you run a service outside Docker ([§4](#4-run-one-service-from-maven-or-your-ide)) |

Host ports used by default: **8081–8086** (services), **5432** (Postgres), **6379** (Redis).

---

## 2. Run with Docker Compose

### Step 1 — Clone the repository
```bash
git clone https://github.com/lukmnh/monorepo-wallet.git
cd monorepo-wallet
```

### Step 2 — Generate the JWT key pair
```bash
./scripts/generate-jwt-keys.sh
```
This creates:
- `keys/jwt_private.pem`, mounted only into auth-service (signs tokens)
- `keys/jwt_public.pem`, mounted into wallet-service and payment-service (verify only)

`keys/` is git-ignored and excluded from Docker builds. Never commit it.

### Step 3 — Create `.env`
```bash
cp .env.example .env

# Fill every secret with a random value (GNU sed; on macOS use: sed -i '' ...)
for var in DB_PASSWORD REDIS_PASSWORD INTERNAL_API_KEY MOCK_GATEWAY_SECRET; do
  sed -i "s|^${var}=.*|${var}=$(openssl rand -hex 32)|" .env
done

grep -c '=change_me$' .env   # must print 0 (no placeholder values left)
```
The other values in `.env.example` are working defaults. Every variable is described in [architecture.md §7](../architecture.md#configuration-env-vars).

### Step 4 — Check that the host ports are free
```bash
# Linux
ss -ltn | grep -E ':(5432|6379|808[1-5])\b'
# macOS
lsof -nP -iTCP -sTCP:LISTEN | grep -E ':(5432|6379|808[1-5])\b'
```
If **5432** or **6379** is taken by another program, don't stop it. Pick a different host port in `.env`:
```bash
POSTGRES_HOST_PORT=15432
REDIS_HOST_PORT=16379
```
These only change the port you use from your machine. The services talk to each other over the Docker network.

### Step 5 — Build and start
```bash
docker compose up --build -d
```
The first build takes a few minutes, because each service image downloads its Maven dependencies. On the first start, Postgres creates all schemas from `db/migration/`.

### Step 6 — Verify
```bash
docker compose ps
```
All 7 containers should be `running` (postgres and redis `healthy`). Then confirm the services finished starting:
```bash
docker compose logs auth-service wallet-service payment-service audit-service mock-gateway notification-service \
  | grep -E "Started .*Application|APPLICATION FAILED"
```
You should see 5 `Started ...Application` lines and no `APPLICATION FAILED`.

Finally, run the end-to-end smoke test:
```bash
./scripts/smoke-test.sh
```
It registers two new users and exercises login, balance, top-up via the gateway webhook, idempotency, transfer, mutations, notifications (inbox, unread badge, mark-read), refresh and logout. Expected last line: `Result: 24 passed, 0 failed` (exit code 0). It creates fresh users each run, so you can repeat it any time.

The stack is ready. Continue with [api-examples.md](api-examples.md) to call the API yourself.

---

## 3. Day-to-day commands

| Task | Command |
|---|---|
| Follow logs of one service | `docker compose logs -f payment-service` |
| Find one request across services | `docker compose logs \| grep <X-Trace-Id value>` |
| Rebuild and restart after a code change | `docker compose up -d --build payment-service` |
| Stop everything (keep data) | `docker compose down` |
| Stop and **delete all data** | `docker compose down -v` |
| Open a SQL shell | `docker compose exec postgres psql -U "$DB_USER" -d "$DB_NAME"` (after `set -a; source .env; set +a`) |
| Open a Redis shell | `docker compose exec redis redis-cli -a "$REDIS_PASSWORD"` |

Every response carries an `X-Trace-Id` header. Send your own `X-Trace-Id` to correlate a request across all service logs.

---

## 4. Run one service from Maven or your IDE

Use this for a fast edit-and-run loop on one service while the rest stays in Docker. The example uses auth-service; the same pattern applies to the others.

### Step 1 — Start the stack and stop the service you will run yourself
```bash
docker compose up -d
docker compose stop auth-service
```

### Step 2 — Export the service's environment
Run from the repository root:
```bash
set -a; source .env; set +a

export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:${POSTGRES_HOST_PORT:-5432}/${DB_NAME}?currentSchema=auth"
export SPRING_DATASOURCE_USERNAME="$DB_USER" SPRING_DATASOURCE_PASSWORD="$DB_PASSWORD"
export SPRING_REDIS_HOST=localhost SPRING_REDIS_PORT="${REDIS_HOST_PORT:-6379}" SPRING_REDIS_PASSWORD="$REDIS_PASSWORD"
export JWT_PRIVATE_KEY_PATH="$PWD/keys/jwt_private.pem"   # absolute: Maven runs in the module dir
export WALLET_SERVICE_URL=http://localhost:8082
```

### Step 3 — Run it
```bash
mvn -pl auth spring-boot:run
```
In an IDE, run `AuthApplication` with the same environment variables in the run configuration.

### Variables per service

| Service | `currentSchema` | Also needs |
|---|---|---|
| auth | `auth` | Redis vars, `JWT_PRIVATE_KEY_PATH`, `JWT_ACCESS_EXPIRY_MINUTES`, `JWT_REFRESH_EXPIRY_DAYS`, `INTERNAL_API_KEY`, `WALLET_SERVICE_URL` |
| wallets | `wallet` | `JWT_PUBLIC_KEY_PATH`, `INTERNAL_API_KEY` |
| payment | `payment` | Redis vars, `JWT_PUBLIC_KEY_PATH`, `INTERNAL_API_KEY`, `MOCK_GATEWAY_SECRET`, `WALLET_SERVICE_URL`, `AUDIT_SERVICE_URL`, `PAYMENT_GATEWAY_URL`, `NOTIFICATION_SERVICE_URL` (all `http://localhost:808x`) |
| auditlog | `audit` | `INTERNAL_API_KEY` |
| notification | `notification` | `JWT_PUBLIC_KEY_PATH`, `INTERNAL_API_KEY` |
| paymentgateway | — | `MOCK_GATEWAY_SECRET`, `PAYMENT_SERVICE_WEBHOOK_URL` |

Most of these come from `.env` via `source`. Key paths must be **absolute**.

Containers can't call a service that runs on your host under its compose name. For example, mock-gateway's webhook goes to `http://payment-service:8083`. If you run payment-service locally, the webhook won't reach it, so top-ups stay `PENDING`. Either run its callers locally too, or develop leaf services this way (auth, wallets, auditlog).

---

## 5. Upgrading an existing environment

Follow this if you ran an earlier version (HS256 `JWT_SECRET`, old wallet paths) and want to keep the database.

### Step 1 — Generate the key pair
```bash
./scripts/generate-jwt-keys.sh
```

### Step 2 — Update `.env`
- Remove `JWT_SECRET`; it is no longer used.
- Add every variable that is new in `.env.example` (`JWT_ISSUER`, `LOGIN_*`, `POSTGRES_HOST_PORT`, `REDIS_HOST_PORT`).

### Step 3 — Apply the new migrations by hand
Postgres only runs `db/migration/` on an empty volume. Apply each new file once, in order:
```bash
set -a; source .env; set +a
for f in db/migration/V1__0710261200_hardening_constraints.sql \
         db/migration/V1__0710261500_pending_transfer_index.sql \
         db/migration/V1__1010261000_init_schema_notification.sql \
         db/migration/V1__1010261001_init_table_notifications.sql \
         db/migration/V1__1010261002_init_table_outbox_events.sql; do
  docker compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 < "$f"
done
```
The hardening migration fails if case-variant duplicate users exist (`Alice` and `alice`) or if existing rows break a new constraint. Fix those rows and run it again.

### Step 4 — Rebuild and restart
```bash
docker compose up --build -d
./scripts/smoke-test.sh
```
Apply the migrations **before** this step: with `ddl-auto: validate`, a service refuses to start if a column it expects is missing.

### Step 5 — Tell API clients
- **Everyone must log in again**: old HS256 access tokens are rejected. Existing refresh tokens keep working.
- Wallet endpoints moved: `/v1/api/wallet/balance` → `/api/v1/wallet/balance`, `/v1/api/wallet/api/v1/wallet/mutations` → `/api/v1/wallet/mutations`.
- Payment responses include `failureReason`. `FAILED`/`EXPIRED` return `422`, and a repeated idempotency key returns the transaction's current state.
- Users registered before this version get their wallet on their first `GET /api/v1/wallet/balance`.

---

## 6. Operations

### Rotate the JWT key pair
```bash
rm keys/jwt_private.pem keys/jwt_public.pem
./scripts/generate-jwt-keys.sh
docker compose up -d --force-recreate auth-service wallet-service payment-service
```
All access tokens become invalid at once (users log in again); refresh tokens keep working.

### Unlock a login lockout
```bash
docker compose exec redis redis-cli -a "$REDIS_PASSWORD" DEL login:fail:user:<username>
```

### Background jobs

| Service | Job | Default schedule | Effect |
|---|---|---|---|
| payment | Expire stale top-ups | every 60 s | `PENDING` top-ups older than `PENDING_EXPIRE_MINUTES` (60) → `EXPIRED` |
| payment | Reconcile transfers | every 60 s | Re-sends `PENDING` transfers older than 120 s (safe: wallet-service ignores duplicates) |
| auth | Refresh-token cleanup | daily 03:00 | Deletes expired refresh tokens |

---

## 7. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `port is already allocated` (5432 / 6379) | Another program uses the port. Set `POSTGRES_HOST_PORT` / `REDIS_HOST_PORT` in `.env` ([§2 Step 4](#step-4--check-that-the-host-ports-are-free)) |
| `port is already allocated` (8081–8086) | Free that port, or change the host side of that service's `ports:` in `docker-compose.yaml` |
| `secret "jwt_private_key"... no such file` | Keys missing: run `./scripts/generate-jwt-keys.sh` |
| `Cannot load JWT ... key` at startup | Key file not in PEM PKCS#8 / X.509 format: delete `keys/` and regenerate |
| `INTERNAL_API_KEY must be set` / `MOCK_GATEWAY_SECRET must be set` / `Could not resolve placeholder` | A variable is missing from `.env` (compose passes an empty string, which is rejected on purpose). Compare with `.env.example` |
| `Schema-validation: missing column` | Migrations not applied to an existing volume ([§5 Step 3](#step-3--apply-the-new-migrations-by-hand)) |
| Register returns `503` | wallet-service not reachable yet; nothing was saved, retry |
| Login returns `429` | Too many failed attempts; wait `Retry-After` seconds or [unlock](#unlock-a-login-lockout) |
| Top-up stays `PENDING` | `TIMEOUT` scenario (expected), or the webhook can't reach payment-service: `docker compose logs mock-gateway` |
| Smoke test: `... is not reachable` | Service still starting or crashed: `docker compose ps` and its logs |
