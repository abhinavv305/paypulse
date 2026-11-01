# PayPulse: Production-Grade Modular Monolith Payment Platform

PayPulse is an enterprise payment orchestration platform built with **Java 21** and **Spring Boot 3** designed for high throughput, strict idempotency invariants, dual-layer concurrency protection, and zero dual-write vulnerabilities.

---

## 🏛 Architecture & Modular Layout

```
paypulse/
├── paypulse-parent/                  # Root POM and dependency management
├── paypulse-domain/                  # Pure Domain Entities, Value Objects (Money), States, Domain Events
├── paypulse-provider-api/            # Provider SPI Gateway, Normalized Contracts, Decline Codes, Circuit Breakers
├── paypulse-provider-stripe/         # Stripe Adapter (Charges, Webhooks with constant-time HMAC-SHA256)
├── paypulse-provider-adyen/          # Adyen Adapter (Payments, HMAC validation)
├── paypulse-provider-mock/           # Deterministic Mock Adapter for load and concurrency testing
├── paypulse-idempotency/             # Dual-layer Redis distributed lock + PostgreSQL unique constraint guard
├── paypulse-outbox/                  # Transactional Outbox Pattern with SKIP LOCKED background relay
├── paypulse-webhook/                 # Fast Ingestion (<20ms), Deduplication, Monotonic Sequence Ordering, DLQ
├── paypulse-reconciliation/          # Self-Healing Reconciliation Worker & Discrepancy Audit Log
├── paypulse-web/                     # REST API Controllers, RFC 7807 Error Handling, DTOs
└── paypulse-app/                     # Bootstrapper, Flyway DDL Migrations, Integration Test Suite
```

---

## 🛡 Core Invariants & Guarantees

1. **The Exactly-Once Fallacy vs Effective Exactly-Once Execution**:
   - Network delivery across distributed nodes is **at-least-once**.
   - Exactly-once effects are achieved via:
     $$\text{At-Least-Once Delivery} + \text{Dual-Layer Idempotency} + \text{PostgreSQL ACID Serialization} = \text{Effectively Exactly-Once Execution}$$
2. **PostgreSQL as the Invariant Guardian**:
   - Redis distributed locking (`SET lock NX PX` + atomic Lua release) acts as fast edge admission control.
   - Even if Redis experiences GC pause lease expiration (the Kleppmann dilemma), asynchronous replication failover, or total outage, PostgreSQL `UNIQUE (merchant_id, idempotency_key)` and optimistic locking (`@Version`) enforce serializability.
3. **Concurrency Stampede Defense (100 Simultaneous Requests)**:
   - When 100 identical requests hit `POST /v1/payments` at the exact same millisecond:
     - Winning thread acquires lock & claims `IN_PROGRESS` idempotency row.
     - Contending threads receive `409 Conflict` (with `Retry-After: 1s`) or `200 OK` (with `Idempotent-Replay: true`).
     - Downstream payment gateway is invoked **exactly ONCE**.
     - Database records **exactly ONE** logical payment. Zero duplicate debits.
4. **Out-of-Order Webhook Protection**:
   - Monotonic sequence numbers prevent stale webhooks from regressing payment states (e.g. `CREATED` cannot overwrite `SUCCEEDED`).
5. **Transactional Outbox**:
   - State mutations and events are committed in the same database transaction.
   - Background poller uses `FOR UPDATE SKIP LOCKED` to relay messages without lock contention.
6. **Provider Fault Isolation**:
   - Resilience4j Circuit Breakers, Bulkheads, and Time Limiters isolate external gateways. Blind retries on charge calls are strictly forbidden.

---

## 🚀 Running the Platform & Tests

### Build & Run All Tests
```bash
./mvnw clean test
```

### Run Application
```bash
./mvnw spring-boot:run -pl paypulse-app
```

---

## 📡 REST API Summary

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/v1/payments` | Create payment (Requires `Idempotency-Key` and `X-Merchant-Id`) |
| `GET` | `/v1/payments/{id}` | Get payment status and audit history |
| `POST` | `/v1/payments/{id}/capture` | Capture authorized payment |
| `POST` | `/v1/payments/{id}/cancel` | Void/Cancel authorized payment |
| `POST` | `/v1/payments/{id}/refund` | Refund captured payment |
| `POST` | `/v1/webhooks/{provider}` | Ingest provider webhook (`stripe`, `adyen`, `mock`) |
| `POST` | `/v1/admin/reconciliation/run`| Trigger self-healing reconciliation scan |
| `POST` | `/v1/admin/dlq/{id}/replay` | Replay dead-lettered event |
