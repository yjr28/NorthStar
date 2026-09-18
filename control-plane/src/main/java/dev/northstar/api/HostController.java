package dev.northstar.api;
import dev.northstar.model.*;
import dev.northstar.repository.ControlPlaneRepository;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class HostController {
  private static final Duration ONLINE_WINDOW=Duration.ofSeconds(90);
  private final ControlPlaneRepository repo;
  public HostController(ControlPlaneRepository repo){this.repo=repo;}

  @PostMapping("/hosts")
  public ResponseEntity<HostRecord> register(@RequestBody HostRegistrationRequest q){
    OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC); UUID id=q.id()==null?UUID.randomUUID():q.id();
    HostRecord h=repo.saveHost(new HostRecord(id,required(q.hostname(),"hostname"),value(q.agentVersion(),"unknown"),value(q.osName(),"unknown"),value(q.architecture(),"unknown"),now,now));
    return ResponseEntity.created(URI.create("/api/v1/hosts/"+id)).body(h);
  }
  @GetMapping("/hosts") public List<HostRecord> listHosts(){return repo.listHosts();}
  @GetMapping("/hosts/{id}") public ResponseEntity<HostRecord> getHost(@PathVariable UUID id){return repo.findHost(id).map(ResponseEntity::ok).orElseGet(()->ResponseEntity.notFound().build());}
  @DeleteMapping("/hosts/{id}") public ResponseEntity<Void> deleteHost(@PathVariable UUID id){return repo.deleteHost(id)==0?ResponseEntity.notFound().build():ResponseEntity.noContent().build();}
  @PostMapping("/hosts/{id}/heartbeat") public ResponseEntity<Void> heartbeat(@PathVariable UUID id){return repo.touchHost(id,OffsetDateTime.now(ZoneOffset.UTC))==0?ResponseEntity.notFound().build():ResponseEntity.noContent().build();}

  @PostMapping("/hosts/{id}/telemetry")
  public ResponseEntity<TelemetryRecord> ingest(@PathVariable UUID id,@RequestBody TelemetryRequest q){
    if(repo.findHost(id).isEmpty()) return ResponseEntity.notFound().build();
    OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC);
    TelemetryRecord t=new TelemetryRecord(UUID.randomUUID(),id,q.collectedAt()==null?now:q.collectedAt(),q.cpuPercent(),q.memoryUsedBytes(),q.memoryTotalBytes(),q.load1m(),q.uptimeSeconds(),q.processCount());
    return ResponseEntity.status(201).body(repo.saveTelemetry(t));
  }
  @GetMapping("/hosts/{id}/telemetry")
  public ResponseEntity<List<TelemetryRecord>> telemetry(@PathVariable UUID id,@RequestParam(defaultValue="50") int limit){
    if(repo.findHost(id).isEmpty()) return ResponseEntity.notFound().build();
    return ResponseEntity.ok(repo.recentTelemetry(id,Math.max(1,Math.min(limit,500))));
  }
  @GetMapping("/hosts/{id}/status")
  public ResponseEntity<Map<String,Object>> status(@PathVariable UUID id){
    return repo.findHost(id).map(h->{Duration age=Duration.between(h.lastSeenAt(),OffsetDateTime.now(ZoneOffset.UTC)); boolean online=!age.isNegative()&&age.compareTo(ONLINE_WINDOW)<=0; return ResponseEntity.ok(Map.<String,Object>of("hostId",h.id(),"online",online,"lastSeenAt",h.lastSeenAt(),"ageSeconds",Math.max(0,age.toSeconds())));}).orElseGet(()->ResponseEntity.notFound().build());
  }

  @PostMapping("/commands")
  public ResponseEntity<CommandRecord> queue(@RequestBody CommandCreateRequest q){
    if(repo.findHost(q.hostId()).isEmpty()) return ResponseEntity.notFound().build();
    CommandRecord c=new CommandRecord(UUID.randomUUID(),q.hostId(),required(q.type(),"type"),value(q.payload(),"{}"),"QUEUED",OffsetDateTime.now(ZoneOffset.UTC),null);
    repo.saveCommand(c); return ResponseEntity.created(URI.create("/api/v1/commands/"+c.id())).body(c);
  }
  @GetMapping("/commands/{id}") public ResponseEntity<CommandRecord> command(@PathVariable UUID id){return repo.findCommand(id).map(ResponseEntity::ok).orElseGet(()->ResponseEntity.notFound().build());}
  @GetMapping("/hosts/{id}/commands") public ResponseEntity<List<CommandRecord>> commands(@PathVariable UUID id){if(repo.findHost(id).isEmpty())return ResponseEntity.notFound().build();return ResponseEntity.ok(repo.pendingCommands(id));}
  @PostMapping("/commands/{id}/ack") public ResponseEntity<CommandRecord> ack(@PathVariable UUID id){return repo.acknowledge(id,OffsetDateTime.now(ZoneOffset.UTC)).map(ResponseEntity::ok).orElseGet(()->ResponseEntity.notFound().build());}

  private static String required(String s,String field){if(s==null||s.isBlank())throw new IllegalArgumentException(field+" is required");return s;}
  private static String value(String s,String fallback){return s==null||s.isBlank()?fallback:s;}
  public record HostRegistrationRequest(UUID id,String hostname,String agentVersion,String osName,String architecture){}
  public record TelemetryRequest(OffsetDateTime collectedAt,double cpuPercent,long memoryUsedBytes,long memoryTotalBytes,double load1m,long uptimeSeconds,int processCount){}
  public record CommandCreateRequest(UUID hostId,String type,String payload){}
}
