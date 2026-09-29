package com.navio.usermanagementservice.service;

import com.navio.usermanagementservice.dto.AdminAuditEventResponse;
import com.navio.usermanagementservice.model.AuditLog;
import com.navio.usermanagementservice.model.User;
import com.navio.usermanagementservice.repository.AuditLogRepository;
import com.navio.usermanagementservice.repository.UserRepository;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminAuditService {
    private static final Set<String> CATALOG_FIELDS = Set.of(
            "make", "model", "trim", "year", "market", "batteryCapacityKwh",
            "batteryCapacityBasis", "rangeKm", "rangeStandard", "maxAcKw",
            "maxDcKw", "connectorTypes", "sourceUrl", "verifiedAt");
    private final AuditLogRepository auditLogs;
    private final UserRepository users;

    public Page<AdminAuditEventResponse> search(AuthenticatedUser caller, String action,
            String resourceType, UUID actorId, Instant from, Instant to, Pageable pageable) {
        if (caller == null || !caller.isAdmin()) throw new SecurityException("Administrator access required");
        if (from != null && to != null && from.isAfter(to))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The start date must be before the end date");
        Specification<AuditLog> filter = (root, query, cb) -> cb.conjunction();
        if (action != null && !action.isBlank()) filter = filter.and((root, query, cb) -> cb.equal(root.get("action"), action));
        if (resourceType != null && !resourceType.isBlank()) filter = filter.and((root, query, cb) -> cb.equal(root.get("resourceType"), resourceType));
        if (actorId != null) filter = filter.and((root, query, cb) -> cb.equal(root.get("actorUserId"), actorId));
        if (from != null) filter = filter.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from));
        if (to != null) filter = filter.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), to));
        Page<AuditLog> page = auditLogs.findAll(filter, pageable);
        Set<UUID> actorIds = page.stream().map(AuditLog::getActorUserId)
                .filter(id -> id != null).collect(Collectors.toSet());
        Map<UUID, String> names = users.findAllById(actorIds).stream()
                .collect(Collectors.toMap(User::getId, User::getDisplayName));
        return page.map(entry -> new AdminAuditEventResponse(entry.getId(), entry.getAction(),
                entry.getResourceType(), entry.getResourceId(), entry.getActorUserId(),
                names.get(entry.getActorUserId()), entry.getCreatedAt(),
                safeCatalogState(entry.getResourceType(), entry.getBefore()),
                safeCatalogState(entry.getResourceType(), entry.getAfter())));
    }

    // Audit storage includes profile data and request context. Only public vehicle specs
    // are permitted through the global activity API; all other events show metadata only.
    private Map<String, Object> safeCatalogState(String resourceType, Map<String, Object> state) {
        if (!"VEHICLE_MODEL".equals(resourceType) || state == null) return Map.of();
        Map<String, Object> safe = new HashMap<>();
        Object status = state.get("status");
        if (status instanceof String) safe.put("status", status);
        if (state.get("specification") instanceof Map<?, ?> specs) {
            for (String field : CATALOG_FIELDS) {
                Object value = specs.get(field);
                if (value instanceof String || value instanceof Number || value instanceof Boolean
                        || value instanceof java.util.List<?>) safe.put(field, value);
            }
        }
        return safe;
    }
}
