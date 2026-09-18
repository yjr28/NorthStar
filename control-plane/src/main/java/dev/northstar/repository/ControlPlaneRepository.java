package dev.northstar.repository;
import dev.northstar.model.*;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ControlPlaneRepository {
  private final JdbcTemplate jdbc;
  public ControlPlaneRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}

  public HostRecord saveHost(HostRecord h){
    jdbc.update("""INSERT INTO hosts(id,hostname,agent_version,os_name,architecture,registered_at,last_seen_at)
      VALUES (?,?,?,?,?,?,?) ON CONFLICT (id) DO UPDATE SET hostname=EXCLUDED.hostname,agent_version=EXCLUDED.agent_version,
      os_name=EXCLUDED.os_name,architecture=EXCLUDED.architecture,last_seen_at=EXCLUDED.last_seen_at""",
      h.id(),h.hostname(),h.agentVersion(),h.osName(),h.architecture(),h.registeredAt(),h.lastSeenAt());
    return findHost(h.id()).orElseThrow();
  }
  public List<HostRecord> listHosts(){return jdbc.query("SELECT * FROM hosts ORDER BY hostname",this::mapHost);}
  public Optional<HostRecord> findHost(UUID id){return jdbc.query("SELECT * FROM hosts WHERE id=?",this::mapHost,id).stream().findFirst();}
  public int deleteHost(UUID id){return jdbc.update("DELETE FROM hosts WHERE id=?",id);}
  public int touchHost(UUID id,OffsetDateTime at){return jdbc.update("UPDATE hosts SET last_seen_at=? WHERE id=?",at,id);}

  public TelemetryRecord saveTelemetry(TelemetryRecord s){
    jdbc.update("""INSERT INTO telemetry(id,host_id,collected_at,cpu_percent,memory_used_bytes,memory_total_bytes,load_1m,uptime_seconds,process_count)
      VALUES (?,?,?,?,?,?,?,?,?)""",s.id(),s.hostId(),s.collectedAt(),s.cpuPercent(),s.memoryUsedBytes(),s.memoryTotalBytes(),s.load1m(),s.uptimeSeconds(),s.processCount());
    touchHost(s.hostId(),s.collectedAt()); return s;
  }
  public List<TelemetryRecord> recentTelemetry(UUID hostId,int limit){
    return jdbc.query("SELECT * FROM telemetry WHERE host_id=? ORDER BY collected_at DESC LIMIT ?",this::mapTelemetry,hostId,limit);
  }

  public CommandRecord saveCommand(CommandRecord c){
    jdbc.update("INSERT INTO commands(id,host_id,type,payload,status,created_at,acknowledged_at) VALUES (?,?,?,?,?,?,?)",
      c.id(),c.hostId(),c.type(),c.payload(),c.status(),c.createdAt(),c.acknowledgedAt()); return c;
  }
  public Optional<CommandRecord> findCommand(UUID id){return jdbc.query("SELECT * FROM commands WHERE id=?",this::mapCommand,id).stream().findFirst();}
  public List<CommandRecord> pendingCommands(UUID hostId){return jdbc.query("SELECT * FROM commands WHERE host_id=? AND status='QUEUED' ORDER BY created_at",this::mapCommand,hostId);}
  public Optional<CommandRecord> acknowledge(UUID id,OffsetDateTime at){
    int changed=jdbc.update("UPDATE commands SET status='ACKNOWLEDGED',acknowledged_at=? WHERE id=?",at,id);
    return changed==0?Optional.empty():findCommand(id);
  }

  private HostRecord mapHost(ResultSet r,int n)throws SQLException{return new HostRecord(r.getObject("id",UUID.class),r.getString("hostname"),r.getString("agent_version"),r.getString("os_name"),r.getString("architecture"),r.getObject("registered_at",OffsetDateTime.class),r.getObject("last_seen_at",OffsetDateTime.class));}
  private TelemetryRecord mapTelemetry(ResultSet r,int n)throws SQLException{return new TelemetryRecord(r.getObject("id",UUID.class),r.getObject("host_id",UUID.class),r.getObject("collected_at",OffsetDateTime.class),r.getDouble("cpu_percent"),r.getLong("memory_used_bytes"),r.getLong("memory_total_bytes"),r.getDouble("load_1m"),r.getLong("uptime_seconds"),r.getInt("process_count"));}
  private CommandRecord mapCommand(ResultSet r,int n)throws SQLException{return new CommandRecord(r.getObject("id",UUID.class),r.getObject("host_id",UUID.class),r.getString("type"),r.getString("payload"),r.getString("status"),r.getObject("created_at",OffsetDateTime.class),r.getObject("acknowledged_at",OffsetDateTime.class));}
}
