package com.navio.usermanagementservice.service;

import com.navio.usermanagementservice.dto.UsernameAvailabilityResponse;
import com.navio.usermanagementservice.exception.UserManagementExceptions.BusinessRuleException;
import com.navio.usermanagementservice.exception.UserManagementExceptions.UsernameTakenException;
import com.navio.usermanagementservice.model.User;
import com.navio.usermanagementservice.repository.UserRepository;
import com.navio.usermanagementservice.repository.UsernameHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Assigns, validates and changes public handles under {@link UsernamePolicy}. */
@Service
@RequiredArgsConstructor
public class UsernameService {

    private static final int GENERATION_ATTEMPTS = 25;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final UsernameHistoryRepository history;
    private final Clock clock;

    /** A free handle derived from the display name; never throws for a valid user. */
    public String generateFor(String displayName, UUID userId) {
        String base = UsernamePolicy.baseFrom(displayName);
        if (isFree(base, userId)) return base;
        for (int attempt = 0; attempt < GENERATION_ATTEMPTS; attempt++) {
            String candidate = base + (attempt < 10 ? 2 + attempt : 10 + RANDOM.nextInt(9990));
            if (isFree(candidate, userId)) return candidate;
        }
        byte[] suffix = new byte[5];
        RANDOM.nextBytes(suffix);
        return "traveler_" + HexFormat.of().formatHex(suffix);
    }

    /** Gives an older profile without a handle its first one. Returns true when it changed. */
    public boolean ensureAssigned(User user) {
        if (user.getUsername() != null) return false;
        user.setUsername(generateFor(user.getDisplayName(), user.getId()));
        return true;
    }

    public UsernameAvailabilityResponse availability(String raw, User caller) {
        String normalized = UsernamePolicy.normalize(raw);
        var problem = UsernamePolicy.problem(normalized);
        if (problem.isPresent()) return new UsernameAvailabilityResponse(normalized, false, problem.get());
        if (normalized.equals(caller.getUsername())) return new UsernameAvailabilityResponse(normalized, true, "This is your current username");
        if (!isFree(normalized, caller.getId())) {
            return new UsernameAvailabilityResponse(normalized, false, "This username is taken");
        }
        return new UsernameAvailabilityResponse(normalized, true, null);
    }

    /**
     * Applies a requested change to {@code user} (not saved here).
     *
     * @return true when the handle actually changed
     */
    public boolean change(User user, String raw) {
        String normalized = UsernamePolicy.normalize(raw);
        if (normalized.equals(user.getUsername())) return false;
        UsernamePolicy.problem(normalized).ifPresent(message -> {
            throw new BusinessRuleException(message);
        });
        Instant now = clock.instant();
        Instant lastChange = user.getUsernameChangedAt();
        if (lastChange != null && lastChange.plus(UsernamePolicy.CHANGE_COOLDOWN).isAfter(now)) {
            throw new BusinessRuleException("You can change your username again after "
                    + lastChange.plus(UsernamePolicy.CHANGE_COOLDOWN));
        }
        if (!isFree(normalized, user.getId())) throw new UsernameTakenException();
        String previous = user.getUsername();
        if (previous != null) history.recordRelease(previous, user.getId(), now);
        user.setUsername(normalized);
        user.setUsernameChangedAt(now);
        return true;
    }

    /** When the next change is allowed, or null when it is allowed now. */
    public Instant nextChangeAllowedAt(User user) {
        Instant last = user.getUsernameChangedAt();
        if (last == null) return null;
        Instant next = last.plus(UsernamePolicy.CHANGE_COOLDOWN);
        return next.isAfter(clock.instant()) ? next : null;
    }

    private boolean isFree(String username, UUID userId) {
        var holder = users.findByUsername(username);
        if (holder.isPresent() && !Objects.equals(holder.get().getId(), userId)) return false;
        return !history.heldByAnotherAccount(username, userId, clock.instant().minus(UsernamePolicy.RELEASE_HOLD));
    }
}
