package com.navio.usermanagementservice.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AdminAuditEventResponse(
        UUID id, String action, String resourceType, UUID resourceId,
        UUID actorUserId, String actorDisplayName, Instant createdAt,
        Map<String, Object> before, Map<String, Object> after) {
}
