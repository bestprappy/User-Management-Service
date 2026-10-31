package com.navio.usermanagementservice.controller;

import com.navio.usermanagementservice.dto.PublicUserProfileResponse;
import com.navio.usermanagementservice.dto.UpdateProfileRequest;
import com.navio.usermanagementservice.dto.UserPreferences;
import com.navio.usermanagementservice.dto.UserProfileResponse;
import com.navio.usermanagementservice.dto.UsernameAvailabilityResponse;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import com.navio.usermanagementservice.security.CurrentUser;
import com.navio.usermanagementservice.service.UserProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Profile endpoints.
 *
 * <p>Every write acts on {@code caller}, which the {@code @CurrentUser} resolver
 * derives from the validated JWT. No handler takes a user id for a write, and
 * none reads {@code X-User-Id} — the header carries no authority in this service.
 */
@RestController
@RequestMapping("/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserProfileService userProfileService;

    /** The caller's own profile, including email and status. */
    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> getCurrentUser(@CurrentUser AuthenticatedUser caller) {
        return ResponseEntity.ok(userProfileService.getMyProfile(caller));
    }

    @PatchMapping("/me")
    public ResponseEntity<UserProfileResponse> updateCurrentUser(
            @CurrentUser AuthenticatedUser caller,
            @Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(userProfileService.updateMyProfile(caller, request));
    }

    @PatchMapping("/me/preferences")
    public ResponseEntity<UserProfileResponse> updatePreferences(
            @CurrentUser AuthenticatedUser caller,
            @Valid @RequestBody UserPreferences request) {
        return ResponseEntity.ok(userProfileService.updatePreferences(caller, request));
    }

    /**
     * Another user's public profile.
     *
     * <p>{@code caller} is present only to require authentication — the response
     * is identical for every caller, so there is nothing here to vary by
     * identity. Anonymous access is not allowed, which keeps the endpoint from
     * becoming an open profile-scraping surface.
     */
    @GetMapping("/{userId}")
    public ResponseEntity<PublicUserProfileResponse> getPublicProfile(
            @CurrentUser AuthenticatedUser caller,
            @PathVariable UUID userId) {
        return ResponseEntity.ok(userProfileService.getPublicProfile(userId));
    }

    /**
     * A public profile by handle. A previous handle resolves to its owner (the
     * response carries the current one); a closed account answers 410.
     */
    @GetMapping("/by-username/{username}")
    public ResponseEntity<PublicUserProfileResponse> getPublicProfileByUsername(
            @CurrentUser AuthenticatedUser caller, @PathVariable String username) {
        return ResponseEntity.ok(userProfileService.getPublicProfileByUsername(username));
    }

    /** People search by handle prefix or display name; signed-in callers only. */
    @GetMapping("/search")
    public ResponseEntity<org.springframework.data.domain.Page<PublicUserProfileResponse>> searchPeople(
            @CurrentUser AuthenticatedUser caller,
            @org.springframework.web.bind.annotation.RequestParam String q,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(userProfileService.searchPeople(q, page, size));
    }

    /** Whether the caller could take this handle right now, with the reason when not. */
    @GetMapping("/username-availability")
    public ResponseEntity<UsernameAvailabilityResponse> checkUsername(
            @CurrentUser AuthenticatedUser caller,
            @org.springframework.web.bind.annotation.RequestParam String username) {
        return ResponseEntity.ok(userProfileService.checkUsername(caller, username));
    }

    @GetMapping("/by-subject/{subject}")
    public ResponseEntity<PublicUserProfileResponse> getPublicProfileBySubject(
            @CurrentUser AuthenticatedUser caller, @PathVariable UUID subject) {
        return ResponseEntity.ok(userProfileService.getPublicProfileBySubject(subject));
    }
}
