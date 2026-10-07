# API Examples

Copy-paste `curl` examples for every public endpoint, in the order a client uses them. Each step stores the values the next step needs in shell variables.

Prerequisites: the stack is running ([getting-started.md](getting-started.md)), plus `curl` and `jq`.
For an automated check of the same flow, run `./scripts/smoke-test.sh`.

- [Conventions](#conventions)
- [0. Setup](#0-setup)
- [1. Register](#1-register)
- [2. Login](#2-login)
- [3. Wallet balance](#3-wallet-balance)
- [4. Top-up](#4-top-up)
- [5. Get a transaction](#5-get-a-transaction)
- [6. Transfer](#6-transfer)
- [7. Mutation history](#7-mutation-history)
- [8. Refresh token](#8-refresh-token)
- [9. Logout](#9-logout)
- [Error reference](#error-reference)
- [Advanced: simulate a gateway webhook](#advanced-simulate-a-gateway-webhook)

---

## Conventions

| Item | Rule |
|---|---|
| Base URLs | auth `:8081`, wallet `:8082`, payment `:8083` |
| Body format | JSON, `Content-Type: application/json` |
| Response envelope | `{ "success": bool, "message": string, "data": object \| null }`. Errors use the same shape |
| Authentication | `Authorization: Bearer <accessToken>` on wallet and payment endpoints. Tokens last 15 minutes |
| Idempotency | `X-Idempotency-Key: <1–100 chars>` is **required** on `POST /topup` and `POST /transfer`. Use a new key per operation, and reuse it **only** to retry that same operation |
| Money | Decimal with at most 2 fraction digits. Top-up 10,000 – 50,000,000; transfer ≥ 1,000 |
| Tracing | Every response has an `X-Trace-Id` header; send your own to correlate across services |

---

## 0. Setup

```bash
AUTH=http://localhost:8081
WALLET=http://localhost:8082
PAYMENT=http://localhost:8083
```

---

## 1. Register

`POST /api/v1/auth/register`. Creates the user **and** their wallet.

```bash
curl -s -X POST $AUTH/api/v1/auth/register -H 'Content-Type: application/json' \
  -d '{"username":"alice","email":"alice@example.com","password":"password123"}' | jq

BOB_ID=$(curl -s -X POST $AUTH/api/v1/auth/register -H 'Content-Type: application/json' \
  -d '{"username":"bob","email":"bob@example.com","password":"password123"}' | jq -r .data.userId)
```

`201 Created`
```json
{
  "success": true,
  "message": "Registration successful",
  "data": {
    "userId": "5d052c70-12ba-4d0a-b1bc-eacc51b3e214",
    "username": "alice",
    "email": "alice@example.com"
  }
}
```

Rules: username 3–50 alphanumeric chars, not only digits, stored lowercase. Password 8–72 chars.

| Status | When |
|---|---|
| `400` | Validation failed (message lists the fields) |
| `409` | Username or email already registered (case-insensitive) |
| `503` | wallet-service unavailable. Nothing was saved, so retrying is safe |

---

## 2. Login

`POST /api/v1/auth/login`

```bash
LOGIN=$(curl -s -X POST $AUTH/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"password123"}')
echo "$LOGIN" | jq
TOKEN=$(echo "$LOGIN" | jq -r .data.accessToken)
REFRESH=$(echo "$LOGIN" | jq -r .data.refreshToken)
```

`200 OK`
```json
{
  "success": true,
  "message": "Login successful",
  "data": {
    "accessToken": "eyJhbGciOiJSUzI1NiJ9...",
    "refreshToken": "Fyec4uE7yxaz...",
    "userId": "5d052c70-12ba-4d0a-b1bc-eacc51b3e214",
    "accessExpiresIn": 900,
    "tokenType": "Bearer"
  }
}
```

| Status | When |
|---|---|
| `401` | Wrong username or password (same message for both) |
| `403` | Account disabled (only shown after a correct password) |
| `429` | 5 failed attempts for this username within 15 min (or 20 from this IP). Blocks even the correct password. Has a `Retry-After` header |

```json
{ "success": false, "message": "Too many failed login attempts. Try again in 900 seconds.", "data": null }
```

---

## 3. Wallet balance

`GET /api/v1/wallet/balance`

```bash
curl -s $WALLET/api/v1/wallet/balance -H "Authorization: Bearer $TOKEN" | jq
```

`200 OK`
```json
{
  "success": true,
  "message": "Balance retrieved",
  "data": {
    "walletId": "69ab2fd7-6d49-4c3c-b9e6-c4c292f8eb95",
    "userId": "5d052c70-12ba-4d0a-b1bc-eacc51b3e214",
    "balance": 0.00,
    "updatedAt": "2026-10-07T06:51:21.734722"
  }
}
```

Without a valid token, `401`:
```json
{ "success": false, "message": "Unauthorized", "data": null }
```

---

## 4. Top-up

`POST /api/v1/topup`. Asynchronous: the response is `PENDING`, and the gateway webhook settles it about 1.5 s later.

```bash
TOPUP=$(curl -s -X POST $PAYMENT/api/v1/topup -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H 'X-Idempotency-Key: topup-001' \
  -d '{"amount":50000,"scenario":"SUCCESS","description":"First top-up"}')
echo "$TOPUP" | jq
TOPUP_ID=$(echo "$TOPUP" | jq -r .data.transactionId)
```

`202 Accepted`
```json
{
  "success": true,
  "message": "Top-up is pending confirmation",
  "data": {
    "transactionId": "5d56d612-cc9b-4527-bbaa-2a1f2270eb46",
    "type": "TOPUP",
    "status": "PENDING",
    "amount": 50000.00,
    "description": "First top-up",
    "failureReason": null,
    "createdAt": "2026-10-07T06:51:22.127308"
  }
}
```

`scenario` drives the mock gateway:

| `scenario` | Result |
|---|---|
| `SUCCESS` | Webhook after ~1.5 s → `SUCCESS`, wallet credited |
| `FAILED` | Webhook after ~1 s → `FAILED`, `failureReason: "Gateway reported status FAILED"` |
| `TIMEOUT` | No webhook → `EXPIRED` after 60 min. A late `SUCCESS` webhook still credits |

**Idempotency.** Sending the same `X-Idempotency-Key` again does not create a second top-up. It returns the transaction's *current* state (here `200` + `SUCCESS` once settled). Reusing the key with a different amount returns `422`.

| Status | When |
|---|---|
| `400` | Missing/invalid `X-Idempotency-Key`, amount out of range or with >2 decimals, bad scenario, malformed JSON |
| `409` | A request with the same key is still being processed; retry shortly |
| `422` | Key already used for a different request; or replay of a `FAILED`/`EXPIRED` top-up |
| `429` | More than 5 payment requests (top-up + transfer) per minute |

---

## 5. Get a transaction

`GET /api/v1/transactions/{id}`. Poll this to follow a `PENDING` top-up or transfer.

```bash
curl -s $PAYMENT/api/v1/transactions/$TOPUP_ID -H "Authorization: Bearer $TOKEN" | jq
```

`200 OK`
```json
{
  "success": true,
  "message": "Transaction retrieved",
  "data": {
    "transactionId": "5d56d612-cc9b-4527-bbaa-2a1f2270eb46",
    "type": "TOPUP",
    "status": "SUCCESS",
    "amount": 50000.00,
    "description": "First top-up",
    "failureReason": null,
    "createdAt": "2026-10-07T06:51:22.127308"
  }
}
```

`404` if the ID doesn't exist **or belongs to another user**; the two cases look identical on purpose.

---

## 6. Transfer

`POST /api/v1/transfer`

```bash
curl -s -X POST $PAYMENT/api/v1/transfer -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H 'X-Idempotency-Key: transfer-001' \
  -d "{\"toUserId\":\"$BOB_ID\",\"amount\":10000,\"description\":\"Lunch\"}" | jq
```

`200 OK`
```json
{
  "success": true,
  "message": "Transfer successful",
  "data": {
    "transactionId": "5fee8f3b-ca5a-418c-9da4-108c029ed4ca",
    "type": "TRANSFER",
    "status": "SUCCESS",
    "amount": 10000.00,
    "description": "Lunch",
    "failureReason": null,
    "createdAt": "2026-10-07T06:51:25.240599"
  }
}
```

Insufficient balance, `422`. The failed transfer is stored, and a retry with the same key returns this same answer:
```bash
curl -s -X POST $PAYMENT/api/v1/transfer -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H 'X-Idempotency-Key: transfer-002' \
  -d "{\"toUserId\":\"$BOB_ID\",\"amount\":9000000}" | jq
```
```json
{
  "success": false,
  "message": "Transfer failed: Insufficient balance",
  "data": {
    "transactionId": "b22c2de5-a8a7-4eab-9acb-dc95e213b319",
    "type": "TRANSFER",
    "status": "FAILED",
    "amount": 9000000.00,
    "description": "Transfer",
    "failureReason": "Insufficient balance",
    "createdAt": "2026-10-07T06:51:25.302461"
  }
}
```

| Status | `data.status` | When |
|---|---|---|
| `200` | `SUCCESS` | Money moved |
| `202` | `PENDING` | wallet-service didn't answer. A background job finishes it within ~2–3 min; poll [§5](#5-get-a-transaction) |
| `422` | `FAILED` | Insufficient balance, or sender/recipient wallet not found |
| `422` | — | Daily transfer limit (default 10,000,000) exceeded, or key reused for a different request |
| `400` | — | Validation, transfer to yourself, missing/invalid idempotency key |
| `409` / `429` | — | Same as top-up |

---

## 7. Mutation history

`GET /api/v1/wallet/mutations?page=0&size=10`. Newest first; `size` is capped at 100.

```bash
curl -s "$WALLET/api/v1/wallet/mutations?page=0&size=10" -H "Authorization: Bearer $TOKEN" | jq
```

`200 OK`
```json
{
  "success": true,
  "message": "Mutations retrieved",
  "data": {
    "content": [
      {
        "id": "d19026bf-6725-4fb3-b537-965d7ca9c7d9",
        "type": "DEBIT",
        "amount": 10000.00,
        "balanceBefore": 50000.00,
        "balanceAfter": 40000.00,
        "referenceId": "5fee8f3b-ca5a-418c-9da4-108c029ed4ca",
        "description": "Transfer out: Lunch",
        "createdAt": "2026-10-07T06:51:25.254166"
      },
      {
        "id": "2fc77af7-12a4-42b6-b7cf-b11f73dcd790",
        "type": "CREDIT",
        "amount": 50000.00,
        "balanceBefore": 0.00,
        "balanceAfter": 50000.00,
        "referenceId": "5d56d612-cc9b-4527-bbaa-2a1f2270eb46",
        "description": "Top-up via payment gateway",
        "createdAt": "2026-10-07T06:51:23.645528"
      }
    ],
    "page": 0,
    "size": 10,
    "totalElements": 2,
    "totalPages": 1,
    "last": true
  }
}
```
`referenceId` is the payment `transactionId` that caused the entry. A failed transfer creates no mutation.

---

## 8. Refresh token

`POST /api/v1/auth/refresh`. Returns a new pair and revokes the old refresh token.

```bash
REFRESHED=$(curl -s -X POST $AUTH/api/v1/auth/refresh -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$REFRESH\"}")
echo "$REFRESHED" | jq
TOKEN=$(echo "$REFRESHED" | jq -r .data.accessToken)
REFRESH=$(echo "$REFRESHED" | jq -r .data.refreshToken)
```

`200 OK`, same shape as login:
```json
{
  "success": true,
  "message": "Token refreshed",
  "data": {
    "accessToken": "eyJhbGciOiJSUzI1NiJ9...",
    "refreshToken": "_X0QDLxqSg7K...",
    "userId": "5d052c70-12ba-4d0a-b1bc-eacc51b3e214",
    "accessExpiresIn": 900,
    "tokenType": "Bearer"
  }
}
```

Always store the **new** refresh token. Re-using an old one returns `401` and is treated as token theft: **every session of the user is revoked**, and the user must log in again.

---

## 9. Logout

`POST /api/v1/auth/logout`. Revokes the refresh token. The access token remains valid until it expires (max 15 min), so clients should discard it.

```bash
curl -s -X POST $AUTH/api/v1/auth/logout -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$REFRESH\"}" | jq
```

`200 OK`
```json
{ "success": true, "message": "Logged out successfully", "data": null }
```

---

## Error reference

All errors use the envelope with `"success": false` and a readable `message`.

| Status | Meaning | Example `message` |
|---|---|---|
| `400` | Invalid request | `Required request header 'X-Idempotency-Key' for method parameter type String is not present` |
| `401` | Missing/invalid/expired token, wrong credentials | `Unauthorized`, `Invalid username or password` |
| `403` | Account disabled | `Account is disabled` |
| `404` | Unknown resource | `Transaction not found` |
| `409` | Conflict / still processing | `Username already taken`, `A request with this idempotency key is still being processed. Retry shortly.` |
| `422` | Business rule | `Transfer failed: Insufficient balance`, `Daily transfer limit exceeded. Remaining: ...` |
| `429` | Throttled (see `Retry-After`) | `Too many payment requests. Max 5 per minute. Retry after 60 seconds.` |
| `503` | Dependency down, safe to retry | `Registration temporarily unavailable, please retry` |
| `500` | Unexpected; report it with the `X-Trace-Id` | `Internal server error` |

---

## Advanced: simulate a gateway webhook

For testing only. The mock gateway normally sends this itself. A webhook must be signed with `MOCK_GATEWAY_SECRET` over `gatewayRef:transactionId:status:amount`, with the amount written exactly as in the body.

```bash
set -a; source .env; set +a
TXN_ID=<topup transactionId>
GATEWAY_REF=$(docker compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -Atc \
  "select coalesce(gateway_ref,'GW-MANUAL') from payment.topup_requests where transaction_id='$TXN_ID'")
AMOUNT=10000

SIGNATURE=$(printf '%s' "$GATEWAY_REF:$TXN_ID:SUCCESS:$AMOUNT" \
  | openssl dgst -sha256 -hmac "$MOCK_GATEWAY_SECRET" -r | cut -d' ' -f1)

curl -s -X POST $PAYMENT/api/v1/webhook/topup -H 'Content-Type: application/json' \
  -H "X-Webhook-Signature: $SIGNATURE" \
  -d "{\"gatewayRef\":\"$GATEWAY_REF\",\"transactionId\":\"$TXN_ID\",\"status\":\"SUCCESS\",\"amount\":$AMOUNT}" | jq
```

| Result | Meaning |
|---|---|
| `200` | Processed, or a duplicate that was ignored (no double credit) |
| `400` | Unknown top-up, `gatewayRef`/`transactionId` mismatch, or amount differs from the stored one |
| `401` | Invalid signature |
