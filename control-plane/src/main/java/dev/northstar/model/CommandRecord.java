package dev.northstar.model;
import java.time.OffsetDateTime;
import java.util.UUID;
public record CommandRecord(UUID id,UUID hostId,String type,String payload,String status,String idempotencyKey,OffsetDateTime createdAt,OffsetDateTime acknowledgedAt) {}
