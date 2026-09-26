package com.navio.usermanagementservice.controller;

import com.navio.usermanagementservice.dto.VehicleModelDtos.*;
import com.navio.usermanagementservice.model.VehicleModel.Status;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import com.navio.usermanagementservice.security.CurrentUser;
import com.navio.usermanagementservice.service.VehicleModelService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/v1/admin/vehicle-models")
@RequiredArgsConstructor @Validated @PreAuthorize("hasRole('ADMIN')")
public class AdminVehicleModelController {
    private final VehicleModelService service;
    @GetMapping
    public Page<AdminResponse> list(@CurrentUser AuthenticatedUser caller,
            @RequestParam(required = false) @Size(max = 100) String term,
            @RequestParam(required = false) Status status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.search(caller, term, status, PageRequest.of(page, size, Sort.by("make", "model", "id")));
    }
    @GetMapping("/{id}") public AdminResponse get(@CurrentUser AuthenticatedUser caller, @PathVariable String id) { return service.detail(caller, id); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public AdminResponse create(@CurrentUser AuthenticatedUser caller, @Valid @RequestBody WriteRequest request) { return service.create(caller, request); }
    @PutMapping("/{id}")
    public AdminResponse update(@CurrentUser AuthenticatedUser caller, @PathVariable String id, @Valid @RequestBody WriteRequest request) { return service.update(caller, id, request); }
    @PostMapping("/{id}/publish")
    public AdminResponse publish(@CurrentUser AuthenticatedUser caller, @PathVariable String id, @Valid @RequestBody VersionRequest request) {
        return service.transition(caller, id, request.expectedVersion(), Status.PUBLISHED);
    }
    @PostMapping("/{id}/archive")
    public AdminResponse archive(@CurrentUser AuthenticatedUser caller, @PathVariable String id, @Valid @RequestBody VersionRequest request) {
        return service.transition(caller, id, request.expectedVersion(), Status.ARCHIVED);
    }
}
