package com.navio.usermanagementservice.controller;

import com.navio.usermanagementservice.config.JacksonConfiguration;
import com.navio.usermanagementservice.config.SecurityConfig;
import com.navio.usermanagementservice.config.WebMvcConfig;
import com.navio.usermanagementservice.exception.UserManagementExceptions.BusinessRuleException;
import com.navio.usermanagementservice.exception.UserManagementExceptions.VehicleNotFoundException;
import com.navio.usermanagementservice.security.*;
import com.navio.usermanagementservice.service.UserProvisioningService;
import com.navio.usermanagementservice.service.UserVehicleService;
import com.navio.usermanagementservice.service.VehicleCatalogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = UserVehicleController.class, properties = {
        "spring.config.name=vehicle-controller-test", "spring.cloud.config.enabled=false", "spring.cloud.bus.enabled=false",
        "navio.security.keycloak.issuer-uri=https://example.com/realms/test",
        "navio.security.keycloak.audiences=navio-api",
        "navio.security.keycloak.admin.base-url=https://example.com",
        "navio.security.keycloak.admin.realm=test", "navio.security.keycloak.admin.client-id=test",
        "navio.security.keycloak.admin.client-secret=test-only"
})
@Import({SecurityConfig.class, JacksonConfiguration.class, WebMvcConfig.class,
        CurrentUserArgumentResolver.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class})
class UserVehicleControllerTests {
    private static final UUID OWNER = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final String BASE = "/v1/users/me/vehicles";
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean UserVehicleService vehicles;
    @MockitoBean VehicleCatalogService catalog;
    @MockitoBean UserProvisioningService provisioning;
    private final AuthenticatedUser caller = new AuthenticatedUser(OWNER, "subject", "test@example.com", "Test", Set.of());

    @BeforeEach void setUp() {
        lenient().when(provisioning.resolve(any(), anySet())).thenReturn(caller);
    }

