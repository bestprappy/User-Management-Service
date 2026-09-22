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

    @Test void legacyConsumptionIsPreservedWithoutWritingOrInferringProvenance() {
        var saved = vehicle();
        var value = new BigDecimal("17.537");
        saved.setConsumptionKwhPer100km(value);
        saved.getMetadata().put("catalog", mapper.toCatalogMetadata(catalog.requireVehicle(CATALOG_ID)));
        var response = mapper.toVehicle(saved);
        assertThat(response.consumptionKwhPer100km()).isSameAs(value);
        assertThat(response.energyProfile().consumptionKwhPer100km()).isSameAs(value);
        assertThat(response.energyProfile().selectionMode().name()).isEqualTo("LEGACY_UNCONFIRMED");
        assertThat(response.energyProfile().consumptionSource().name()).isEqualTo("UNKNOWN");
        assertThat(response.energyProfile().consumptionStandard().name()).isEqualTo("NONE");
        assertThat(response.energyProfile().sourceUrl()).isNull();
        assertThat(saved.getMetadata()).doesNotContainKey("energyProfile");
    }

    @Test void catalogueEvidenceDoesNotBecomeConsumptionEvidence() {
        var car = catalog.requireVehicle(CATALOG_ID);
        var profile = car.energyProfile();
        assertThat(profile.ratedRangeKm()).isEqualByComparingTo(car.rangeKm());
        assertThat(profile.ratedRangeStandard().name()).isEqualTo("NEDC");
        assertThat(profile.consumptionStandard().name()).isEqualTo("NONE");
        assertThat(profile.capacityBasis().name()).isEqualTo("MANUFACTURER_DECLARED_UNSPECIFIED");
        assertThat(profile.usableBatteryCapacityKwh()).isNull();
        assertThat(profile.consumptionKwhPer100km()).isNull();
        assertThat(profile.sourceUrl()).isNull();
        assertThat(car.sourceUrl()).isNotBlank();
    }

    @Test void observedConsumptionRoundTripsAndUnrelatedUpdatesPreserveIt() throws Exception {
        var saved = vehicle();
        saved.getMetadata().put("otherFeature", java.util.Map.of("enabled", true));
        when(vehicles.findByIdAndUserIdAndDeletedAtIsNull(ID, OWNER)).thenReturn(Optional.of(saved));
        when(vehicles.save(saved)).thenReturn(saved);
        var json = new JacksonConfiguration().objectMapper();
        var request = json.readValue("""
                {"consumptionKwhPer100km":18.125,"consumptionProvenance":{"consumptionSource":"USER_OBSERVED"}}
                """, UpdateVehicleRequest.class);
        service.updateVehicle(caller, ID, request);
        // Exercise the JSON representation used by the existing JSONB field.
        saved.setMetadata(json.readValue(json.writeValueAsString(saved.getMetadata()),
                new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {}));
        var response = service.updateVehicle(caller, ID, patch(null, new VehicleSettings(null, null, 40, null)));
        assertThat(response.consumptionKwhPer100km()).isEqualByComparingTo("18.125");
        assertThat(response.energyProfile().consumptionSource().name()).isEqualTo("USER_OBSERVED");
        assertThat(response.energyProfile().consumptionMeasurementBasis().name()).isEqualTo("UNKNOWN");
        assertThat(response.energyProfile().selectionMode().name()).isEqualTo("USER_OVERRIDE");
        assertThat(saved.getMetadata()).containsKey("otherFeature");
        var reloaded = json.readValue(json.writeValueAsString(response), com.navio.usermanagementservice.dto.VehicleResponse.class);
        assertThat(reloaded.energyProfile()).isEqualTo(response.energyProfile());
        // Old payloads still work, but cannot keep a stale source claim.
        service.updateVehicle(caller, ID, json.readValue("{\"consumptionKwhPer100km\":19.25}", UpdateVehicleRequest.class));
        assertThat(mapper.toVehicle(saved).energyProfile().consumptionSource().name()).isEqualTo("UNKNOWN");
        assertThat(saved.getConsumptionKwhPer100km()).isEqualByComparingTo("19.25");
        assertThat(saved.getMetadata()).containsKey("otherFeature");
    }

    @Test void clientCannotClaimAuthoritativeProvenance() {
        var json = new JacksonConfiguration().objectMapper();
        assertThatThrownBy(() -> json.readValue("""
                {"consumptionKwhPer100km":18,"consumptionProvenance":{"consumptionSource":"MANUFACTURER_REPORTED"}}
                """, UpdateVehicleRequest.class)).isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }

    @Test void provenanceWithoutConsumptionIsRejected() throws Exception {
        var saved = vehicle();
        when(vehicles.findByIdAndUserIdAndDeletedAtIsNull(ID, OWNER)).thenReturn(Optional.of(saved));
        var request = new JacksonConfiguration().objectMapper().readValue("""
                {"consumptionProvenance":{"consumptionSource":"USER_OBSERVED"}}
                """, UpdateVehicleRequest.class);
        assertThatThrownBy(() -> service.updateVehicle(caller, ID, request)).isInstanceOf(BusinessRuleException.class);
        verify(vehicles, never()).save(any());
    }

    @Test void explicitDefaultAndResetKeepRangeWithoutInventingConsumption() throws Exception {
        var saved = vehicle();
        saved.setConsumptionKwhPer100km(new BigDecimal("17.537"));
        saved.getMetadata().put("catalog", mapper.toCatalogMetadata(catalog.requireVehicle(CATALOG_ID)));
        saved.getMetadata().put("otherFeature", "preserved");
        when(vehicles.findByIdAndUserIdAndDeletedAtIsNull(ID, OWNER)).thenReturn(Optional.of(saved));
        when(vehicles.save(saved)).thenReturn(saved);
        var json = new JacksonConfiguration().objectMapper();
        var reset = json.readValue("{\"energySelection\":\"RESET_DEFAULT\"}", UpdateVehicleRequest.class);
        var response = service.updateVehicle(caller, ID, reset);
        assertThat(response.consumptionKwhPer100km()).isNull();
        assertThat(response.energyProfile().modelKind().name()).isEqualTo("RATED_RANGE");
        assertThat(response.energyProfile().selectionMode().name()).isEqualTo("CATALOG_DEFAULT");
        assertThat(response.energyProfile().ratedRangeStandard().name()).isEqualTo("NEDC");
        assertThat(response.energyProfile().consumptionStandard().name()).isEqualTo("NONE");
        assertThat(response.energyProfile().usableBatteryCapacityKwh()).isNull();
        saved.setMetadata(json.readValue(json.writeValueAsString(saved.getMetadata()),
                new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {}));
        assertThat(service.getMyVehicle(caller, ID).energyProfile()).isEqualTo(response.energyProfile());
        assertThat(saved.getMetadata()).containsEntry("otherFeature", "preserved");
        var estimate = json.readValue("{\"energySelection\":\"USE_RATED_RANGE\"}", UpdateVehicleRequest.class);
        response = service.updateVehicle(caller, ID, estimate);
        assertThat(response.energyProfile().modelKind().name()).isEqualTo("RATED_RANGE");
        assertThat(response.consumptionKwhPer100km()).isNull();
        assertThat(service.getMyVehicle(caller, ID).energyProfile()).isEqualTo(response.energyProfile());
        var override = json.readValue("{\"energySelection\":\"USER_OVERRIDE\",\"consumptionKwhPer100km\":16.123}", UpdateVehicleRequest.class);
        response = service.updateVehicle(caller, ID, override);
        assertThat(response.consumptionKwhPer100km()).isEqualByComparingTo("16.123");
        assertThat(response.energyProfile().consumptionSource().name()).isEqualTo("USER_OBSERVED");
        assertThat(response.energyProfile().consumptionMeasurementBasis().name()).isEqualTo("UNKNOWN");
        assertThat(service.updateVehicle(caller, ID, reset).consumptionKwhPer100km()).isNull();
    }

    @Test void legacyConfirmationPreservesValueAndUnknownProvenanceAndIsInvalidatedByEdits() throws Exception {
        var saved = vehicle();
        saved.setConsumptionKwhPer100km(new BigDecimal("17.537"));
        when(vehicles.findByIdAndUserIdAndDeletedAtIsNull(ID, OWNER)).thenReturn(Optional.of(saved));
        when(vehicles.save(saved)).thenReturn(saved);
        var json = new JacksonConfiguration().objectMapper();
        var response = service.updateVehicle(caller, ID,
                json.readValue("{\"energySelection\":\"CONFIRM_LEGACY\"}", UpdateVehicleRequest.class));
        assertThat(response.legacyConsumptionConfirmed()).isTrue();
        assertThat(response.consumptionKwhPer100km()).isEqualByComparingTo("17.537");
        assertThat(response.energyProfile().consumptionSource().name()).isEqualTo("UNKNOWN");
        assertThat(service.getMyVehicle(caller, ID).legacyConsumptionConfirmed()).isTrue();
        response = service.updateVehicle(caller, ID, json.readValue("{\"consumptionKwhPer100km\":18}", UpdateVehicleRequest.class));
        assertThat(response.legacyConsumptionConfirmed()).isFalse();
        assertThatThrownBy(() -> service.updateVehicle(caller, ID, json.readValue(
                "{\"energySelection\":\"RESET_DEFAULT\",\"consumptionKwhPer100km\":19}", UpdateVehicleRequest.class)))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test void directDefaultRequiresConsumptionEvidenceAndBatterySideBasis() throws Exception {
        var json = new JacksonConfiguration().objectMapper();
        var tree = json.valueToTree(catalog.requireVehicle(CATALOG_ID));
        var profile = (com.fasterxml.jackson.databind.node.ObjectNode) tree.get("energyProfile");
        profile.put("modelKind", "CONSUMPTION");
        profile.put("consumptionKwhPer100km", 15.2);
        profile.put("consumptionSource", "MANUFACTURER_REPORTED");
        profile.put("consumptionMeasurementBasis", "BATTERY_SIDE");
        profile.put("sourceUrl", "https://example.com/consumption-specification");
        var direct = json.treeToValue(tree, com.navio.usermanagementservice.dto.VehicleCatalogResponse.class);
        assertThat(com.navio.usermanagementservice.dto.VehicleEnergyProfile.defaultFor(direct, null).consumptionKwhPer100km()).isEqualByComparingTo("15.2");
        var estimate = com.navio.usermanagementservice.dto.VehicleEnergyProfile.defaultFor(direct, null, true);
        assertThat(estimate.modelKind().name()).isEqualTo("RATED_RANGE");
        assertThat(estimate.consumptionKwhPer100km()).isNull();
        assertThat(estimate.sourceUrl()).isNull();
        assertThat(estimate.consumptionStandard().name()).isEqualTo("NONE");
        profile.put("consumptionMeasurementBasis", "WALL_SIDE");
        assertThat(com.navio.usermanagementservice.dto.VehicleEnergyProfile.defaultFor(
                json.treeToValue(tree, com.navio.usermanagementservice.dto.VehicleCatalogResponse.class), null).consumptionKwhPer100km()).isNull();
        profile.put("consumptionMeasurementBasis", "BATTERY_SIDE");
        profile.putNull("sourceUrl");
        assertThat(com.navio.usermanagementservice.dto.VehicleEnergyProfile.defaultFor(
                json.treeToValue(tree, com.navio.usermanagementservice.dto.VehicleCatalogResponse.class), null).modelKind().name()).isEqualTo("RATED_RANGE");
        assertThat(com.navio.usermanagementservice.dto.VehicleEnergyProfile.defaultFor(null, null).modelKind().name()).isEqualTo("UNAVAILABLE");
    }

    @Test void catalogueDefaultCanBeSavedWithoutConsumption() throws Exception {
        UserVehicle[] stored = new UserVehicle[1];
        when(vehicles.save(any())).thenAnswer(call -> {
            UserVehicle v = call.getArgument(0); v.setId(ID); stored[0] = v; return v;
        });
        when(vehicles.findByIdAndUserIdAndDeletedAtIsNull(ID, OWNER)).thenAnswer(call -> Optional.of(stored[0]));
        var request = new JacksonConfiguration().objectMapper().readValue(
                "{\"energySelection\":\"USE_DEFAULT\",\"startingBatteryPct\":80}", AddCatalogVehicleRequest.class);
        var response = service.addCatalogVehicle(caller, CATALOG_ID, request);
        assertThat(response.consumptionKwhPer100km()).isNull();
        assertThat(response.energyProfile().modelKind().name()).isEqualTo("RATED_RANGE");
        assertThat(service.getMyVehicle(caller, ID).energyProfile()).isEqualTo(response.energyProfile());
    }

    private UserVehicle vehicle() {
        return UserVehicle.builder().id(ID).userId(OWNER).make("BYD").model("ATTO 3")
                .batteryCapacityKwh(new BigDecimal("60.48")).rangeKm(new BigDecimal("480"))
                .connectorTypes(List.of("CCS2", "TYPE2")).build();
    }
}
