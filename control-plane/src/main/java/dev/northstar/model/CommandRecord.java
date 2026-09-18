package dev.northstar.model;
import java.time.OffsetDateTime;
import java.util.UUID;
public record CommandRecord(UUID id,UUID hostId,String type,String payload,String status,OffsetDateTime createdAt,OffsetDateTime acknowledgedAt) {}
