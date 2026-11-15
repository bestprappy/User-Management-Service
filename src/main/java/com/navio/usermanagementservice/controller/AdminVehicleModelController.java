package com.navio.usermanagementservice.controller;

import com.navio.usermanagementservice.dto.VehicleModelDtos.*;
import com.navio.usermanagementservice.model.VehicleModel.Status;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import com.navio.usermanagementservice.security.CurrentUser;
import com.navio.usermanagementservice.service.VehicleModelImageService;
import com.navio.usermanagementservice.service.VehicleModelService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @RequestMapping("/v1/admin/vehicle-models")
@RequiredArgsConstructor @Validated @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
public class AdminVehicleModelController {
    private final VehicleModelService service;
    private final VehicleModelImageService images;
    /** Stores a re-encoded PNG or JPEG and returns the path to save as a vehicle's imageUrl. */
    @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED)
    public ImageResponse uploadImage(@CurrentUser AuthenticatedUser caller, @RequestPart("file") MultipartFile file) { return images.upload(caller, file); }
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
