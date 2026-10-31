package com.navio.usermanagementservice.service;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The rules for public handles, in one place so the API, the availability check
 * and provisioning can never disagree.
 *
 * <ul>
 *   <li>3-30 characters: lowercase letters, digits and underscores, starting with a letter.</li>
 *   <li>Unique ignoring case (stored lowercase).</li>
 *   <li>Changeable once every {@link #CHANGE_COOLDOWN}.</li>
 *   <li>A released handle keeps redirecting to its previous owner and cannot be
 *       claimed by anyone else for {@link #RELEASE_HOLD}.</li>
 * </ul>
 */
public final class UsernamePolicy {

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 30;
    public static final Duration CHANGE_COOLDOWN = Duration.ofDays(30);
    public static final Duration RELEASE_HOLD = Duration.ofDays(90);

    private static final Pattern FORMAT = Pattern.compile("^[a-z][a-z0-9_]{2,29}$");

    /** Handles that would impersonate staff or collide with application routes. */
    private static final Set<String> RESERVED = Set.of(
            "admin", "administrator", "api", "app", "community", "dashboard", "explore", "help",
            "login", "logout", "me", "moderator", "mod", "navio", "notifications", "official",
            "owner", "planner", "profile", "root", "saved", "security", "settings", "share",
            "signin", "signup", "staff", "support", "system", "team", "user", "users");

    private UsernamePolicy() {
    }

    /** Trims, strips one leading {@code @}, and lowercases. */
    public static String normalize(String raw) {
        if (raw == null) return "";
        String value = raw.strip();
        if (value.startsWith("@")) value = value.substring(1);
        return value.toLowerCase(Locale.ROOT);
    }

    /** Why a normalized handle is unacceptable, or empty when its shape is fine. */
    public static Optional<String> problem(String normalized) {
        if (normalized.length() < MIN_LENGTH || normalized.length() > MAX_LENGTH) {
            return Optional.of("Usernames are 3-30 characters");
        }
        if (!FORMAT.matcher(normalized).matches()) {
            return Optional.of("Use lowercase letters, numbers and underscores, starting with a letter");
        }
        if (RESERVED.contains(normalized) || normalized.startsWith("navio")) {
            return Optional.of("This username is reserved");
        }
        return Optional.empty();
    }

    /** A slug suitable as a generated handle's base, always valid in shape. */
    public static String baseFrom(String displayName) {
        String slug = normalize(displayName).replaceAll("[^a-z0-9]+", "");
        if (slug.length() > 20) slug = slug.substring(0, 20);
        if (slug.isEmpty() || !Character.isLetter(slug.charAt(0))) slug = "traveler" + slug;
        if (slug.length() > 20) slug = slug.substring(0, 20);
        if (slug.length() < MIN_LENGTH) slug = slug + "traveler";
        if (RESERVED.contains(slug) || slug.startsWith("navio")) slug = "traveler" + slug;
        return slug.length() > 24 ? slug.substring(0, 24) : slug;
    }
}
