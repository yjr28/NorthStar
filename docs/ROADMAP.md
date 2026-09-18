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
- [ ] PostgreSQL-backed integration tests
- [ ] Request validation + structured error envelope
- [ ] Idempotency keys
- [ ] Cursor pagination
- [ ] Command leasing / retry-safe acknowledgements
- [ ] Agent exponential backoff + local spool

## v0.3 — Security and observability
- [ ] Per-host credentials and rotation
- [ ] Signed agent requests
- [ ] Operator RBAC
- [ ] OpenTelemetry traces
- [ ] Prometheus metrics + Grafana
- [ ] Command audit log

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
