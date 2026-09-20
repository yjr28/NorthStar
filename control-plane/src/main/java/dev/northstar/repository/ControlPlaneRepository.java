package dev.northstar.repository;

import dev.northstar.model.CommandAuditRecord;
import dev.northstar.model.CommandRecord;
import dev.northstar.model.HostRecord;
import dev.northstar.model.TelemetryRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
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
    public void createCredential(UUID hostId,String tokenHash,OffsetDateTime at){jdbc.update("INSERT INTO host_credentials(host_id,token_hash,created_at) VALUES (?,?,?) ON CONFLICT (host_id) DO NOTHING",hostId,tokenHash,at);}
    public Optional<String> credentialHash(UUID hostId){return jdbc.query("SELECT token_hash FROM host_credentials WHERE host_id=?",(r,n)->r.getString(1),hostId).stream().findFirst();}
    public int rotateCredential(UUID hostId,String tokenHash,OffsetDateTime at){return jdbc.update("UPDATE host_credentials SET token_hash=?,rotated_at=? WHERE host_id=?",tokenHash,at,hostId);}

    public TelemetryRecord saveTelemetry(TelemetryRecord sample) {
        jdbc.update("INSERT INTO telemetry(id,host_id,collected_at,cpu_percent,memory_used_bytes,memory_total_bytes,load_1m,uptime_seconds,process_count) VALUES (?,?,?,?,?,?,?,?,?)", sample.id(), sample.hostId(), sample.collectedAt(), sample.cpuPercent(), sample.memoryUsedBytes(), sample.memoryTotalBytes(), sample.load1m(), sample.uptimeSeconds(), sample.processCount());
        touchHost(sample.hostId(), sample.collectedAt()); return sample;
    }
    public List<TelemetryRecord> recentTelemetry(UUID hostId, int limit, OffsetDateTime before) {
        if (before == null) return jdbc.query("SELECT * FROM telemetry WHERE host_id=? ORDER BY collected_at DESC LIMIT ?", this::mapTelemetry, hostId, limit);
        return jdbc.query("SELECT * FROM telemetry WHERE host_id=? AND collected_at<? ORDER BY collected_at DESC LIMIT ?", this::mapTelemetry, hostId, before, limit);
    }

    @Transactional
    public CommandRecord saveCommand(CommandRecord c) {
        jdbc.update("INSERT INTO commands(id,host_id,type,payload,status,idempotency_key,created_at,acknowledged_at,lease_token,lease_expires_at,delivery_attempts) VALUES (?,?,?,?,?,?,?,?,?,?,?)", c.id(),c.hostId(),c.type(),c.payload(),c.status(),c.idempotencyKey(),c.createdAt(),c.acknowledgedAt(),c.leaseToken(),c.leaseExpiresAt(),c.deliveryAttempts());
        appendAudit(c.id(),c.hostId(),"QUEUED","OPERATOR_ADMIN",c.createdAt(),"command accepted for delivery");
        return c;
    }

    @Transactional
    public IdempotentCommandResult saveCommandIdempotent(CommandRecord c) {
        if (c.idempotencyKey() == null) return new IdempotentCommandResult(saveCommand(c), true);
        int inserted=jdbc.update("INSERT INTO commands(id,host_id,type,payload,status,idempotency_key,created_at,acknowledged_at,lease_token,lease_expires_at,delivery_attempts) VALUES (?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING", c.id(),c.hostId(),c.type(),c.payload(),c.status(),c.idempotencyKey(),c.createdAt(),c.acknowledgedAt(),c.leaseToken(),c.leaseExpiresAt(),c.deliveryAttempts());
        if(inserted==1){appendAudit(c.id(),c.hostId(),"QUEUED","OPERATOR_ADMIN",c.createdAt(),"command accepted for delivery");return new IdempotentCommandResult(c,true);}
        CommandRecord existing=findCommandByIdempotencyKey(c.idempotencyKey()).orElseThrow(() -> new IllegalStateException("idempotency conflict without persisted command"));
        return new IdempotentCommandResult(existing,false);
    }
    public record IdempotentCommandResult(CommandRecord command, boolean created) {}
    public Optional<CommandRecord> findCommand(UUID id) { return jdbc.query("SELECT * FROM commands WHERE id=?", this::mapCommand, id).stream().findFirst(); }
    public Optional<CommandRecord> findCommandByIdempotencyKey(String key) { return jdbc.query("SELECT * FROM commands WHERE idempotency_key=?", this::mapCommand, key).stream().findFirst(); }
    public List<CommandAuditRecord> commandAudit(UUID commandId) { return jdbc.query("SELECT * FROM command_audit WHERE command_id=? ORDER BY occurred_at,id", this::mapCommandAudit, commandId); }

    @Transactional
    public List<CommandRecord> leaseCommands(UUID hostId, int limit, OffsetDateTime now, OffsetDateTime expiresAt) {
        List<UUID> ids=jdbc.query("SELECT id FROM commands WHERE host_id=? AND status<>'ACKNOWLEDGED' AND (lease_expires_at IS NULL OR lease_expires_at<=?) ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT ?", (r,n)->r.getObject(1,UUID.class), hostId, now, limit);
        for(UUID id:ids) {
            UUID leaseToken=UUID.randomUUID();
            jdbc.update("UPDATE commands SET status='LEASED',lease_token=?,lease_expires_at=?,delivery_attempts=delivery_attempts+1 WHERE id=?",leaseToken,expiresAt,id);
            appendAudit(id,hostId,"LEASED","AGENT",now,"lease expires at "+expiresAt);
        }
        if(ids.isEmpty()) return List.of();
        String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
        return jdbc.query("SELECT * FROM commands WHERE id IN (" + placeholders + ") ORDER BY created_at", this::mapCommand, ids.toArray());
    }

    @Transactional
    public Optional<CommandRecord> acknowledge(UUID id, UUID leaseToken, OffsetDateTime at) {
        int changed=jdbc.update("UPDATE commands SET status='ACKNOWLEDGED',acknowledged_at=?,lease_expires_at=NULL WHERE id=? AND status<>'ACKNOWLEDGED' AND lease_token=?",at,id,leaseToken);
        if(changed==0) {
            var existing=findCommand(id);
            if(existing.isPresent() && "ACKNOWLEDGED".equals(existing.get().status()) && leaseToken.equals(existing.get().leaseToken())) return existing;
            return Optional.empty();
        }
        var acknowledged=findCommand(id);
        acknowledged.ifPresent(c->appendAudit(c.id(),c.hostId(),"ACKNOWLEDGED","AGENT",at,"matching lease token acknowledged"));
        return acknowledged;
    }

    private void appendAudit(UUID commandId,UUID hostId,String eventType,String actorType,OffsetDateTime at,String details){jdbc.update("INSERT INTO command_audit(id,command_id,host_id,event_type,actor_type,occurred_at,details) VALUES (?,?,?,?,?,?,?)",UUID.randomUUID(),commandId,hostId,eventType,actorType,at,details);}
    private HostRecord mapHost(ResultSet r,int n)throws SQLException{return new HostRecord(r.getObject("id",UUID.class),r.getString("hostname"),r.getString("agent_version"),r.getString("os_name"),r.getString("architecture"),r.getObject("registered_at",OffsetDateTime.class),r.getObject("last_seen_at",OffsetDateTime.class));}
    private TelemetryRecord mapTelemetry(ResultSet r,int n)throws SQLException{return new TelemetryRecord(r.getObject("id",UUID.class),r.getObject("host_id",UUID.class),r.getObject("collected_at",OffsetDateTime.class),r.getDouble("cpu_percent"),r.getLong("memory_used_bytes"),r.getLong("memory_total_bytes"),r.getDouble("load_1m"),r.getLong("uptime_seconds"),r.getInt("process_count"));}
    private CommandRecord mapCommand(ResultSet r,int n)throws SQLException{return new CommandRecord(r.getObject("id",UUID.class),r.getObject("host_id",UUID.class),r.getString("type"),r.getString("payload"),r.getString("status"),r.getString("idempotency_key"),r.getObject("created_at",OffsetDateTime.class),r.getObject("acknowledged_at",OffsetDateTime.class),r.getObject("lease_token",UUID.class),r.getObject("lease_expires_at",OffsetDateTime.class),r.getInt("delivery_attempts"));}
    private CommandAuditRecord mapCommandAudit(ResultSet r,int n)throws SQLException{return new CommandAuditRecord(r.getObject("id",UUID.class),r.getObject("command_id",UUID.class),r.getObject("host_id",UUID.class),r.getString("event_type"),r.getString("actor_type"),r.getObject("occurred_at",OffsetDateTime.class),r.getString("details"));}
}
