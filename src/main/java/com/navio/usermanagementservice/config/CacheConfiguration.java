package com.navio.usermanagementservice.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.navio.usermanagementservice.service.BanStatusService;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Caches used by the service.
 *
 * <p>Only the ban-status cache is defined. It is checked on every authenticated
 * request, so the TTL is a security parameter rather than a tuning knob: it
 * bounds how long a suspension could go unnoticed if the explicit eviction were
 * ever missed.
 *
 * <p>Sixty seconds is short enough for that backstop to be acceptable, and in
 * normal operation it never applies — {@code UserModerationService} evicts the
 * entry as soon as its transaction commits, so a suspension takes effect on the
 * user's next request.
 *
 * <p>Note that this is an in-process cache. With several replicas, an eviction
 * on one instance does not reach the others, so the TTL is what bounds
 * propagation there. Moving to a shared cache would remove that gap; for the
 * current deployment the short TTL plus Keycloak session revocation is enough.
 */
@Configuration
@EnableCaching
public class CacheConfiguration {

    private static final Duration BAN_STATUS_TTL = Duration.ofSeconds(60);
    private static final long BAN_STATUS_MAX_ENTRIES = 10_000;

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(BanStatusService.BAN_STATUS_CACHE);
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(BAN_STATUS_TTL)
                .maximumSize(BAN_STATUS_MAX_ENTRIES)
                .recordStats());
        // Optional.empty() results are cached too — "not suspended" is the common
        // case and is exactly what we want to avoid re-querying.
        cacheManager.setAllowNullValues(true);
        return cacheManager;
    }
}
