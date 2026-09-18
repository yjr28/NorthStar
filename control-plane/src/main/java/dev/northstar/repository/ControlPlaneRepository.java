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
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ControlPlaneRepository {
    private final JdbcTemplate jdbc;
    public ControlPlaneRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public HostRecord saveHost(HostRecord host) {
        String sql = "INSERT INTO hosts(id, hostname, agent_version, os_name, architecture, registered_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET hostname=EXCLUDED.hostname, agent_version=EXCLUDED.agent_version, os_name=EXCLUDED.os_name, architecture=EXCLUDED.architecture, last_seen_at=EXCLUDED.last_seen_at";
        jdbc.update(sql, host.id(), host.hostname(), host.agentVersion(), host.osName(), host.architecture(), host.registeredAt(), host.lastSeenAt());
        return findHost(host.id()).orElseThrow();
    }
    public List<HostRecord> listHosts() { return jdbc.query("SELECT * FROM hosts ORDER BY hostname", this::mapHost); }
    public Optional<HostRecord> findHost(UUID id) { return jdbc.query("SELECT * FROM hosts WHERE id = ?", this::mapHost, id).stream().findFirst(); }
    public int deleteHost(UUID id) { return jdbc.update("DELETE FROM hosts WHERE id = ?", id); }
    public int touchHost(UUID id, OffsetDateTime seenAt) { return jdbc.update("UPDATE hosts SET last_seen_at = ? WHERE id = ?", seenAt, id); }

    public TelemetryRecord saveTelemetry(TelemetryRecord sample) {
        jdbc.update("INSERT INTO telemetry(id,host_id,collected_at,cpu_percent,memory_used_bytes,memory_total_bytes,load_1m,uptime_seconds,process_count) VALUES (?,?,?,?,?,?,?,?,?)", sample.id(), sample.hostId(), sample.collectedAt(), sample.cpuPercent(), sample.memoryUsedBytes(), sample.memoryTotalBytes(), sample.load1m(), sample.uptimeSeconds(), sample.processCount());
        touchHost(sample.hostId(), sample.collectedAt()); return sample;
    }
    public List<TelemetryRecord> recentTelemetry(UUID hostId, int limit, OffsetDateTime before) {
        if (before == null) return jdbc.query("SELECT * FROM telemetry WHERE host_id=? ORDER BY collected_at DESC LIMIT ?", this::mapTelemetry, hostId, limit);
        return jdbc.query("SELECT * FROM telemetry WHERE host_id=? AND collected_at<? ORDER BY collected_at DESC LIMIT ?", this::mapTelemetry, hostId, before, limit);
    }

    public CommandRecord saveCommand(CommandRecord c) {
        jdbc.update("INSERT INTO commands(id,host_id,type,payload,status,idempotency_key,created_at,acknowledged_at,lease_token,lease_expires_at,delivery_attempts) VALUES (?,?,?,?,?,?,?,?,?,?,?)", c.id(),c.hostId(),c.type(),c.payload(),c.status(),c.idempotencyKey(),c.createdAt(),c.acknowledgedAt(),c.leaseToken(),c.leaseExpiresAt(),c.deliveryAttempts()); return c;
    }
    public Optional<CommandRecord> findCommand(UUID id) { return jdbc.query("SELECT * FROM commands WHERE id=?", this::mapCommand, id).stream().findFirst(); }
    public Optional<CommandRecord> findCommandByIdempotencyKey(String key) { return jdbc.query("SELECT * FROM commands WHERE idempotency_key=?", this::mapCommand, key).stream().findFirst(); }

    @Transactional
    public List<CommandRecord> leaseCommands(UUID hostId, int limit, OffsetDateTime now, OffsetDateTime expiresAt) {
        List<UUID> ids=jdbc.query("SELECT id FROM commands WHERE host_id=? AND status<>'ACKNOWLEDGED' AND (lease_expires_at IS NULL OR lease_expires_at<=?) ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT ?", (r,n)->r.getObject(1,UUID.class), hostId, now, limit);
        for(UUID id:ids) jdbc.update("UPDATE commands SET status='LEASED',lease_token=?,lease_expires_at=?,delivery_attempts=delivery_attempts+1 WHERE id=?",UUID.randomUUID(),expiresAt,id);
        if(ids.isEmpty()) return List.of();
        return jdbc.query("SELECT * FROM commands WHERE id = ANY (?) ORDER BY created_at", this::mapCommand, ids.toArray(UUID[]::new));
    }

    public Optional<CommandRecord> acknowledge(UUID id, UUID leaseToken, OffsetDateTime at) {
        int changed=jdbc.update("UPDATE commands SET status='ACKNOWLEDGED',acknowledged_at=?,lease_expires_at=NULL WHERE id=? AND status<>'ACKNOWLEDGED' AND lease_token=?",at,id,leaseToken);
        if(changed==0) {
            var existing=findCommand(id);
            if(existing.isPresent() && "ACKNOWLEDGED".equals(existing.get().status()) && leaseToken.equals(existing.get().leaseToken())) return existing;
            return Optional.empty();
        }
        return findCommand(id);
    }

    private HostRecord mapHost(ResultSet r,int n)throws SQLException{return new HostRecord(r.getObject("id",UUID.class),r.getString("hostname"),r.getString("agent_version"),r.getString("os_name"),r.getString("architecture"),r.getObject("registered_at",OffsetDateTime.class),r.getObject("last_seen_at",OffsetDateTime.class));}
    private TelemetryRecord mapTelemetry(ResultSet r,int n)throws SQLException{return new TelemetryRecord(r.getObject("id",UUID.class),r.getObject("host_id",UUID.class),r.getObject("collected_at",OffsetDateTime.class),r.getDouble("cpu_percent"),r.getLong("memory_used_bytes"),r.getLong("memory_total_bytes"),r.getDouble("load_1m"),r.getLong("uptime_seconds"),r.getInt("process_count"));}
    private CommandRecord mapCommand(ResultSet r,int n)throws SQLException{return new CommandRecord(r.getObject("id",UUID.class),r.getObject("host_id",UUID.class),r.getString("type"),r.getString("payload"),r.getString("status"),r.getString("idempotency_key"),r.getObject("created_at",OffsetDateTime.class),r.getObject("acknowledged_at",OffsetDateTime.class),r.getObject("lease_token",UUID.class),r.getObject("lease_expires_at",OffsetDateTime.class),r.getInt("delivery_attempts"));}
}
