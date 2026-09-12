package com.navio.usermanagementservice.service;

import com.navio.usermanagementservice.dto.VehicleRequests.CreateVehicleRequest;
import com.navio.usermanagementservice.dto.VehicleRequests.UpdateVehicleRequest;
import com.navio.usermanagementservice.exception.UserManagementExceptions.BusinessRuleException;
import com.navio.usermanagementservice.exception.UserManagementExceptions.VehicleNotFoundException;
import com.navio.usermanagementservice.model.UserVehicle;
import com.navio.usermanagementservice.repository.UserVehicleRepository;
import com.navio.usermanagementservice.repository.UserRepository;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import com.navio.usermanagementservice.security.NavioRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ownership scoping is the control that keeps one user's garage out of another
 * user's reach, so these tests assert the repository is always queried with the
 * caller's id rather than with the vehicle id alone.
 */
@ExtendWith(MockitoExtension.class)
class UserVehicleServiceTest {

    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_USER_ID = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID VEHICLE_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");

    @Mock
    private UserVehicleRepository vehicleRepository;

    @Mock
    private AuditService auditService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private VehicleCatalogService catalogService;

    private UserVehicleService service;

    @BeforeEach
    void setUp() {
        service = new UserVehicleService(vehicleRepository, new UserMapper(new ObjectMapper().findAndRegisterModules()), auditService, userRepository, catalogService);
    }

    @Test
    void readsAreScopedToTheCallingUser() {
        when(vehicleRepository.findByIdAndUserIdAndDeletedAtIsNull(VEHICLE_ID, OWNER_ID))
                .thenReturn(Optional.of(vehicle(OWNER_ID)));

        service.getMyVehicle(caller(OWNER_ID), VEHICLE_ID);

        // The caller's id is part of the query, not an afterthought check.
        verify(vehicleRepository).findByIdAndUserIdAndDeletedAtIsNull(VEHICLE_ID, OWNER_ID);
    }

    @Test
    void anotherUsersVehicleIsNotFound() {
        // The repository returns empty because the query is scoped by user id.
        when(vehicleRepository.findByIdAndUserIdAndDeletedAtIsNull(VEHICLE_ID, OTHER_USER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMyVehicle(caller(OTHER_USER_ID), VEHICLE_ID))
                .isInstanceOf(VehicleNotFoundException.class);
    }

    @Test
    void updatingAnotherUsersVehicleIsRefused() {
        when(vehicleRepository.findByIdAndUserIdAndDeletedAtIsNull(VEHICLE_ID, OTHER_USER_ID))
                .thenReturn(Optional.empty());

        UpdateVehicleRequest request = new UpdateVehicleRequest(
                "Stolen", null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.updateVehicle(caller(OTHER_USER_ID), VEHICLE_ID, request))
                .isInstanceOf(VehicleNotFoundException.class);

        verify(vehicleRepository, never()).save(any());
    }

    @Test
    void deletingAnotherUsersVehicleIsRefused() {
        when(vehicleRepository.findByIdAndUserIdAndDeletedAtIsNull(VEHICLE_ID, OTHER_USER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteVehicle(caller(OTHER_USER_ID), VEHICLE_ID))
                .isInstanceOf(VehicleNotFoundException.class);

        verify(vehicleRepository, never()).save(any());
    }

    @Test
    void deleteIsSoftAndReleasesTheDefaultSlot() {
        UserVehicle existing = vehicle(OWNER_ID);
        existing.setDefault(true);
        when(vehicleRepository.findByIdAndUserIdAndDeletedAtIsNull(VEHICLE_ID, OWNER_ID))
                .thenReturn(Optional.of(existing));

        service.deleteVehicle(caller(OWNER_ID), VEHICLE_ID);

        ArgumentCaptor<UserVehicle> saved = ArgumentCaptor.forClass(UserVehicle.class);
        verify(vehicleRepository).saveAndFlush(saved.capture());

        assertThat(saved.getValue().getDeletedAt()).isNotNull();
        // Leaving is_default set would keep the partial unique index occupied by
        // a deleted row, blocking the next default.
        assertThat(saved.getValue().isDefault()).isFalse();
    }

    @Test
    void garageSizeIsCapped() {
        when(vehicleRepository.countByUserIdAndDeletedAtIsNull(OWNER_ID)).thenReturn(25L);

        assertThatThrownBy(() -> service.addVehicle(caller(OWNER_ID), createRequest(List.of("CCS2"))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("at most");

        verify(vehicleRepository, never()).save(any());
    }

    @Test
    void connectorTypesAreNormalisedAndDeduplicated() {
        when(vehicleRepository.countByUserIdAndDeletedAtIsNull(OWNER_ID)).thenReturn(1L);
        when(vehicleRepository.save(any(UserVehicle.class))).thenAnswer(call -> call.getArgument(0));

        service.addVehicle(caller(OWNER_ID), createRequest(List.of(" ccs2 ", "CCS2", "type2")));

        ArgumentCaptor<UserVehicle> saved = ArgumentCaptor.forClass(UserVehicle.class);
        verify(vehicleRepository).save(saved.capture());

        assertThat(saved.getValue().getConnectorTypes()).containsExactly("CCS2", "TYPE2");
    }

    @Test
    void rejectsConnectorTypesOutsideTheAllowedCharacterSet() {
        when(vehicleRepository.countByUserIdAndDeletedAtIsNull(OWNER_ID)).thenReturn(1L);

        assertThatThrownBy(() ->
                service.addVehicle(caller(OWNER_ID), createRequest(List.of("<script>alert(1)</script>"))))
                .isInstanceOf(BusinessRuleException.class);

        verify(vehicleRepository, never()).save(any());
    }

    @Test
    void firstVehicleBecomesTheDefault() {
        when(vehicleRepository.countByUserIdAndDeletedAtIsNull(OWNER_ID)).thenReturn(0L);
        when(vehicleRepository.save(any(UserVehicle.class))).thenAnswer(call -> call.getArgument(0));

        service.addVehicle(caller(OWNER_ID), createRequest(List.of("CCS2")));

        ArgumentCaptor<UserVehicle> saved = ArgumentCaptor.forClass(UserVehicle.class);
        verify(vehicleRepository).save(saved.capture());
        assertThat(saved.getValue().isDefault()).isTrue();
        verify(vehicleRepository).clearDefaultForUser(eq(OWNER_ID), eq(null));
    }

    private CreateVehicleRequest createRequest(List<String> connectors) {
        return new CreateVehicleRequest(
                "Daily", "Tesla", "Model 3", (short) 2023,
                new BigDecimal("57.50"), new BigDecimal("430.00"), new BigDecimal("14.500"),
                connectors, null);
    }

    private UserVehicle vehicle(UUID ownerId) {
        return UserVehicle.builder()
                .id(VEHICLE_ID)
                .userId(ownerId)
                .make("Tesla")
                .model("Model 3")
                .batteryCapacityKwh(new BigDecimal("57.50"))
                .rangeKm(new BigDecimal("430.00"))
                .connectorTypes(new java.util.ArrayList<>(List.of("CCS2")))
                .metadata(new java.util.HashMap<>())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private AuthenticatedUser caller(UUID userId) {
        return new AuthenticatedUser(userId, "sub-" + userId, "user@example.com", "User",
                Set.of(NavioRole.USER));
    }
}
