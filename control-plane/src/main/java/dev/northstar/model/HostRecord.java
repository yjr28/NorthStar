package dev.northstar.model;
import java.time.OffsetDateTime;
import java.util.UUID;
public record HostRecord(UUID id,String hostname,String agentVersion,String osName,String architecture,OffsetDateTime registeredAt,OffsetDateTime lastSeenAt) {}
