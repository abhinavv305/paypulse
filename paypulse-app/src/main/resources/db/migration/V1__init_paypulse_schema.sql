-- V1__init_paypulse_schema.sql

-- 1. Merchants Table
CREATE TABLE IF NOT EXISTS merchants (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    api_key_hash VARCHAR(64) NOT NULL,
    webhook_secret VARCHAR(255) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- 2. Idempotency Records
CREATE TABLE IF NOT EXISTS idempotency_keys (
    id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    resource_id UUID,
    status VARCHAR(32) NOT NULL,
    response_code INT,
    response_headers TEXT,
    response_body TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    locked_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_merchant_idempotency UNIQUE (merchant_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_idemp_lookup ON idempotency_keys(merchant_id, idempotency_key);

-- 3. Core Payments Table
CREATE TABLE IF NOT EXISTS payments (
    id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL,
    idempotency_key_id UUID,
    provider_id VARCHAR(32) NOT NULL,
    provider_payment_id VARCHAR(255),
    amount_minor_units BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(32) NOT NULL,
    state_sequence_number INT NOT NULL DEFAULT 10,
    capture_method VARCHAR(16) NOT NULL DEFAULT 'AUTOMATIC',
    client_token VARCHAR(255),
    customer_email VARCHAR(255),
    description VARCHAR(500),
    error_code VARCHAR(64),
    error_message TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_positive_amount CHECK (amount_minor_units > 0)
);

CREATE INDEX IF NOT EXISTS idx_payments_merchant_created ON payments(merchant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_payments_provider_ref ON payments(provider_id, provider_payment_id);

-- 4. Payment Attempts (Multi-attempt history)
CREATE TABLE IF NOT EXISTS payment_attempts (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL,
    attempt_number INT NOT NULL,
    provider_id VARCHAR(32) NOT NULL,
    provider_transaction_id VARCHAR(255),
    request_payload TEXT,
    response_payload TEXT,
    http_status INT,
    latency_ms INT,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- 5. Refunds Table
CREATE TABLE IF NOT EXISTS refunds (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL,
    provider_refund_id VARCHAR(255),
    amount_minor_units BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(32) NOT NULL,
    reason VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- 6. Webhook Ingestion Events (Append-Only Log)
CREATE TABLE IF NOT EXISTS webhook_events (
    id UUID PRIMARY KEY,
    provider_id VARCHAR(32) NOT NULL,
    provider_event_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    signature_header TEXT,
    payload TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_provider_event UNIQUE (provider_id, provider_event_id)
);

CREATE INDEX IF NOT EXISTS idx_webhook_status ON webhook_events(status, created_at);

-- 7. Transactional Outbox Events
CREATE TABLE IF NOT EXISTS outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_outbox_queue ON outbox_events(status, created_at ASC);

-- 8. Dead-Letter Events (DLQ)
CREATE TABLE IF NOT EXISTS dead_letter_events (
    id UUID PRIMARY KEY,
    source_type VARCHAR(32) NOT NULL,
    source_id UUID NOT NULL,
    payload TEXT NOT NULL,
    headers TEXT,
    error_class VARCHAR(255) NOT NULL,
    error_message TEXT,
    stack_trace TEXT,
    retry_count INT NOT NULL,
    replayed BOOLEAN NOT NULL DEFAULT FALSE,
    replayed_at TIMESTAMP WITH TIME ZONE,
    replayed_by VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- 9. Reconciliation Audit Log
CREATE TABLE IF NOT EXISTS reconciliation_audit_logs (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL,
    initial_status VARCHAR(32) NOT NULL,
    resolved_status VARCHAR(32) NOT NULL,
    provider_status VARCHAR(64) NOT NULL,
    action_taken VARCHAR(128) NOT NULL,
    discrepancy_reason TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
