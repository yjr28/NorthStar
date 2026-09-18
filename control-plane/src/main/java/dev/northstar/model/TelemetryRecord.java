package dev.northstar.model;
import java.time.OffsetDateTime;
import java.util.UUID;
public record TelemetryRecord(UUID id,UUID hostId,OffsetDateTime collectedAt,double cpuPercent,long memoryUsedBytes,long memoryTotalBytes,double load1m,long uptimeSeconds,int processCount) {}
