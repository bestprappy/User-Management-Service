package com.navio.usermanagementservice.service;

import com.navio.usermanagementservice.dto.ModerationDtos.AdminUserSummaryResponse;
import com.navio.usermanagementservice.model.User;
import com.navio.usermanagementservice.model.UserStatus;
import com.navio.usermanagementservice.repository.UserBanRepository;
import com.navio.usermanagementservice.repository.UserRepository;
import com.navio.usermanagementservice.repository.UserRoleRepository;
import com.navio.usermanagementservice.security.NavioRole;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read-side support for the moderation console.
 *
 * <p>Role data here comes from the {@code iam.user_roles} snapshot rather than
 * Keycloak. A listing of 50 users would otherwise mean 50 Admin API round trips
 * per page. The snapshot is adequate for a list view; anything that acts on a
 * role re-reads it from Keycloak first — see {@link RoleManagementService}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminUserService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final UserBanRepository userBanRepository;

    /**
     * Searches users by email or display name.
     *
     * @param term   free text; may be null to list everyone.
     * @param status optional status filter.
     */
    public Page<AdminUserSummaryResponse> search(String term, UserStatus status, Pageable pageable) {
        String normalizedTerm = (term == null || term.isBlank()) ? null : term.trim();
        Page<User> page = userRepository.search(normalizedTerm, status, pageable);

        // Roles and bans are fetched once for the whole page rather than per row,
        // so a large page does not turn into 2N queries.
        Set<UUID> userIds = page.getContent().stream().map(User::getId).collect(Collectors.toSet());
        Map<UUID, List<NavioRole>> rolesByUser = rolesFor(userIds);

        Instant now = Instant.now();
        return page.map(user -> new AdminUserSummaryResponse(
                user.getId(),
                user.getDisplayName(),
                user.getEmail(),
                user.getStatus().value(),
                rolesByUser.getOrDefault(user.getId(), List.of()),
                user.getCreatedAt(),
                suspendedUntil(user.getId(), now)
        ));
    }

    private Map<UUID, List<NavioRole>> rolesFor(Set<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userIds.stream().collect(Collectors.toMap(
                Function.identity(),
                userId -> userRoleRepository.findByUserId(userId).stream()
                        .map(assignment -> assignment.getRole())
                        .toList()));
    }

    /**
     * @return when the current suspension ends, or null if not suspended or the
     * suspension is indefinite.
     */
    private Instant suspendedUntil(UUID userId, Instant now) {
        return userBanRepository.findActiveBans(userId, now).stream()
                .findFirst()
                .map(ban -> ban.getEndsAt())
                .orElse(null);
    }
}
