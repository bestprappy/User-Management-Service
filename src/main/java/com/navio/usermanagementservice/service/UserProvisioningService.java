package com.navio.usermanagementservice.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navio.usermanagementservice.dto.UserPreferences;
import com.navio.usermanagementservice.exception.UserManagementExceptions.AccountSuspendedException;
import com.navio.usermanagementservice.model.User;
import com.navio.usermanagementservice.model.UserStatus;
import com.navio.usermanagementservice.repository.UserRepository;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import com.navio.usermanagementservice.security.NavioRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves a validated Keycloak token into a Navio profile, provisioning one on
 * first sight, and refuses the request if the account is suspended.
 *
 * <h2>Why suspension is re-checked on every request</h2>
 * Disabling an account in Keycloak stops it issuing <em>new</em> tokens; it does
 * not invalidate an access token the client already holds. Between the moment a
 * moderator suspends someone and the moment their current token expires, that
 * token still verifies perfectly. Checking ban state per request closes that
 * window. Combined with the Keycloak session logout performed at suspension
 * time, access ends immediately instead of minutes later.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserProvisioningService {

    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_PREFERRED_USERNAME = "preferred_username";
    private static final String CLAIM_NAME = "name";
    private static final String CLAIM_GIVEN_NAME = "given_name";
    private static final String CLAIM_LOCALE = "locale";

    private static final int MAX_DISPLAY_NAME_LENGTH = 120;
    private static final int MAX_EMAIL_LENGTH = 255;
    private static final int MAX_LOCALE_LENGTH = 20;
    private static final String FALLBACK_DISPLAY_NAME = "Navio user";

    private final UserRepository userRepository;
    private final BanStatusService banStatusService;
    private final AuditService auditService;
    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

    /**
     * Resolves the caller, creating the profile if this is their first request.
     *
     * @param jwt   a token that has already passed signature, issuer, expiry and
     *              audience validation.
     * @param roles authorities derived from that token.
     * @throws AccountSuspendedException when the account is currently suspended.
     */
    @Transactional
    public AuthenticatedUser resolve(Jwt jwt, Set<NavioRole> roles) {
        String authSubject = jwt.getSubject();
        if (authSubject == null || authSubject.isBlank()) {
            // Should be unreachable after validation, but a profile keyed on a
            // null subject would be catastrophic — fail loudly instead.
            throw new IllegalStateException("Validated token has no subject claim");
        }

        User user = userRepository.findByAuthSubject(authSubject)
                .map(existing -> syncFromToken(existing, jwt))
                .orElseGet(() -> provision(authSubject, jwt));

        assertNotSuspended(user);

        return new AuthenticatedUser(
                user.getId(),
                user.getAuthSubject(),
                user.getEmail(),
                user.getDisplayName(),
                roles
        );
    }

    /**
     * @throws AccountSuspendedException when the account is closed or an active
     *                                   ban covers this user.
     */
    public void assertNotSuspended(User user) {
        if (user.isDeleted()) {
            throw new AccountSuspendedException("This account has been closed");
        }
        Optional<BanStatusService.BanSnapshot> ban = banStatusService.activeBan(user.getId());
        if (ban.isPresent()) {
            // Repeated rows here are the signal that a suspended or stolen
            // credential is being probed.
            auditService.recordRejectedAttempt(
                    user.getId(),
                    AuditAction.SUSPENDED_ACCESS_ATTEMPT,
                    Map.of("banId", String.valueOf(ban.get().banId())));
            throw new AccountSuspendedException(ban.get().reason());
        }
    }

    private User provision(String authSubject, Jwt jwt) {
        User user = User.builder()
                .authSubject(authSubject)
                .email(requireEmail(jwt))
                .displayName(resolveDisplayName(jwt))
                .locale(claim(jwt, CLAIM_LOCALE).map(value -> truncate(value.trim(), MAX_LOCALE_LENGTH)).orElse(null))
                .status(UserStatus.ACTIVE)
                .preferences(toMap(UserPreferences.defaults()))
                .build();

        try {
            User saved = userRepository.saveAndFlush(user);
            auditService.record(saved.getId(), AuditAction.USER_PROVISIONED,
                    AuditAction.RESOURCE_USER, saved.getId(), Map.of("source", "jit"));
            outboxService.publish(saved.getId(), OutboxService.EVENT_USER_PROVISIONED,
                    Map.of("userId", saved.getId().toString()));
            log.info("Provisioned Navio profile {} for a new Keycloak subject", saved.getId());
            return saved;
        } catch (DataIntegrityViolationException exception) {
            // Two concurrent first requests race on uq_iam_users_auth_subject.
            // The loser re-reads the winner's row rather than failing the request.
            log.debug("Concurrent provisioning detected; re-reading the existing profile");
            return userRepository.findByAuthSubject(authSubject).orElseThrow(() -> exception);
        }
    }

    /**
     * Keeps the mirrored profile aligned with Keycloak.
     *
     * <p>Only email and display name are synchronised, and only when Keycloak
     * actually differs — an unconditional write would bump {@code updated_at} on
     * every request and generate needless database load.
     */
    private User syncFromToken(User user, Jwt jwt) {
        boolean changed = false;

        String tokenEmail = claim(jwt, CLAIM_EMAIL)
                .map(value -> truncate(value.trim(), MAX_EMAIL_LENGTH))
                .orElse(null);
        if (tokenEmail != null && !tokenEmail.equalsIgnoreCase(user.getEmail())) {
            user.setEmail(tokenEmail);
            changed = true;
        }

        String tokenDisplayName = resolveDisplayNameOrNull(jwt);
        if (tokenDisplayName != null && !tokenDisplayName.equals(user.getDisplayName())) {
            user.setDisplayName(tokenDisplayName);
            changed = true;
        }

        if (!changed) {
            return user;
        }

        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            // Another account already holds the new email. Keep the stored
            // profile rather than failing the request; the mismatch is an
            // operator concern the caller cannot fix.
            log.warn("Could not sync profile {} from token: email conflicts with an existing account",
                    user.getId());
            return userRepository.findByAuthSubject(user.getAuthSubject()).orElse(user);
        }
    }

    private String requireEmail(Jwt jwt) {
        return claim(jwt, CLAIM_EMAIL)
                .map(value -> truncate(value.trim(), MAX_EMAIL_LENGTH))
                .orElseThrow(() -> new IllegalStateException(
                        "Keycloak token has no email claim; add an email mapper to the client scope"));
    }

    private String resolveDisplayName(Jwt jwt) {
        String resolved = resolveDisplayNameOrNull(jwt);
        return resolved != null ? resolved : FALLBACK_DISPLAY_NAME;
    }

    private String resolveDisplayNameOrNull(Jwt jwt) {
        return claim(jwt, CLAIM_NAME)
                .or(() -> claim(jwt, CLAIM_PREFERRED_USERNAME))
                .or(() -> claim(jwt, CLAIM_GIVEN_NAME))
                .map(value -> truncate(value.trim(), MAX_DISPLAY_NAME_LENGTH))
                .filter(value -> !value.isBlank())
                .orElse(null);
    }

    private Optional<String> claim(Jwt jwt, String name) {
        return Optional.ofNullable(jwt.getClaimAsString(name)).filter(value -> !value.isBlank());
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private Map<String, Object> toMap(UserPreferences preferences) {
        return objectMapper.convertValue(preferences, new TypeReference<>() {
        });
    }
}
