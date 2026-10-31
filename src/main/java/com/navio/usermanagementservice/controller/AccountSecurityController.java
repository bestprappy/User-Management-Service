package com.navio.usermanagementservice.controller;

import com.navio.usermanagementservice.dto.SecurityOverviewResponse;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import com.navio.usermanagementservice.security.CurrentUser;
import com.navio.usermanagementservice.service.AccountSecurityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Owner-only sign-in facts and the sign-out-everywhere action. Nothing here is
 * reachable for another user's account: both routes act on the token's subject.
 */
@RestController
@RequestMapping("/v1/users/me")
@RequiredArgsConstructor
public class AccountSecurityController {

    private final AccountSecurityService security;

    @GetMapping("/security")
    public ResponseEntity<SecurityOverviewResponse> overview(@CurrentUser AuthenticatedUser caller,
                                                             @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(security.overview(caller, authenticatedAt(jwt)));
    }

    @PostMapping("/sessions/revoke")
    public ResponseEntity<Void> signOutEverywhere(@CurrentUser AuthenticatedUser caller,
                                                  @AuthenticationPrincipal Jwt jwt) {
        security.signOutEverywhere(caller, authenticatedAt(jwt));
        return ResponseEntity.noContent().build();
    }

    /** Keycloak's {@code auth_time}: when the user last actually entered credentials. */
    static Instant authenticatedAt(Jwt jwt) {
        if (jwt == null) return null;
        Object value = jwt.getClaims().get("auth_time");
        if (value instanceof Instant instant) return instant;
        if (value instanceof Number seconds) return Instant.ofEpochSecond(seconds.longValue());
        return null;
    }
}
