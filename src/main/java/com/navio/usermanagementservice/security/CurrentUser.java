package com.navio.usermanagementservice.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the caller's {@link AuthenticatedUser}, resolved from the validated
 * JWT.
 *
 * <p>This is the <em>only</em> supported way for a handler to learn who is
 * calling. Controllers must not accept a user id as a header, path variable, or
 * body field — that is precisely the pattern that makes
 * {@code X-User-Id}-style trust exploitable.
 *
 * <pre>{@code
 * @GetMapping("/me")
 * public UserProfileResponse me(@CurrentUser AuthenticatedUser caller) { ... }
 * }</pre>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUser {
}
