# Wallet — Microservices System

Sistem wallet berbasis microservices yang mendukung top-up via payment gateway, transfer antar user, manajemen saldo, idempotency, rate limiting, dan audit logging.

---

## Arsitektur

```
Client
  │
  ├──► auth-service    :8081  → Register, Login, Refresh Token, Logout
  ├──► wallet-service  :8082  → Cek Saldo, Riwayat Mutasi
  ├──► payment-service :8083  → Top-up, Transfer, Webhook
  ├──► audit-service   :8084  → Audit Log (internal)
  └──► payment-gateway :8085  → Simulasi Payment Gateway
```
## Cara Menjalankan

### Prerequisites
- Docker & Docker Compose installed
- Port 8081–8085, 5432–5435, 6379 tersedia

### Steps

```bash
# Clone repo
git clone https://github.com/lukmnh/monorepo-wallet.git
cd wallet

# Copy env file dan isi secrets
cp .env.example .env
# Edit .env — ganti semua *_PASSWORD, JWT_SECRET, INTERNAL_API_KEY, MOCK_GATEWAY_SECRET

# Build dan jalankan semua service
docker compose up --build

# Cek semua service running
docker compose ps

## Keputusan Teknis & Trade-off

### Pessimistic Locking untuk Concurrent Balance Update

Menggunakan `SELECT FOR UPDATE` pada tabel wallets.

**Alasan:** Di sistem finansial, correctness lebih penting dari throughput. Pessimistic locking menjamin tidak ada dua transaksi yang bisa memodifikasi saldo wallet yang sama secara bersamaan.

**Trade-off:** Throughput lebih rendah dibanding optimistic locking saat concurrent request tinggi. Untuk skala production dengan volume sangat tinggi, perlu pertimbangkan sharding wallet atau queue per-wallet.

---

### Deadlock Prevention pada Atomic Transfer

Dua wallet di-lock dalam urutan UUID yang konsisten (UUID lebih kecil di-lock duluan).

**Alasan:** Tanpa urutan konsisten, `transfer(A→B)` dan `transfer(B→A)` yang terjadi bersamaan bisa saling menunggu lock → deadlock.

**Trade-off:** Sedikit overhead untuk sorting UUID, tapi menghilangkan entire class of bug.

---

### Redis SET NX untuk Idempotency

Menggunakan atomic `SET key value NX EX ttl` bukan GET-then-SET.

**Alasan:** GET-then-SET adalah race condition — dua request identik bisa lolos bersamaan jika keduanya GET sebelum salah satu SET. NX (set-if-not-exists) adalah operasi atomic di Redis.

**Trade-off:** TTL harus dipilih hati-hati. Terlalu pendek: duplicate terlewat. Terlalu panjang: Redis memory boros.

---

### Audit Service Fire-and-Forget

Payment-service call audit-service menggunakan `@Async`, exception ditangkap dan di-log saja.

**Alasan:** Audit log tidak boleh mempengaruhi latency atau keberhasilan transaksi utama. Kalau audit-service down, transaksi tetap harus jalan.

**Trade-off:** Audit log bisa hilang kalau audit-service down. Solusi production: gunakan message queue (Kafka/RabbitMQ) sehingga event tidak hilang meski consumer down sementara.

---

### HMAC-SHA256 untuk Webhook Validation

Signature = `HMAC-SHA256(gatewayRef:status:amount, sharedSecret)`

**Alasan:** Memastikan webhook yang datang benar-benar dari mock-gateway kita, bukan dari pihak luar yang mencoba inject transaksi palsu.

**Trade-off:** Shared secret harus dijaga kerahasiaannya. Jika bocor, attacker bisa forge webhook. Solusi: rotate secret secara berkala.
````
## Struktur Database
auth DB:      users, refresh_tokens
wallet DB:    wallets, mutations
payment DB:   transactions, topup_requests, transfer_requests
audit DB:     audit_logs
```