package dev.northstar.repository;

import dev.northstar.model.CommandRecord;
import dev.northstar.model.HostRecord;
import dev.northstar.model.TelemetryRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ControlPlaneRepository {
    private final JdbcTemplate jdbc;
    public ControlPlaneRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public HostRecord saveHost(HostRecord host) {
        String sql =
            "INSERT INTO hosts(id, hostname, agent_version, os_name, architecture, registered_at, last_seen_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (id) DO UPDATE SET hostname=EXCLUDED.hostname, agent_version=EXCLUDED.agent_version, " +
            "os_name=EXCLUDED.os_name, architecture=EXCLUDED.architecture, last_seen_at=EXCLUDED.last_seen_at";
        jdbc.update(sql, host.id(), host.hostname(), host.agentVersion(), host.osName(), host.architecture(), host.registeredAt(), host.lastSeenAt());
        return findHost(host.id()).orElseThrow();
    }

    public List<HostRecord> listHosts() {
        return jdbc.query("SELECT * FROM hosts ORDER BY hostname", this::mapHost);
    }

    public Optional<HostRecord> findHost(UUID id) {
        return jdbc.query("SELECT * FROM hosts WHERE id = ?", this::mapHost, id).stream().findFirst();
    }

    public int deleteHost(UUID id) { return jdbc.update("DELETE FROM hosts WHERE id = ?", id); }
    public int touchHost(UUID id, OffsetDateTime seenAt) { return jdbc.update("UPDATE hosts SET last_seen_at = ? WHERE id = ?", seenAt, id); }

    public TelemetryRecord saveTelemetry(TelemetryRecord sample) {
        String sql =
            "INSERT INTO telemetry(id,host_id,collected_at,cpu_percent,memory_used_bytes,memory_total_bytes,load_1m,uptime_seconds,process_count) " +
            "VALUES (?,?,?,?,?,?,?,?,?)";
        jdbc.update(sql, sample.id(), sample.hostId(), sample.collectedAt(), sample.cpuPercent(), sample.memoryUsedBytes(), sample.memoryTotalBytes(), sample.load1m(), sample.uptimeSeconds(), sample.processCount());
        touchHost(sample.hostId(), sample.collectedAt());
        return sample;
    }

    public List<TelemetryRecord> recentTelemetry(UUID hostId, int limit, OffsetDateTime before) {
        if (before == null) {
            return jdbc.query("SELECT * FROM telemetry WHERE host_id=? ORDER BY collected_at DESC LIMIT ?", this::mapTelemetry, hostId, limit);
        }
        return jdbc.query("SELECT * FROM telemetry WHERE host_id=? AND collected_at<? ORDER BY collected_at DESC LIMIT ?", this::mapTelemetry, hostId, before, limit);
    }

    public CommandRecord saveCommand(CommandRecord command) {
        jdbc.update(
            "INSERT INTO commands(id,host_id,type,payload,status,idempotency_key,created_at,acknowledged_at) VALUES (?,?,?,?,?,?,?,?)",
            command.id(), command.hostId(), command.type(), command.payload(), command.status(), command.idempotencyKey(), command.createdAt(), command.acknowledgedAt());
        return command;
    }

    public Optional<CommandRecord> findCommand(UUID id) {
        return jdbc.query("SELECT * FROM commands WHERE id=?", this::mapCommand, id).stream().findFirst();
    }

    public Optional<CommandRecord> findCommandByIdempotencyKey(String key) {
        return jdbc.query("SELECT * FROM commands WHERE idempotency_key=?", this::mapCommand, key).stream().findFirst();
    }

    public List<CommandRecord> pendingCommands(UUID hostId) {
        return jdbc.query("SELECT * FROM commands WHERE host_id=? AND status='QUEUED' ORDER BY created_at", this::mapCommand, hostId);
    }

    public Optional<CommandRecord> acknowledge(UUID id, OffsetDateTime at) {
        int changed = jdbc.update("UPDATE commands SET status='ACKNOWLEDGED',acknowledged_at=? WHERE id=?", at, id);
        return changed == 0 ? Optional.empty() : findCommand(id);
    }

    private HostRecord mapHost(ResultSet r, int n) throws SQLException {
        return new HostRecord(r.getObject("id",UUID.class),r.getString("hostname"),r.getString("agent_version"),r.getString("os_name"),r.getString("architecture"),r.getObject("registered_at",OffsetDateTime.class),r.getObject("last_seen_at",OffsetDateTime.class));
    }

    private TelemetryRecord mapTelemetry(ResultSet r, int n) throws SQLException {
        return new TelemetryRecord(r.getObject("id",UUID.class),r.getObject("host_id",UUID.class),r.getObject("collected_at",OffsetDateTime.class),r.getDouble("cpu_percent"),r.getLong("memory_used_bytes"),r.getLong("memory_total_bytes"),r.getDouble("load_1m"),r.getLong("uptime_seconds"),r.getInt("process_count"));
    }

    private CommandRecord mapCommand(ResultSet r, int n) throws SQLException {
        return new CommandRecord(r.getObject("id",UUID.class),r.getObject("host_id",UUID.class),r.getString("type"),r.getString("payload"),r.getString("status"),r.getString("idempotency_key"),r.getObject("created_at",OffsetDateTime.class),r.getObject("acknowledged_at",OffsetDateTime.class));
    }
}
