# Roadmap

## v0.1 — End-to-end foundation
- [x] C Linux telemetry collector
- [x] C++ host agent
- [x] Java REST control plane
- [x] PostgreSQL persistence
- [x] 12 management endpoints
- [x] OpenAPI/Swagger
- [x] Docker Compose
- [x] GitHub Actions + Jenkins build
- [x] 100-host fleet simulator

## v0.2 — Reliability and API correctness
- [x] PostgreSQL-backed integration test suite
- [x] Request validation + structured error envelope
- [x] Idempotency keys for command creation
- [x] Cursor-style telemetry pagination via `before`
- [x] Command leasing / retry-safe acknowledgements
- [x] Agent exponential backoff + local telemetry spool

Command delivery uses short, server-issued leases. Pollers atomically claim only commands whose lease is absent or expired; every delivery gets a unique lease token and increments `deliveryAttempts`. Acknowledgement requires the matching token, so stale workers cannot acknowledge a newer delivery, while repeating an acknowledgement with the same token is safe.

The agent persists failed telemetry as newline-delimited JSON at `NORTHSTAR_SPOOL_PATH` (default `/tmp/northstar-telemetry.spool`), drains older samples before sending new telemetry, and applies jittered exponential retry backoff. `NORTHSTAR_RETRY_BASE_MS` and `NORTHSTAR_RETRY_MAX_MS` tune retry behavior.

## v0.3 — Security and observability
- [x] Per-host credentials and rotation
- [ ] Signed agent requests
- [x] Operator RBAC (admin/read-only API-key roles)
- [ ] OpenTelemetry traces
- [x] Prometheus metrics endpoint
- [x] NorthStar lifecycle counters
- [ ] Grafana dashboards
- [x] Command audit log

Each host is provisioned with an explicit agent token. Only a SHA-256 digest is persisted; comparisons are constant-time. Heartbeat, telemetry ingestion, command leasing, acknowledgement, and credential rotation require the host token. Rotation immediately invalidates the prior credential. Request signing/replay protection remains separate work.

Operator APIs fail closed behind `X-NorthStar-Operator-Key`. `NORTHSTAR_OPERATOR_API_KEY` grants admin access; optional `NORTHSTAR_OPERATOR_READ_API_KEY` grants GET/HEAD access only and receives 403 on mutation attempts. Invalid or missing credentials receive 401.

Command lifecycle transitions are durably recorded in PostgreSQL. Queue, lease, and acknowledgement events capture the command, host, actor class, timestamp, and non-secret event details. Audit writes participate in the same database transaction as command state changes, idempotent acknowledgement replay does not create duplicate audit entries, and read-only operators can inspect the ordered history at `GET /api/v1/commands/{id}/audit`.

The control plane exposes Spring Boot health probes at `/actuator/health` and Prometheus-format metrics at `/actuator/prometheus`. Metrics carry a stable `application=northstar-control-plane` tag. NorthStar-specific counters cover accepted telemetry, newly queued commands, lease deliveries, successful acknowledgements, acknowledgement conflicts, and rejected agent authentication attempts; JVM, HTTP, process, and datasource instrumentation remains available from Micrometer. Grafana provisioning remains separate work.

## v0.4 — Reproducible scale benchmark
- [ ] 100/250/500-host scenarios
- [ ] p50/p95/p99 latency capture
- [ ] sustained throughput capture
- [ ] PostgreSQL query profiling
- [ ] publish raw benchmark artifacts and methodology

## v0.5 — Hybrid-cloud deployment
- [ ] KVM/libvirt deployment scripts
- [ ] AWS infrastructure-as-code
- [ ] EC2 + RDS deployment
- [ ] Elastic Beanstalk path
- [ ] health checks, rollback, and runbooks
