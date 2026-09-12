package com.navio.usermanagementservice.service;

import com.navio.usermanagementservice.config.JacksonConfiguration;
import com.navio.usermanagementservice.dto.VehicleRequests.AddCatalogVehicleRequest;
import com.navio.usermanagementservice.dto.VehicleRequests.UpdateVehicleRequest;
import com.navio.usermanagementservice.dto.VehicleSettings;
import com.navio.usermanagementservice.exception.UserManagementExceptions.BusinessRuleException;
import com.navio.usermanagementservice.model.UserVehicle;
import com.navio.usermanagementservice.repository.UserRepository;
import com.navio.usermanagementservice.repository.UserVehicleRepository;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VehicleGarageTests {
    private static final UUID OWNER = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final String CATALOG_ID = "th-byd-atto-3-extended-2026";
    @Mock UserVehicleRepository vehicles;
    @Mock UserRepository users;
    @Mock AuditService audit;
    private UserVehicleService service;
    private VehicleCatalogService catalog;
    private UserMapper mapper;
    private final AuthenticatedUser caller = new AuthenticatedUser(OWNER, "subject", "test@example.com", "Test", Set.of());

    @BeforeEach void setUp() throws Exception {
        var json = new JacksonConfiguration().objectMapper();
        catalog = new VehicleCatalogService(json);
        mapper = new UserMapper(json);
        service = new UserVehicleService(vehicles, mapper, audit, users, catalog);
    }

    @Test void catalogueKeepsThailandRangeStandardsAndUnknownValuesExplicit() {
        assertThat(catalog.listVehicles()).hasSize(3).allSatisfy(car -> {
            assertThat(car.market()).isEqualTo("TH");
            assertThat(car.rangeStandard()).isEqualTo("NEDC");
            assertThat(car.sourceUrl()).startsWith("https://www.reverautomotive.com/");
        });
        assertThat(catalog.requireVehicle(CATALOG_ID).maxAcKw()).isEqualByComparingTo("7");
        var seal = catalog.requireVehicle("th-byd-seal-premium-82-56");
        assertThat(seal.year()).isNull();
        assertThat(seal.maxAcKw()).isNull();
    }

    @Test void savingCatalogueVehicleStoresOfficialSnapshotAndDriverConsumption() {
        UserVehicle[] saved = new UserVehicle[1];
        when(vehicles.save(any())).thenAnswer(call -> {
            UserVehicle vehicle = call.getArgument(0);
            vehicle.setId(ID);
            saved[0] = vehicle;
            return vehicle;
        });
        when(vehicles.findByIdAndUserIdAndDeletedAtIsNull(ID, OWNER)).thenAnswer(call -> Optional.of(saved[0]));

        var response = service.addCatalogVehicle(caller, CATALOG_ID,
                new AddCatalogVehicleRequest("Daily", new BigDecimal("17.5"), 65));

        assertThat(response.batteryCapacityKwh()).isEqualByComparingTo("60.48");
        assertThat(response.rangeKm()).isEqualByComparingTo("480");
        assertThat(response.consumptionKwhPer100km()).isEqualByComparingTo("17.5");
        assertThat(response.settings().startingBatteryPct()).isEqualTo(65);
        assertThat(response.catalog().id()).isEqualTo(CATALOG_ID);
        assertThat(response.settings().imageUrl()).isEqualTo("/images/vehicles/byd-atto-3.png");
        assertThat(response.isDefault()).isTrue();
        verify(users, atLeastOnce()).lockGarage(OWNER);
    }

    @Test void repeatedCatalogueSelectionReusesTheSavedVehicle() {
        var saved = vehicle();
        saved.getMetadata().put("catalog", mapper.toCatalogMetadata(catalog.requireVehicle(CATALOG_ID)));
        when(vehicles.findByUserIdAndDeletedAtIsNullOrderByIsDefaultDescCreatedAtAsc(OWNER)).thenReturn(List.of(saved));
        when(vehicles.findByIdAndUserIdAndDeletedAtIsNull(ID, OWNER)).thenReturn(Optional.of(saved));
        when(vehicles.save(saved)).thenReturn(saved);

        var response = service.addCatalogVehicle(caller, CATALOG_ID, new AddCatalogVehicleRequest(null, new BigDecimal("19"), 70));

        assertThat(response.id()).isEqualTo(ID);
        assertThat(response.settings().startingBatteryPct()).isEqualTo(70);
        assertThat(response.catalog().id()).isEqualTo(CATALOG_ID);
        verify(vehicles, never()).countByUserIdAndDeletedAtIsNull(any());
    }

    @Test void selectingAnotherVehicleClearsThePreviousDefaultBeforeSaving() {
        var saved = vehicle();
        when(vehicles.findByIdAndUserIdAndDeletedAtIsNull(ID, OWNER)).thenReturn(Optional.of(saved));
        when(vehicles.save(saved)).thenReturn(saved);
        var response = service.updateVehicle(caller, ID, patch(true, null));
        assertThat(response.isDefault()).isTrue();
        var order = inOrder(users, vehicles);
        order.verify(users).lockGarage(OWNER);
        order.verify(vehicles).findByIdAndUserIdAndDeletedAtIsNull(ID, OWNER);
        order.verify(vehicles).clearDefaultForUser(OWNER, ID);
        order.verify(vehicles).save(saved);
    }

    @Test void deletingDefaultPromotesTheNextVehicleAfterReleasingTheUniqueSlot() {
        var saved = vehicle();
        saved.setDefault(true);
        var next = vehicle();
        next.setId(UUID.fromString("10000000-0000-4000-8000-000000000002"));
        when(vehicles.findByIdAndUserIdAndDeletedAtIsNull(ID, OWNER)).thenReturn(Optional.of(saved));
        when(vehicles.findByUserIdAndDeletedAtIsNullOrderByIsDefaultDescCreatedAtAsc(OWNER)).thenReturn(List.of(next));
        service.deleteVehicle(caller, ID);
        assertThat(saved.getDeletedAt()).isNotNull();
        assertThat(next.isDefault()).isTrue();
        var order = inOrder(vehicles);
        order.verify(vehicles).findByIdAndUserIdAndDeletedAtIsNull(ID, OWNER);
        order.verify(vehicles).saveAndFlush(saved);
        order.verify(vehicles).findByUserIdAndDeletedAtIsNullOrderByIsDefaultDescCreatedAtAsc(OWNER);
        order.verify(vehicles).save(next);
    }

    @Test void updatingBatteryPreservesOtherSettingsAndSourceSnapshot() {
        var saved = vehicle();
        saved.setMaxDcKw(new BigDecimal("88"));
        saved.getMetadata().put("catalog", mapper.toCatalogMetadata(catalog.requireVehicle(CATALOG_ID)));
        when(vehicles.findByIdAndUserIdAndDeletedAtIsNull(ID, OWNER)).thenReturn(Optional.of(saved));
        when(vehicles.save(saved)).thenReturn(saved);
        var response = service.updateVehicle(caller, ID, patch(null, new VehicleSettings(null, null, 0, null)));
        assertThat(response.settings().startingBatteryPct()).isZero();
        assertThat(response.settings().maxDcKw()).isEqualByComparingTo("88");
        assertThat(response.catalog()).isNotNull();
    }

    @Test void editedSpecificationsLoseTheOfficialCatalogueClaim() {
        var saved = vehicle();
        saved.getMetadata().put("catalog", mapper.toCatalogMetadata(catalog.requireVehicle(CATALOG_ID)));
        when(vehicles.findByIdAndUserIdAndDeletedAtIsNull(ID, OWNER)).thenReturn(Optional.of(saved));
        when(vehicles.save(saved)).thenReturn(saved);
        var response = service.updateVehicle(caller, ID, patch(null, new VehicleSettings(new BigDecimal("11"), null, null, null)));
        assertThat(response.catalog()).isNull();
    }

    @Test void unknownCatalogueIdsNeverCreateAVehicle() {
        assertThatThrownBy(() -> service.addCatalogVehicle(caller, "unknown", new AddCatalogVehicleRequest(null, BigDecimal.TEN, 80)))
                .isInstanceOf(BusinessRuleException.class);
        verifyNoInteractions(vehicles, audit);
    }

    private UpdateVehicleRequest patch(Boolean isDefault, VehicleSettings settings) {
        return new UpdateVehicleRequest(null, null, null, null, null, null, null, null, isDefault, settings);
    }

    private UserVehicle vehicle() {
        return UserVehicle.builder().id(ID).userId(OWNER).make("BYD").model("ATTO 3")
                .batteryCapacityKwh(new BigDecimal("60.48")).rangeKm(new BigDecimal("480"))
                .connectorTypes(List.of("CCS2", "TYPE2")).build();
    }
}
