package com.navio.usermanagementservice.service;

import com.navio.usermanagementservice.dto.VehicleRequests.CreateVehicleRequest;
import com.navio.usermanagementservice.dto.VehicleRequests.UpdateVehicleRequest;
import com.navio.usermanagementservice.dto.VehicleResponse;
import com.navio.usermanagementservice.dto.VehicleRequests.AddCatalogVehicleRequest;
import com.navio.usermanagementservice.dto.VehicleSettings;
import com.navio.usermanagementservice.repository.UserRepository;
import com.navio.usermanagementservice.exception.UserManagementExceptions.BusinessRuleException;
import com.navio.usermanagementservice.exception.UserManagementExceptions.VehicleNotFoundException;
import com.navio.usermanagementservice.model.UserVehicle;
import com.navio.usermanagementservice.repository.UserVehicleRepository;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Set;

/**
 * The user's saved-vehicle garage.
 *
 * <p>Every operation resolves the vehicle with a query scoped to
 * {@code caller.id()}. Nothing here loads a vehicle by id alone and checks
 * ownership afterwards, so a request for another user's vehicle id returns 404
 * from the query itself rather than relying on a follow-up check somebody could
 * forget to write.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserVehicleService {

    /**
     * Upper bound on garage size.
     *
     * <p>Without a cap, an authenticated caller could insert rows indefinitely —
     * a cheap way to exhaust storage on a shared database.
     */
    private static final long MAX_VEHICLES_PER_USER = 25;

    /** Connector identifiers are restricted to a safe character set. */
    private static final Set<String> CONNECTOR_TYPES = Set.of("CCS1", "CCS2", "TYPE2", "J1772", "CHADEMO", "NACS", "GB_T");

    private final UserVehicleRepository vehicleRepository;
    private final UserMapper userMapper;
    private final AuditService auditService;
    private final UserRepository userRepository;
    private final VehicleCatalogService catalogService;

    public List<VehicleResponse> listMyVehicles(AuthenticatedUser caller) {
        return vehicleRepository
                .findByUserIdAndDeletedAtIsNullOrderByIsDefaultDescCreatedAtAsc(caller.id())
                .stream()
                .map(userMapper::toVehicle)
                .toList();
    }

    public VehicleResponse getMyVehicle(AuthenticatedUser caller, UUID vehicleId) {
        return userMapper.toVehicle(requireOwnedVehicle(caller, vehicleId));
    }

    @Transactional
    public VehicleResponse addVehicle(AuthenticatedUser caller, CreateVehicleRequest request) {
        userRepository.lockGarage(caller.id());
        long existing = vehicleRepository.countByUserIdAndDeletedAtIsNull(caller.id());
        if (existing >= MAX_VEHICLES_PER_USER) {
            throw new BusinessRuleException(
                    "You can save at most " + MAX_VEHICLES_PER_USER + " vehicles. Remove one and try again.");
        }

        boolean makeDefault = Boolean.TRUE.equals(request.isDefault()) || existing == 0;

        UserVehicle vehicle = UserVehicle.builder()
                .userId(caller.id())
                .nickname(trimToNull(request.nickname()))
                .make(request.make().trim())
                .model(request.model().trim())
                .year(request.year())
                .batteryCapacityKwh(request.batteryCapacityKwh())
                .rangeKm(request.rangeKm())
                .consumptionKwhPer100km(request.consumptionKwhPer100km())
                .connectorTypes(normalizeConnectors(request.connectorTypes()))
                .isDefault(makeDefault)
                .metadata(new HashMap<>())
                .build();

        applySettings(vehicle, request.settings());

        if (makeDefault) {
            // Clear any prior default first so the partial unique index
            // uq_iam_user_vehicles_user_default is never violated.
            vehicleRepository.clearDefaultForUser(caller.id(), null);
        }

        UserVehicle saved = vehicleRepository.save(vehicle);
        auditService.record(caller.id(), AuditAction.USER_VEHICLE_CREATED,
                AuditAction.RESOURCE_VEHICLE, saved.getId(), Map.of());

        return userMapper.toVehicle(saved);
    }

    @Transactional
    public VehicleResponse addCatalogVehicle(AuthenticatedUser caller, String catalogId,
                                             AddCatalogVehicleRequest request) {
        userRepository.lockGarage(caller.id());
        var catalog = catalogService.requireVehicle(catalogId);
        var existing = vehicleRepository.findByUserIdAndDeletedAtIsNullOrderByIsDefaultDescCreatedAtAsc(caller.id())
                .stream().filter(vehicle -> {
                    Object snapshot = vehicle.getMetadata().get("catalog");
                    return snapshot instanceof Map<?, ?> fields && catalogId.equals(fields.get("id"));
                }).findFirst();
        if (existing.isPresent()) {
            return updateVehicle(caller, existing.get().getId(), new UpdateVehicleRequest(
                    request.nickname(), null, null, null, null, null, request.consumptionKwhPer100km(),
                    null, true, new VehicleSettings(null, null, request.startingBatteryPct(), null)));
        }
        // Specifications are always resolved on the server, never copied from browser input.
        var response = addVehicle(caller, new CreateVehicleRequest(request.nickname(), catalog.make(),
                catalog.model(), catalog.year(), catalog.batteryCapacityKwh(), catalog.rangeKm(),
                request.consumptionKwhPer100km(), catalog.connectorTypes(), true,
                new VehicleSettings(catalog.maxAcKw(), catalog.maxDcKw(), request.startingBatteryPct(), catalog.imageUrl())));
        var vehicle = requireOwnedVehicle(caller, response.id());
        vehicle.getMetadata().put("catalog", userMapper.toCatalogMetadata(catalog));
        return userMapper.toVehicle(vehicleRepository.save(vehicle));
    }

    @Transactional
    public VehicleResponse updateVehicle(AuthenticatedUser caller, UUID vehicleId, UpdateVehicleRequest request) {
        userRepository.lockGarage(caller.id());
        UserVehicle vehicle = requireOwnedVehicle(caller, vehicleId);
        Map<String, Object> before = vehicleSnapshot(vehicle);

        if ((request.make() != null && request.make().isBlank()) ||
                (request.model() != null && request.model().isBlank())) {
            throw new BusinessRuleException("Make and model must not be blank");
        }
        boolean changesSpecifications = request.make() != null || request.model() != null || request.year() != null
                || request.batteryCapacityKwh() != null || request.rangeKm() != null || request.connectorTypes() != null
                || (request.settings() != null && (request.settings().maxAcKw() != null || request.settings().maxDcKw() != null));
        if (changesSpecifications) {
            // An edited specification becomes a custom vehicle; retain no claim of official verification.
            vehicle.getMetadata().remove("catalog");
        }
        applySettings(vehicle, request.settings());

        if (request.nickname() != null) {
            vehicle.setNickname(trimToNull(request.nickname()));
        }
        if (request.make() != null) {
            vehicle.setMake(request.make().trim());
        }
        if (request.model() != null) {
            vehicle.setModel(request.model().trim());
        }
        if (request.year() != null) {
            vehicle.setYear(request.year());
        }
        if (request.batteryCapacityKwh() != null) {
            vehicle.setBatteryCapacityKwh(request.batteryCapacityKwh());
        }
        if (request.rangeKm() != null) {
            vehicle.setRangeKm(request.rangeKm());
        }
        if (request.consumptionKwhPer100km() != null) {
            vehicle.setConsumptionKwhPer100km(request.consumptionKwhPer100km());
        }
        if (request.connectorTypes() != null) {
            vehicle.setConnectorTypes(normalizeConnectors(request.connectorTypes()));
        }

        if (Boolean.TRUE.equals(request.isDefault()) && !vehicle.isDefault()) {
            vehicleRepository.clearDefaultForUser(caller.id(), vehicle.getId());
            vehicle.setDefault(true);
        } else if (Boolean.FALSE.equals(request.isDefault()) && vehicle.isDefault()) {
            vehicle.setDefault(false);
        }

        UserVehicle saved = vehicleRepository.save(vehicle);
        auditService.record(caller.id(), AuditAction.USER_VEHICLE_UPDATED,
                AuditAction.RESOURCE_VEHICLE, saved.getId(),
                before, vehicleSnapshot(saved), Map.of());

        return userMapper.toVehicle(saved);
    }

    /**
     * Soft-deletes a vehicle.
     *
     * <p>The row is retained because trips already reference a copy of it and
     * audit entries point at its id; a hard delete would break both.
     */
    @Transactional
    public void deleteVehicle(AuthenticatedUser caller, UUID vehicleId) {
        userRepository.lockGarage(caller.id());
        UserVehicle vehicle = requireOwnedVehicle(caller, vehicleId);
        boolean wasDefault = vehicle.isDefault();
        vehicle.setDeletedAt(Instant.now());
        // Release the default slot, otherwise the partial unique index would
        // keep a deleted row occupying it.
        vehicle.setDefault(false);
        vehicleRepository.saveAndFlush(vehicle);

        if (wasDefault) {
            vehicleRepository.findByUserIdAndDeletedAtIsNullOrderByIsDefaultDescCreatedAtAsc(caller.id())
                    .stream().findFirst().ifPresent(next -> {
                        next.setDefault(true);
                        vehicleRepository.save(next);
                    });
        }

        auditService.record(caller.id(), AuditAction.USER_VEHICLE_DELETED,
                AuditAction.RESOURCE_VEHICLE, vehicle.getId(), Map.of());
    }

    /**
     * Loads a vehicle that belongs to the caller.
     *
     * @throws VehicleNotFoundException when it does not exist <em>or</em> belongs
     *                                  to someone else — the two are deliberately
     *                                  indistinguishable to the client.
     */
    private UserVehicle requireOwnedVehicle(AuthenticatedUser caller, UUID vehicleId) {
        return vehicleRepository.findByIdAndUserIdAndDeletedAtIsNull(vehicleId, caller.id())
                .orElseThrow(() -> new VehicleNotFoundException(vehicleId));
    }

    /**
     * Trims, upper-cases, de-duplicates and validates connector identifiers.
     *
     * <p>These strings are stored in a {@code TEXT[]} column and later rendered by
     * the client and forwarded to the mobility service, so the character set is
     * restricted rather than accepting arbitrary text.
     */
    private List<String> normalizeConnectors(List<String> connectors) {
        if (connectors == null) {
            return new ArrayList<>();
        }
        List<String> normalized = connectors.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();

        if (normalized.isEmpty()) {
            throw new BusinessRuleException("Choose at least one connector type");
        }

        for (String connector : normalized) {
            if (!CONNECTOR_TYPES.contains(connector)) {
                throw new BusinessRuleException(
                        "Choose a supported EV connector type");
            }
        }
        return normalized;
    }

    private void applySettings(UserVehicle vehicle, VehicleSettings settings) {
        if (settings == null) return;
        if (settings.maxAcKw() != null) vehicle.setMaxAcKw(settings.maxAcKw());
        if (settings.maxDcKw() != null) vehicle.setMaxDcKw(settings.maxDcKw());
        if (settings.startingBatteryPct() != null) vehicle.setStartingBatteryPct(settings.startingBatteryPct());
        if (settings.imageUrl() != null) {
            String imageUrl = trimToNull(settings.imageUrl());
            if (imageUrl != null && !imageUrl.matches("^/images/vehicles/[a-z0-9-]+\\.(png|webp)$")) {
                try {
                    URI uri = URI.create(imageUrl);
                    if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
                        throw new IllegalArgumentException("Invalid image URL");
                    }
                } catch (IllegalArgumentException exception) {
                    throw new BusinessRuleException("Use a valid HTTPS image URL");
                }
            }
            vehicle.setImageUrl(imageUrl);
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Map<String, Object> vehicleSnapshot(UserVehicle vehicle) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("nickname", vehicle.getNickname());
        snapshot.put("make", vehicle.getMake());
        snapshot.put("model", vehicle.getModel());
        snapshot.put("year", vehicle.getYear());
        snapshot.put("batteryCapacityKwh", vehicle.getBatteryCapacityKwh());
        snapshot.put("rangeKm", vehicle.getRangeKm());
        snapshot.put("isDefault", vehicle.isDefault());
        snapshot.put("startingBatteryPct", vehicle.getStartingBatteryPct());
        snapshot.put("maxAcKw", vehicle.getMaxAcKw());
        snapshot.put("maxDcKw", vehicle.getMaxDcKw());
        return snapshot;
    }
}
