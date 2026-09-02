package com.navio.usermanagementservice.service;

import com.navio.usermanagementservice.repository.UserBanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Cached lookup of whether a user is currently suspended.
 *
 * <p>Lives in its own bean deliberately. Spring's caching is proxy-based, so a
 * {@code @Cacheable} method called from another method of the same class is
 * invoked directly and silently skips the cache. Putting this behind a separate
 * bean means the annotation actually takes effect — and keeps the ban-state
 * concern separate from provisioning.
 *
 * <p>Every request checks this, so it is the hottest path in the service.
 */
@Service
@RequiredArgsConstructor
public class BanStatusService {

    /** Cache name; TTL is configured in {@code CacheConfiguration}. */
    public static final String BAN_STATUS_CACHE = "userBanStatus";

    private final UserBanRepository userBanRepository;

    /**
     * @return the active suspension for this user, or empty when not suspended.
     */
    @Cacheable(cacheNames = BAN_STATUS_CACHE, key = "#userId")
    @Transactional(readOnly = true)
    public Optional<BanSnapshot> activeBan(UUID userId) {
        return userBanRepository.findActiveBans(userId, Instant.now()).stream()
                .findFirst()
                .map(ban -> new BanSnapshot(ban.getId(), ban.getReason(), ban.getEndsAt()));
    }

    /**
     * Drops cached state for a user.
     *
     * <p>Called immediately after any suspension or reactivation so the change
     * takes effect on the caller's very next request rather than after the TTL.
     */
    @CacheEvict(cacheNames = BAN_STATUS_CACHE, key = "#userId")
    public void evict(UUID userId) {
        // Annotation-driven; no body required.
    }

    /** Minimal projection of an active ban. Safe to cache: contains no secrets. */
    public record BanSnapshot(UUID banId, String reason, Instant endsAt) {
    }
}
