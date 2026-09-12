package com.navio.usermanagementservice.service;

import com.navio.usermanagementservice.dto.SavedPlaceRequests.CreateSavedPlaceRequest;
import com.navio.usermanagementservice.dto.SavedPlaceRequests.UpdateSavedPlaceRequest;
import com.navio.usermanagementservice.dto.SavedPlaceResponse;
import com.navio.usermanagementservice.exception.UserManagementExceptions.BusinessRuleException;
import com.navio.usermanagementservice.exception.UserManagementExceptions.SavedPlaceNotFoundException;
import com.navio.usermanagementservice.model.UserSavedPlace;
import com.navio.usermanagementservice.model.UserSavedPlace.SavedPlaceKind;
import com.navio.usermanagementservice.repository.UserRepository;
import com.navio.usermanagementservice.repository.UserSavedPlaceRepository;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The user's saved personal anchors — where their days start and end.
 *
 * <p>Every operation resolves the place with a query scoped to
 * {@code caller.id()}. Nothing here loads a place by id alone and checks
 * ownership afterwards, so a request for someone else's place id returns 404
 * from the query itself.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserSavedPlaceService {

    /**
     * Upper bound on saved places.
     *
     * <p>Without a cap an authenticated caller could insert rows indefinitely,
     * which is a cheap way to exhaust storage on a shared database.
     */
    private static final long MAX_SAVED_PLACES_PER_USER = 50;

    private final UserSavedPlaceRepository savedPlaceRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public List<SavedPlaceResponse> listMySavedPlaces(AuthenticatedUser caller) {
        return savedPlaceRepository
                .findByUserIdAndDeletedAtIsNullOrderByIsDefaultDescCreatedAtAsc(caller.id())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public SavedPlaceResponse getMySavedPlace(AuthenticatedUser caller, UUID placeId) {
        return toResponse(requireOwnedPlace(caller, placeId));
    }

    @Transactional
    public SavedPlaceResponse addSavedPlace(AuthenticatedUser caller, CreateSavedPlaceRequest request) {
        userRepository.lockSavedPlaces(caller.id());
        long existing = savedPlaceRepository.countByUserIdAndDeletedAtIsNull(caller.id());
        if (existing >= MAX_SAVED_PLACES_PER_USER) {
            throw new BusinessRuleException(
                    "You can save at most " + MAX_SAVED_PLACES_PER_USER + " places. Remove one and try again.");
        }

        SavedPlaceKind kind = parseKind(request.kind());
        requireVacantSingleton(caller, kind, null);
        requireFiniteCoordinates(request.lat(), request.lng());

        // The first saved place becomes the default start point, so the planner
        // has something to preselect without asking on every new trip.
        boolean makeDefault = Boolean.TRUE.equals(request.isDefault()) || existing == 0;

        UserSavedPlace place = UserSavedPlace.builder()
                .userId(caller.id())
                .label(request.label().trim())
                .kind(kind)
                .name(request.name().trim())
                .address(trimToNull(request.address()))
                .lat(request.lat())
                .lng(request.lng())
                .providerPlaceId(trimToNull(request.providerPlaceId()))
                .isDefault(makeDefault)
                .build();

        if (makeDefault) {
            savedPlaceRepository.clearDefaultForUser(caller.id(), null);
        }

        UserSavedPlace saved = savedPlaceRepository.save(place);
        // The label and kind are recorded; the address and coordinates are not,
        // because the audit log is read by administrators.
        auditService.record(caller.id(), AuditAction.USER_SAVED_PLACE_CREATED,
                AuditAction.RESOURCE_SAVED_PLACE, saved.getId(),
                Map.of("label", saved.getLabel(), "kind", saved.getKind().name()));

        return toResponse(saved);
    }

    @Transactional
    public SavedPlaceResponse updateSavedPlace(
            AuthenticatedUser caller, UUID placeId, UpdateSavedPlaceRequest request) {
        userRepository.lockSavedPlaces(caller.id());
        UserSavedPlace place = requireOwnedPlace(caller, placeId);

        if (request.label() != null) {
            if (request.label().isBlank()) {
                throw new BusinessRuleException("label must not be blank");
            }
            place.setLabel(request.label().trim());
        }
        if (request.kind() != null) {
            SavedPlaceKind kind = parseKind(request.kind());
            requireVacantSingleton(caller, kind, place.getId());
            place.setKind(kind);
        }
        if (request.name() != null) {
            if (request.name().isBlank()) {
                throw new BusinessRuleException("name must not be blank");
            }
            place.setName(request.name().trim());
        }
        if (request.address() != null) {
            place.setAddress(trimToNull(request.address()));
        }
        // Latitude and longitude move together: accepting one without the other
        // would leave the row pointing at a place that does not exist.
        if (request.lat() != null || request.lng() != null) {
            if (request.lat() == null || request.lng() == null) {
                throw new BusinessRuleException("lat and lng must be updated together");
            }
            requireFiniteCoordinates(request.lat(), request.lng());
            place.setLat(request.lat());
            place.setLng(request.lng());
        }
        if (request.providerPlaceId() != null) {
            place.setProviderPlaceId(trimToNull(request.providerPlaceId()));
        }

        if (Boolean.TRUE.equals(request.isDefault()) && !place.isDefault()) {
            savedPlaceRepository.clearDefaultForUser(caller.id(), place.getId());
            place.setDefault(true);
        } else if (Boolean.FALSE.equals(request.isDefault()) && place.isDefault()) {
            place.setDefault(false);
        }

        UserSavedPlace saved = savedPlaceRepository.save(place);
        auditService.record(caller.id(), AuditAction.USER_SAVED_PLACE_UPDATED,
                AuditAction.RESOURCE_SAVED_PLACE, saved.getId(),
                Map.of("label", saved.getLabel(), "kind", saved.getKind().name()));

        return toResponse(saved);
    }

    /**
     * Soft-deletes a saved place.
     *
     * <p>The row is retained because audit entries point at its id. Trips are
     * unaffected either way: they hold a copy of the coordinates, not a
     * reference to this row.
     */
    @Transactional
    public void deleteSavedPlace(AuthenticatedUser caller, UUID placeId) {
        userRepository.lockSavedPlaces(caller.id());
        UserSavedPlace place = requireOwnedPlace(caller, placeId);
        boolean wasDefault = place.isDefault();
        place.setDeletedAt(Instant.now());
        // Release the default slot, otherwise the partial unique index would
        // keep a deleted row occupying it.
        place.setDefault(false);
        savedPlaceRepository.saveAndFlush(place);

        if (wasDefault) {
            savedPlaceRepository
                    .findByUserIdAndDeletedAtIsNullOrderByIsDefaultDescCreatedAtAsc(caller.id())
                    .stream().findFirst().ifPresent(next -> {
                        next.setDefault(true);
                        savedPlaceRepository.save(next);
                    });
        }

        auditService.record(caller.id(), AuditAction.USER_SAVED_PLACE_DELETED,
                AuditAction.RESOURCE_SAVED_PLACE, place.getId(),
                Map.of("kind", place.getKind().name()));
    }

    /**
     * Loads a saved place that belongs to the caller.
     *
     * @throws SavedPlaceNotFoundException when it does not exist <em>or</em>
     *                                     belongs to someone else — the two are
     *                                     deliberately indistinguishable.
     */
    private UserSavedPlace requireOwnedPlace(AuthenticatedUser caller, UUID placeId) {
        return savedPlaceRepository.findByIdAndUserIdAndDeletedAtIsNull(placeId, caller.id())
                .orElseThrow(() -> new SavedPlaceNotFoundException(placeId));
    }

    /**
     * Rejects a second HOME or WORK before the unique index does, so the caller
     * gets an actionable message instead of a 409 from a constraint name.
     */
    private void requireVacantSingleton(AuthenticatedUser caller, SavedPlaceKind kind, UUID exceptId) {
        if (kind == SavedPlaceKind.CUSTOM) {
            return;
        }
        savedPlaceRepository.findByUserIdAndKindAndDeletedAtIsNull(caller.id(), kind)
                .filter(existing -> !existing.getId().equals(exceptId))
                .ifPresent(existing -> {
                    throw new BusinessRuleException(
                            "You already have a " + kind.name().toLowerCase(Locale.ROOT)
                                    + " saved. Edit it instead of adding another.");
                });
    }

    /**
     * Bean validation bounds NaN out of range only by luck of comparison order,
     * so infinities and NaN are rejected explicitly before they reach routing.
     */
    private void requireFiniteCoordinates(Double lat, Double lng) {
        if (lat == null || lng == null || !Double.isFinite(lat) || !Double.isFinite(lng)) {
            throw new BusinessRuleException("Choose a place with valid coordinates");
        }
    }

    private SavedPlaceKind parseKind(String value) {
        if (value == null || value.isBlank()) {
            return SavedPlaceKind.CUSTOM;
        }
        try {
            return SavedPlaceKind.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BusinessRuleException("kind must be HOME, WORK or CUSTOM");
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private SavedPlaceResponse toResponse(UserSavedPlace place) {
        return new SavedPlaceResponse(
                place.getId(),
                place.getLabel(),
                place.getKind().name(),
                place.getName(),
                place.getAddress(),
                place.getLat(),
                place.getLng(),
                place.getProviderPlaceId(),
                place.isDefault(),
                place.getCreatedAt(),
                place.getUpdatedAt());
    }
}
