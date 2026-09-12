package com.navio.usermanagementservice.controller;

import com.navio.usermanagementservice.dto.SavedPlaceRequests.CreateSavedPlaceRequest;
import com.navio.usermanagementservice.dto.SavedPlaceRequests.UpdateSavedPlaceRequest;
import com.navio.usermanagementservice.dto.SavedPlaceResponse;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import com.navio.usermanagementservice.security.CurrentUser;
import com.navio.usermanagementservice.service.UserSavedPlaceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The caller's saved personal anchors — home, work, and anywhere else their
 * travel days tend to start or end.
 *
 * <p>The route is {@code /me/places}, never {@code /{userId}/places}. With no
 * user id in the path there is nothing for a caller to tamper with, so the
 * endpoint cannot be pointed at someone else's saved addresses — the ownership
 * scope is structural rather than a check that could be omitted.
 */
@RestController
@RequestMapping("/v1/users/me/places")
@RequiredArgsConstructor
public class UserSavedPlaceController {

    private final UserSavedPlaceService savedPlaceService;

    @GetMapping
    public ResponseEntity<List<SavedPlaceResponse>> listSavedPlaces(@CurrentUser AuthenticatedUser caller) {
        return ResponseEntity.ok(savedPlaceService.listMySavedPlaces(caller));
    }

    @GetMapping("/{placeId}")
    public ResponseEntity<SavedPlaceResponse> getSavedPlace(
            @CurrentUser AuthenticatedUser caller,
            @PathVariable UUID placeId) {
        return ResponseEntity.ok(savedPlaceService.getMySavedPlace(caller, placeId));
    }

    @PostMapping
    public ResponseEntity<SavedPlaceResponse> addSavedPlace(
            @CurrentUser AuthenticatedUser caller,
            @Valid @RequestBody CreateSavedPlaceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(savedPlaceService.addSavedPlace(caller, request));
    }

    @PatchMapping("/{placeId}")
    public ResponseEntity<SavedPlaceResponse> updateSavedPlace(
            @CurrentUser AuthenticatedUser caller,
            @PathVariable UUID placeId,
            @Valid @RequestBody UpdateSavedPlaceRequest request) {
        return ResponseEntity.ok(savedPlaceService.updateSavedPlace(caller, placeId, request));
    }

    @DeleteMapping("/{placeId}")
    public ResponseEntity<Void> deleteSavedPlace(
            @CurrentUser AuthenticatedUser caller,
            @PathVariable UUID placeId) {
        savedPlaceService.deleteSavedPlace(caller, placeId);
        return ResponseEntity.noContent().build();
    }
}