    @Test void observedProvenanceIsAcceptedButAuthoritativeClaimsAreRejected() throws Exception {
        mvc.perform(patch(BASE + "/" + ID).with(jwt()).contentType(MediaType.APPLICATION_JSON).content("""
                {"consumptionKwhPer100km":18.125,"consumptionProvenance":{"consumptionSource":"USER_OBSERVED","consumptionMeasurementBasis":"UNKNOWN"}}
                """)).andExpect(status().isOk());
        verify(vehicles).updateVehicle(eq(caller), eq(ID), argThat(request ->
                request.consumptionProvenance() != null
                && request.consumptionProvenance().consumptionSource().name().equals("USER_OBSERVED")));
        for (String source : java.util.List.of("MANUFACTURER_REPORTED", "REGULATORY_REPORTED")) {
            mvc.perform(patch(BASE + "/" + ID).with(jwt()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"consumptionKwhPer100km\":18,\"consumptionProvenance\":{\"consumptionSource\":\"" + source + "\"}}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test void anonymousRequestsCannotReadOrMutateTheGarage() throws Exception {
        for (var request : java.util.List.of(get(BASE), get(BASE + "/" + ID), get("/v1/users/" + OWNER + "/vehicles"), get("/v1/users/me"), post(BASE),
                post(BASE + "/catalog"), patch(BASE + "/catalog"), delete(BASE + "/catalog"),
                post(BASE + "/catalog/example"), patch(BASE + "/" + ID), delete(BASE + "/" + ID))) {
            mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
        }
        verifyNoInteractions(vehicles, catalog, provisioning);
    }

    @Test void catalogueLiteralWinsOverTheVehicleIdRoute() throws Exception {
        var realCatalog = new VehicleCatalogService(new JacksonConfiguration().objectMapper());
        when(catalog.listVehicles()).thenReturn(realCatalog.listVehicles());
        mvc.perform(get(BASE + "/catalog").with(jwt().jwt(token -> token.subject(OWNER.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].market").value("TH"))
                .andExpect(jsonPath("$[0].rangeStandard").value("NEDC"))
                .andExpect(jsonPath("$[0].verifiedAt").value("2026-09-12"));
        verify(catalog).listVehicles();
        verifyNoInteractions(vehicles);
    }

    @Test void anonymousCatalogueReadReturnsOnlyPublicSpecifications() throws Exception {
        var realCatalog = new VehicleCatalogService(new JacksonConfiguration().objectMapper());
        when(catalog.listVehicles()).thenReturn(realCatalog.listVehicles());
        mvc.perform(get(BASE + "/catalog"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value("th-byd-atto-3-extended-2026"))
                .andExpect(jsonPath("$[0].energyProfile.ratedRangeStandard").value("NEDC"))
                .andExpect(jsonPath("$[0].userId").doesNotExist())
                .andExpect(jsonPath("$[0].nickname").doesNotExist());
        verifyNoInteractions(vehicles, provisioning);
    }

    @Test void invalidStartingBatteryIsRejectedBeforeMutation() throws Exception {
        mvc.perform(patch(BASE + "/" + ID).with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settings\":{\"startingBatteryPct\":101}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.validationErrors['settings.startingBatteryPct']").exists());
        verifyNoInteractions(vehicles);
    }

    @Test void savedVehicleResponsesIncludeChargingSettingsWithoutExposingTheOwnerId() throws Exception {
        var vehicle = com.navio.usermanagementservice.model.UserVehicle.builder().id(ID).userId(OWNER)
                .make("BYD").model("ATTO 3").batteryCapacityKwh(new java.math.BigDecimal("60.48"))
                .rangeKm(new java.math.BigDecimal("480")).consumptionKwhPer100km(new java.math.BigDecimal("17.5"))
                .connectorTypes(java.util.List.of("TYPE2", "CCS2")).maxAcKw(new java.math.BigDecimal("7"))
                .maxDcKw(new java.math.BigDecimal("88")).startingBatteryPct(65).isDefault(true)
                .imageUrl("/images/vehicles/byd-atto-3.png").createdAt(java.time.Instant.parse("2026-09-12T00:00:00Z"))
                .updatedAt(java.time.Instant.parse("2026-09-12T00:00:00Z")).build();
        var response = new com.navio.usermanagementservice.service.UserMapper(new JacksonConfiguration().objectMapper()).toVehicle(vehicle);
        when(vehicles.addVehicle(eq(caller), any())).thenReturn(response);
        mvc.perform(post(BASE).with(jwt()).contentType(MediaType.APPLICATION_JSON).content("""
                {"make":"BYD","model":"ATTO 3","batteryCapacityKwh":60.48,"rangeKm":480,
                 "consumptionKwhPer100km":17.5,"connectorTypes":["TYPE2","CCS2"],
                 "settings":{"maxAcKw":7,"maxDcKw":88,"startingBatteryPct":65}}
                """))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.settings.startingBatteryPct").value(65))
                .andExpect(jsonPath("$.settings.maxAcKw").value(7))
                .andExpect(jsonPath("$.settings.imageUrl").value("/images/vehicles/byd-atto-3.png"))
                .andExpect(jsonPath("$.createdAt").value("2026-09-12T00:00:00Z"))
                .andExpect(jsonPath("$.isDefault").value(true)).andExpect(jsonPath("$.userId").doesNotExist());
    }

    @Test void unsafeImageUrlsAreRejectedBeforeMutation() throws Exception {
        mvc.perform(patch(BASE + "/" + ID).with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settings\":{\"imageUrl\":\"javascript:alert(1)\"}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.validationErrors['settings.imageUrl']").exists());
        verifyNoInteractions(vehicles);
    }

    @Test void catalogueSelectionRequiresConsumptionOrExplicitDefault() throws Exception {
        mvc.perform(post(BASE + "/catalog/th-byd-atto-3-extended-2026").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.validationErrors.consumptionSelectionValid").exists());
        verifyNoInteractions(vehicles);
    }

    @Test void explicitCatalogueDefaultDoesNotRequireConsumption() throws Exception {
        mvc.perform(post(BASE + "/catalog/th-byd-atto-3-extended-2026").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"energySelection\":\"USE_DEFAULT\"}"))
                .andExpect(status().isCreated());
        verify(vehicles).addCatalogVehicle(eq(caller), eq("th-byd-atto-3-extended-2026"),
                argThat(request -> request.consumptionKwhPer100km() == null
                        && request.energySelection() == com.navio.usermanagementservice.dto.VehicleRequests.EnergySelection.USE_DEFAULT));
    }

    @Test void unknownEnergySelectionIsRejectedBeforeMutation() throws Exception {
        mvc.perform(patch(BASE + "/" + ID).with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"energySelection\":\"TRUST_EVERYTHING\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(vehicles);
    }

    @Test void consumptionCannotRoundDownToZeroWhenPersisted() throws Exception {
        mvc.perform(post(BASE + "/catalog/th-byd-atto-3-extended-2026").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"consumptionKwhPer100km\":0.0001}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.validationErrors.consumptionKwhPer100km").exists());
        verifyNoInteractions(vehicles);
    }

    @Test void anotherUsersVehicleReturnsTheSameNotFoundContract() throws Exception {
        when(vehicles.getMyVehicle(caller, ID)).thenThrow(new VehicleNotFoundException(ID));
        mvc.perform(get(BASE + "/" + ID).with(jwt()).header("X-User-Id", ID.toString()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Vehicle not found"));
        verify(vehicles).getMyVehicle(caller, ID);
    }

    @Test void unavailableCatalogueSelectionReturnsAUsefulValidationError() throws Exception {
        when(vehicles.addCatalogVehicle(eq(caller), eq("missing"), any()))
                .thenThrow(new BusinessRuleException("Refresh and choose again."));
        mvc.perform(post(BASE + "/catalog/missing").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"consumptionKwhPer100km\":17.5,\"startingBatteryPct\":80}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.message").value("Refresh and choose again."));
    }
}
