package com.navio.usermanagementservice.controller;

import com.navio.usermanagementservice.dto.VehicleRequests.CreateVehicleRequest;
import com.navio.usermanagementservice.dto.VehicleRequests.UpdateVehicleRequest;
import com.navio.usermanagementservice.dto.VehicleResponse;
import com.navio.usermanagementservice.dto.VehicleCatalogResponse;
import com.navio.usermanagementservice.dto.VehicleRequests.AddCatalogVehicleRequest;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import com.navio.usermanagementservice.security.CurrentUser;
import com.navio.usermanagementservice.service.UserVehicleService;
import com.navio.usermanagementservice.service.VehicleCatalogService;
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
 * The caller's saved-vehicle garage.
 *
 * <p>The route is {@code /me/vehicles}, never {@code /{userId}/vehicles}. With
 * no user id in the path there is nothing for a caller to tamper with, so the
 * endpoint cannot be pointed at someone else's garage — the ownership scope is
 * structural rather than a check that could be omitted.
 */
@RestController
@RequestMapping("/v1/users/me/vehicles")
@RequiredArgsConstructor
public class UserVehicleController {

    private final UserVehicleService userVehicleService;
    private final VehicleCatalogService vehicleCatalogService;

    @GetMapping("/catalog")
    public ResponseEntity<List<VehicleCatalogResponse>> listCatalog(@CurrentUser AuthenticatedUser caller) {
        return ResponseEntity.ok(vehicleCatalogService.listVehicles());
    }

    @PostMapping("/catalog/{catalogId}")
    public ResponseEntity<VehicleResponse> addCatalogVehicle(
            @CurrentUser AuthenticatedUser caller,
            @PathVariable String catalogId,
            @Valid @RequestBody AddCatalogVehicleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(userVehicleService.addCatalogVehicle(caller, catalogId, request));
    }

    @GetMapping
    public ResponseEntity<List<VehicleResponse>> listVehicles(@CurrentUser AuthenticatedUser caller) {
        return ResponseEntity.ok(userVehicleService.listMyVehicles(caller));
    }

    @GetMapping("/{vehicleId}")
    public ResponseEntity<VehicleResponse> getVehicle(
            @CurrentUser AuthenticatedUser caller,
            @PathVariable UUID vehicleId) {
        return ResponseEntity.ok(userVehicleService.getMyVehicle(caller, vehicleId));
    }

    @PostMapping
    public ResponseEntity<VehicleResponse> addVehicle(
            @CurrentUser AuthenticatedUser caller,
            @Valid @RequestBody CreateVehicleRequest request) {
        VehicleResponse response = userVehicleService.addVehicle(caller, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/{vehicleId}")
    public ResponseEntity<VehicleResponse> updateVehicle(
            @CurrentUser AuthenticatedUser caller,
            @PathVariable UUID vehicleId,
            @Valid @RequestBody UpdateVehicleRequest request) {
        return ResponseEntity.ok(userVehicleService.updateVehicle(caller, vehicleId, request));
    }

    @DeleteMapping("/{vehicleId}")
    public ResponseEntity<Void> deleteVehicle(
            @CurrentUser AuthenticatedUser caller,
            @PathVariable UUID vehicleId) {
        userVehicleService.deleteVehicle(caller, vehicleId);
        return ResponseEntity.noContent().build();
    }
}
