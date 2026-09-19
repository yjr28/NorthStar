package dev.northstar.api;

import dev.northstar.model.CommandAuditRecord;
import dev.northstar.model.CommandRecord;
import dev.northstar.model.HostRecord;
import dev.northstar.model.TelemetryRecord;
import dev.northstar.repository.ControlPlaneRepository;
import dev.northstar.security.AgentCredentialService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class HostController {
  private static final Duration ONLINE_WINDOW=Duration.ofSeconds(90);
  private static final Duration COMMAND_LEASE=Duration.ofSeconds(30);
  private final ControlPlaneRepository repo;
  private final AgentCredentialService credentials;
  public HostController(ControlPlaneRepository repo,AgentCredentialService credentials){this.repo=repo;this.credentials=credentials;}

  @PostMapping("/hosts") public ResponseEntity<HostRecord> register(@Valid @RequestBody HostRegistrationRequest q){OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC);UUID id=q.id()==null?UUID.randomUUID():q.id();HostRecord h=repo.saveHost(new HostRecord(id,q.hostname(),value(q.agentVersion(),"unknown"),value(q.osName(),"unknown"),value(q.architecture(),"unknown"),now,now));credentials.provision(id,q.agentToken());return ResponseEntity.created(URI.create("/api/v1/hosts/"+id)).body(h);}
  @GetMapping("/hosts") public List<HostRecord> listHosts(){return repo.listHosts();}
  @GetMapping("/hosts/{id}") public ResponseEntity<HostRecord> getHost(@PathVariable UUID id){return repo.findHost(id).map(ResponseEntity::ok).orElseGet(()->ResponseEntity.notFound().build());}
  @DeleteMapping("/hosts/{id}") public ResponseEntity<Void> deleteHost(@PathVariable UUID id){return repo.deleteHost(id)==0?ResponseEntity.notFound().build():ResponseEntity.noContent().build();}
  @PostMapping("/hosts/{id}/heartbeat") public ResponseEntity<Void> heartbeat(@PathVariable UUID id,@RequestHeader(name="X-NorthStar-Agent-Token",required=false)String token){if(!credentials.authenticate(id,token))return ResponseEntity.status(401).build();return repo.touchHost(id,OffsetDateTime.now(ZoneOffset.UTC))==0?ResponseEntity.notFound().build():ResponseEntity.noContent().build();}
  @PostMapping("/hosts/{id}/credentials/rotate") public ResponseEntity<Void> rotate(@PathVariable UUID id,@RequestHeader(name="X-NorthStar-Agent-Token",required=false)String token,@Valid @RequestBody CredentialRotationRequest q){return credentials.rotate(id,token,q.newToken())?ResponseEntity.noContent().build():ResponseEntity.status(401).build();}

  @PostMapping("/hosts/{id}/telemetry") public ResponseEntity<TelemetryRecord> ingest(@PathVariable UUID id,@RequestHeader(name="X-NorthStar-Agent-Token",required=false)String token,@Valid @RequestBody TelemetryRequest q){if(!credentials.authenticate(id,token))return ResponseEntity.status(401).build();if(repo.findHost(id).isEmpty())return ResponseEntity.notFound().build();OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC);TelemetryRecord t=new TelemetryRecord(UUID.randomUUID(),id,q.collectedAt()==null?now:q.collectedAt(),q.cpuPercent(),q.memoryUsedBytes(),q.memoryTotalBytes(),q.load1m(),q.uptimeSeconds(),q.processCount());return ResponseEntity.status(201).body(repo.saveTelemetry(t));}
  @GetMapping("/hosts/{id}/telemetry") public ResponseEntity<List<TelemetryRecord>> telemetry(@PathVariable UUID id,@RequestParam(defaultValue="50") int limit,@RequestParam(required=false) OffsetDateTime before){if(repo.findHost(id).isEmpty())return ResponseEntity.notFound().build();return ResponseEntity.ok(repo.recentTelemetry(id,Math.max(1,Math.min(limit,500)),before));}
  @GetMapping("/hosts/{id}/status") public ResponseEntity<Map<String,Object>> status(@PathVariable UUID id){return repo.findHost(id).map(h->{Duration age=Duration.between(h.lastSeenAt(),OffsetDateTime.now(ZoneOffset.UTC));boolean online=!age.isNegative()&&age.compareTo(ONLINE_WINDOW)<=0;return ResponseEntity.ok(Map.<String,Object>of("hostId",h.id(),"online",online,"lastSeenAt",h.lastSeenAt(),"ageSeconds",Math.max(0,age.toSeconds())));}).orElseGet(()->ResponseEntity.notFound().build());}

  @PostMapping("/commands") public ResponseEntity<CommandRecord> queue(@RequestHeader(name="Idempotency-Key",required=false)String key,@Valid @RequestBody CommandCreateRequest q){if(repo.findHost(q.hostId()).isEmpty())return ResponseEntity.notFound().build();if(key!=null&&!key.isBlank()){var existing=repo.findCommandByIdempotencyKey(key);if(existing.isPresent())return ResponseEntity.ok(existing.get());}CommandRecord c=new CommandRecord(UUID.randomUUID(),q.hostId(),q.type(),value(q.payload(),"{}"),"QUEUED",blankToNull(key),OffsetDateTime.now(ZoneOffset.UTC),null,null,null,0);repo.saveCommand(c);return ResponseEntity.created(URI.create("/api/v1/commands/"+c.id())).body(c);}
  @GetMapping("/commands/{id}") public ResponseEntity<CommandRecord> command(@PathVariable UUID id){return repo.findCommand(id).map(ResponseEntity::ok).orElseGet(()->ResponseEntity.notFound().build());}
  @GetMapping("/commands/{id}/audit") public ResponseEntity<List<CommandAuditRecord>> commandAudit(@PathVariable UUID id){if(repo.findCommand(id).isEmpty())return ResponseEntity.notFound().build();return ResponseEntity.ok(repo.commandAudit(id));}
  @PostMapping("/hosts/{id}/commands/lease") public ResponseEntity<List<CommandRecord>> lease(@PathVariable UUID id,@RequestHeader(name="X-NorthStar-Agent-Token",required=false)String token,@RequestParam(defaultValue="10")int limit){if(!credentials.authenticate(id,token))return ResponseEntity.status(401).build();if(repo.findHost(id).isEmpty())return ResponseEntity.notFound().build();OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC);return ResponseEntity.ok(repo.leaseCommands(id,Math.max(1,Math.min(limit,100)),now,now.plus(COMMAND_LEASE)));}
  @PostMapping("/commands/{id}/ack") public ResponseEntity<CommandRecord> ack(@PathVariable UUID id,@RequestHeader(name="X-NorthStar-Agent-Token",required=false)String token,@Valid @RequestBody CommandAckRequest q){var command=repo.findCommand(id);if(command.isEmpty())return ResponseEntity.notFound().build();if(!credentials.authenticate(command.get().hostId(),token))return ResponseEntity.status(401).build();return repo.acknowledge(id,q.leaseToken(),OffsetDateTime.now(ZoneOffset.UTC)).map(ResponseEntity::ok).orElseGet(()->ResponseEntity.status(409).build());}

  private static String value(String s,String fallback){return s==null||s.isBlank()?fallback:s;} private static String blankToNull(String s){return s==null||s.isBlank()?null:s;}
  public record HostRegistrationRequest(UUID id,@NotBlank String hostname,String agentVersion,String osName,String architecture,@NotBlank @Size(min=24,max=256) String agentToken){}
  public record CredentialRotationRequest(@NotBlank @Size(min=24,max=256) String newToken){}
  public record TelemetryRequest(OffsetDateTime collectedAt,@Min(0) @Max(100) double cpuPercent,@Min(0) long memoryUsedBytes,@Min(0) long memoryTotalBytes,@Min(0) double load1m,@Min(0) long uptimeSeconds,@Min(0) int processCount){}
  public record CommandCreateRequest(@NotNull UUID hostId,@NotBlank String type,String payload){}
  public record CommandAckRequest(@NotNull UUID leaseToken){}
}
