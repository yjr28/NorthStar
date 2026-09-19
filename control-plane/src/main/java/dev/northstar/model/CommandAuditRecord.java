package dev.northstar.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CommandAuditRecord(
    UUID id,
    UUID commandId,
    UUID hostId,
    String eventType,
    String actorType,
    OffsetDateTime occurredAt,
    String details
) {}
