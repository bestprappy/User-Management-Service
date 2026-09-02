package com.navio.usermanagementservice.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navio.usermanagementservice.dto.PublicUserProfileResponse;
import com.navio.usermanagementservice.dto.UserPreferences;
import com.navio.usermanagementservice.dto.UserProfileResponse;
import com.navio.usermanagementservice.dto.VehicleResponse;
import com.navio.usermanagementservice.model.User;
import com.navio.usermanagementservice.model.UserVehicle;
import com.navio.usermanagementservice.security.NavioRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Entity-to-DTO mapping.
 *
 * <p>Kept separate from the services so there is exactly one place that decides
 * which fields leave the system — the distinction between
 * {@link UserProfileResponse} (owner-only) and
 * {@link PublicUserProfileResponse} (visible to any authenticated caller) is a
 * security boundary, not a formatting choice.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserMapper {

    private final ObjectMapper objectMapper;

    /** Full profile, for the owner only. */
    public UserProfileResponse toProfile(User user, List<NavioRole> roles) {
        return new UserProfileResponse(
                user.getId(),
                user.getDisplayName(),
                user.getEmail(),
                user.getAvatarMediaId(),
                user.getStatus().value(),
                user.getLocale(),
                user.getCountryCode(),
                roles,
                toPreferences(user.getPreferences()),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }

    /** Reduced profile, safe to show to other users. */
    public PublicUserProfileResponse toPublicProfile(User user) {
        return new PublicUserProfileResponse(
                user.getId(),
                user.getDisplayName(),
                user.getAvatarMediaId(),
                user.getCreatedAt()
        );
    }

    public VehicleResponse toVehicle(UserVehicle vehicle) {
        return new VehicleResponse(
                vehicle.getId(),
                vehicle.getNickname(),
                vehicle.getMake(),
                vehicle.getModel(),
                vehicle.getYear(),
                vehicle.getBatteryCapacityKwh(),
                vehicle.getRangeKm(),
                vehicle.getConsumptionKwhPer100km(),
                List.copyOf(vehicle.getConnectorTypes()),
                vehicle.isDefault(),
                vehicle.getCreatedAt(),
                vehicle.getUpdatedAt()
        );
    }

    /**
     * Reads the stored JSONB into the typed preferences record.
     *
     * <p>Rows written by an older schema version may not deserialize cleanly.
     * Falling back to defaults keeps the profile readable instead of failing the
     * whole request over a stale settings blob.
     */
    public UserPreferences toPreferences(Map<String, Object> stored) {
        if (stored == null || stored.isEmpty()) {
            return UserPreferences.defaults();
        }
        try {
            UserPreferences preferences = objectMapper.convertValue(stored, UserPreferences.class);
            return preferences == null ? UserPreferences.defaults() : preferences;
        } catch (IllegalArgumentException exception) {
            log.warn("Stored preferences could not be parsed; returning defaults", exception);
            return UserPreferences.defaults();
        }
    }

    public Map<String, Object> toMap(UserPreferences preferences) {
        return objectMapper.convertValue(preferences, new TypeReference<>() {
        });
    }
}
