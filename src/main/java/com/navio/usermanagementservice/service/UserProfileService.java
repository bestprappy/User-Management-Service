package com.navio.usermanagementservice.service;

import com.navio.usermanagementservice.dto.PublicUserProfileResponse;
import com.navio.usermanagementservice.dto.TravelProfile;
import com.navio.usermanagementservice.dto.UpdateProfileRequest;
import com.navio.usermanagementservice.dto.UsernameAvailabilityResponse;
import com.navio.usermanagementservice.dto.UserPreferences;
import com.navio.usermanagementservice.dto.UserProfileResponse;
import com.navio.usermanagementservice.exception.UserManagementExceptions.BusinessRuleException;
import com.navio.usermanagementservice.exception.UserManagementExceptions.UserGoneException;
import com.navio.usermanagementservice.exception.UserManagementExceptions.UserNotFoundException;
import com.navio.usermanagementservice.model.User;
import com.navio.usermanagementservice.repository.UserRepository;
import com.navio.usermanagementservice.repository.UsernameHistoryRepository;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import com.navio.usermanagementservice.security.NavioRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
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
    private final UsernameService usernameService;
    private final UsernameHistoryRepository usernameHistory;

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

    public PublicUserProfileResponse getPublicProfileBySubject(UUID subject) {
        var user = userRepository.findByAuthSubject(subject.toString())
                .filter(profile -> profile.getDeletedAt() == null)
                .orElseThrow(() -> new UserNotFoundException(subject));
        return userMapper.toPublicProfile(user);
    }

    /**
     * A public profile by handle. A handle the owner has since changed still
     * resolves (the response carries the current one, so the client redirects).
     * A closed account answers 410 so the page can say so instead of "not found".
     */
    public PublicUserProfileResponse getPublicProfileByUsername(String rawUsername) {
        String username = UsernamePolicy.normalize(rawUsername);
        var current = userRepository.findByUsername(username);
        if (current.isPresent()) return publicOrGone(current.get());
        return usernameHistory.mostRecentOwner(username)
                .flatMap(userRepository::findById)
                .map(this::publicOrGone)
                .orElseThrow(() -> new UserNotFoundException("No user with that username"));
    }

    /** Most results one people-search page may return. */
    public static final int MAX_SEARCH_PAGE = 50;

    /**
     * Finds people by handle prefix or display name. Only public profile fields
     * are matched and returned; email is never searched.
     */
    public org.springframework.data.domain.Page<PublicUserProfileResponse> searchPeople(String query, int page, int size) {
        String term = query == null ? "" : query.strip();
        if (term.startsWith("@")) term = term.substring(1);
        if (term.length() < 2 || term.length() > 60) {
            throw new BusinessRuleException("Search for 2 to 60 characters");
        }
        if (page < 0 || size < 1 || size > MAX_SEARCH_PAGE) {
            throw new BusinessRuleException("Page must be nonnegative and size between 1 and 50");
        }
        String escaped = term.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return userRepository.searchPublicProfiles(escaped.toLowerCase(Locale.ROOT), escaped,
                        term.toLowerCase(Locale.ROOT), org.springframework.data.domain.PageRequest.of(page, size))
                .map(userMapper::toPublicProfile);
    }

    public UsernameAvailabilityResponse checkUsername(AuthenticatedUser caller, String candidate) {
        return usernameService.availability(candidate, requireUser(caller.id()));
    }

    private PublicUserProfileResponse publicOrGone(User user) {
        if (user.isDeleted()) throw new UserGoneException();
        return userMapper.toPublicProfile(user);
    }

    @Transactional
    public UserProfileResponse updateMyProfile(AuthenticatedUser caller, UpdateProfileRequest request) {
        if (request.avatarMediaId() != null) {
            throw new com.navio.usermanagementservice.exception.UserManagementExceptions.BusinessRuleException(
                    "Upload your profile picture through the picture endpoint");
        }
        User user = requireUser(caller.id());

        Map<String, Object> before = profileSnapshot(user);
        boolean changed = false;

        if (request.displayName() != null) {
            user.setDisplayName(request.displayName().trim());
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
        if (request.username() != null && usernameService.change(user, request.username())) {
            changed = true;
        }
        if (request.bio() != null) {
            String bio = request.bio().strip();
            user.setBio(bio.isEmpty() ? null : bio);
            changed = true;
        }
        if (request.travelInterests() != null) {
            List<String> unknown = request.travelInterests().stream()
                    .filter(value -> !TravelProfile.INTERESTS.contains(value)).toList();
            if (!unknown.isEmpty()) throw new BusinessRuleException("Unknown travel interest: " + unknown.getFirst());
            user.setTravelInterests(new ArrayList<>(TravelProfile.sanitizeInterests(request.travelInterests())));
            changed = true;
        }
        if (request.travelPace() != null) {
            user.setTravelPace(request.travelPace().isEmpty() ? null : request.travelPace());
            changed = true;
        }
        if (request.showTravelPreferences() != null) {
            user.setShowTravelPreferences(request.showTravelPreferences());
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
                Map.of("userId", saved.getId().toString(), "displayName", saved.getDisplayName(),
                        "username", String.valueOf(saved.getUsername())));

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
        snapshot.put("username", user.getUsername());
        snapshot.put("bio", user.getBio());
        snapshot.put("travelInterests", user.getTravelInterests() == null ? List.of() : List.copyOf(user.getTravelInterests()));
        snapshot.put("travelPace", user.getTravelPace());
        snapshot.put("showTravelPreferences", user.getShowTravelPreferences());
        return snapshot;
    }
}
