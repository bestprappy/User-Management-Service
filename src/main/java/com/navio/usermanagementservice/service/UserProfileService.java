package com.navio.usermanagementservice.service;

import com.navio.usermanagementservice.dto.PublicUserProfileResponse;
import com.navio.usermanagementservice.dto.UpdateProfileRequest;
import com.navio.usermanagementservice.dto.UserPreferences;
import com.navio.usermanagementservice.dto.UserProfileResponse;
import com.navio.usermanagementservice.exception.UserManagementExceptions.UserNotFoundException;
import com.navio.usermanagementservice.model.User;
import com.navio.usermanagementservice.repository.UserRepository;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import com.navio.usermanagementservice.security.NavioRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Profile reads and self-service edits.
 *
 * <p>Every mutating method takes the caller as an {@link AuthenticatedUser} and
 * operates on {@code caller.id()}. No method accepts a target user id for a
 * write, so there is no parameter an attacker could point at somebody else's
 * profile.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserProfileService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final AuditService auditService;
    private final OutboxService outboxService;

    /** The caller's own profile, including email and status. */
    public UserProfileResponse getMyProfile(AuthenticatedUser caller) {
        User user = requireUser(caller.id());
        return userMapper.toProfile(user, rolesOf(caller));
    }

    /**
     * Another user's public profile.
     *
     * <p>Soft-deleted profiles return 404 rather than a tombstone: a closed
     * account should not remain discoverable by id.
     */
    public PublicUserProfileResponse getPublicProfile(UUID userId) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        return userMapper.toPublicProfile(user);
    }

    @Transactional
    public UserProfileResponse updateMyProfile(AuthenticatedUser caller, UpdateProfileRequest request) {
        User user = requireUser(caller.id());

        Map<String, Object> before = profileSnapshot(user);
        boolean changed = false;

        if (request.displayName() != null) {
            user.setDisplayName(request.displayName().trim());
            changed = true;
        }
        if (request.avatarMediaId() != null) {
            user.setAvatarMediaId(request.avatarMediaId());
            changed = true;
        }
        if (request.locale() != null) {
            user.setLocale(request.locale());
            changed = true;
        }
        if (request.countryCode() != null) {
            // Normalised so the ISO code is stored consistently regardless of
            // how the client sent it.
            user.setCountryCode(request.countryCode().toUpperCase(Locale.ROOT));
            changed = true;
        }

        if (!changed) {
            return userMapper.toProfile(user, rolesOf(caller));
        }

        User saved = userRepository.save(user);
        auditService.record(caller.id(), AuditAction.USER_PROFILE_UPDATED,
                AuditAction.RESOURCE_USER, saved.getId(),
                before, profileSnapshot(saved), Map.of());
        outboxService.publish(saved.getId(), OutboxService.EVENT_USER_PROFILE_UPDATED,
                Map.of("userId", saved.getId().toString(), "displayName", saved.getDisplayName()));

        return userMapper.toProfile(saved, rolesOf(caller));
    }

    /**
     * Applies a partial preference update.
     *
     * <p>The incoming record is merged onto the stored one, so a client sending
     * only {@code {"language":"th"}} does not silently reset the other settings.
     */
    @Transactional
    public UserProfileResponse updatePreferences(AuthenticatedUser caller, UserPreferences request) {
        User user = requireUser(caller.id());

        UserPreferences current = userMapper.toPreferences(user.getPreferences());
        UserPreferences merged = request.mergedOnto(current);
        user.setPreferences(userMapper.toMap(merged));

        User saved = userRepository.save(user);
        auditService.record(caller.id(), AuditAction.USER_PREFERENCES_UPDATED,
                AuditAction.RESOURCE_USER, saved.getId(), Map.of());

        return userMapper.toProfile(saved, rolesOf(caller));
    }

    private User requireUser(UUID userId) {
        return userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
    }

    /**
     * Roles come from the caller's validated token, not from
     * {@code iam.user_roles} — that table is a display snapshot and may lag a
     * Keycloak change.
     */
    private List<NavioRole> rolesOf(AuthenticatedUser caller) {
        return caller.roles().stream().sorted().toList();
    }

    /** Field-level snapshot for the audit diff. Contains no secrets. */
    private Map<String, Object> profileSnapshot(User user) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("displayName", user.getDisplayName());
        snapshot.put("avatarMediaId", user.getAvatarMediaId() == null
                ? null : user.getAvatarMediaId().toString());
        snapshot.put("locale", user.getLocale());
        snapshot.put("countryCode", user.getCountryCode());
        return snapshot;
    }
}
