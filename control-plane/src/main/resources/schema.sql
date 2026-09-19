CREATE TABLE IF NOT EXISTS hosts (
 id UUID PRIMARY KEY, hostname VARCHAR(255) NOT NULL, agent_version VARCHAR(64) NOT NULL,
 os_name VARCHAR(128) NOT NULL, architecture VARCHAR(128) NOT NULL,
 registered_at TIMESTAMPTZ NOT NULL, last_seen_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE IF NOT EXISTS host_credentials (
 host_id UUID PRIMARY KEY REFERENCES hosts(id) ON DELETE CASCADE,
 token_hash VARCHAR(64) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL,
 rotated_at TIMESTAMPTZ
);
CREATE TABLE IF NOT EXISTS telemetry (
 id UUID PRIMARY KEY, host_id UUID NOT NULL REFERENCES hosts(id) ON DELETE CASCADE,
 collected_at TIMESTAMPTZ NOT NULL, cpu_percent DOUBLE PRECISION NOT NULL,
 memory_used_bytes BIGINT NOT NULL, memory_total_bytes BIGINT NOT NULL,
 load_1m DOUBLE PRECISION NOT NULL, uptime_seconds BIGINT NOT NULL, process_count INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_telemetry_host_collected ON telemetry(host_id,collected_at DESC);
CREATE TABLE IF NOT EXISTS commands (
 id UUID PRIMARY KEY, host_id UUID NOT NULL REFERENCES hosts(id) ON DELETE CASCADE,
 type VARCHAR(128) NOT NULL, payload TEXT NOT NULL, status VARCHAR(32) NOT NULL,
 idempotency_key VARCHAR(128),
 created_at TIMESTAMPTZ NOT NULL, acknowledged_at TIMESTAMPTZ,
 lease_token UUID, lease_expires_at TIMESTAMPTZ, delivery_attempts INTEGER NOT NULL DEFAULT 0
);
ALTER TABLE commands ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(128);
ALTER TABLE commands ADD COLUMN IF NOT EXISTS lease_token UUID;
ALTER TABLE commands ADD COLUMN IF NOT EXISTS lease_expires_at TIMESTAMPTZ;
ALTER TABLE commands ADD COLUMN IF NOT EXISTS delivery_attempts INTEGER NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX IF NOT EXISTS idx_commands_idempotency_key
  ON commands(idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_commands_host_status ON commands(host_id,status,created_at);
CREATE INDEX IF NOT EXISTS idx_commands_host_lease ON commands(host_id,lease_expires_at,created_at)
  WHERE status <> 'ACKNOWLEDGED';
