CREATE TABLE IF NOT EXISTS ledger (
    order_id    VARCHAR(64) PRIMARY KEY,
    customer_id VARCHAR(64) NOT NULL,
    total       NUMERIC(12, 2) NOT NULL,
    currency    CHAR(3) NOT NULL,
    recorded_at TIMESTAMP NOT NULL
);
