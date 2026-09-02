package com.navio.usermanagementservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.navio.usermanagementservice.security.NavioRole;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Payloads for the moderation and role-administration endpoints.
 */
public final class ModerationDtos {

    private ModerationDtos() {
    }

    /**
     * Suspension or reactivation input.
     *
     * <p>A reason is mandatory. Every suspension lands in the append-only audit
     * log, and an entry that records who acted but not why is close to useless
     * when the decision is challenged later.
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ModerationRequest(

            @NotBlank(message = "reason is required")
            @Size(min = 3, max = 2000, message = "reason must be 3-2000 characters")
            String reason,

            /** Null means an indefinite suspension. */
            @Future(message = "expiresAt must be in the future")
            Instant expiresAt
    ) {
    }

    /** Result of a moderation action. */
    public record ModerationResponse(
            UUID userId,
            String status,
            String reason,
            Instant expiresAt,
            Instant updatedAt
    ) {
    }

    /**
     * Role grant input.
     *
     * <p>The role is a typed enum, not a free string, so only the three global
     * roles can ever be requested — a caller cannot ask for
     * {@code realm-admin} or any other Keycloak role through this path.
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record RoleAssignmentRequest(

            @NotNull(message = "role is required")
            NavioRole role,

            @NotBlank(message = "reason is required")
            @Size(min = 3, max = 2000, message = "reason must be 3-2000 characters")
            String reason
    ) {
    }

    /** Role state after a grant or revoke. */
    public record RoleAssignmentResponse(
            UUID userId,
            List<NavioRole> roles,
            Instant updatedAt
    ) {
    }

    /** One row in the admin user search. */
    public record AdminUserSummaryResponse(
            UUID id,
            String displayName,
            String email,
            String status,
            List<NavioRole> roles,
            Instant createdAt,
            Instant suspendedUntil
    ) {
    }
}
