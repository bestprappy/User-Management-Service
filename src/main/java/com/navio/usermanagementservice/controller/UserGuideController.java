package com.navio.usermanagementservice.controller;

import com.navio.usermanagementservice.dto.GuideDtos.*;
import com.navio.usermanagementservice.model.Guide.Status;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import com.navio.usermanagementservice.security.CurrentUser;
import com.navio.usermanagementservice.service.UserGuideService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @RequestMapping("/v1/users/me/guides") @RequiredArgsConstructor @Validated
public class UserGuideController {
    private final UserGuideService service;
    @GetMapping
    public ResponseEntity<Page<Summary>> list(@CurrentUser AuthenticatedUser caller,
            @RequestParam(required = false) @Size(max = 100) String term,
            @RequestParam(required = false) Status status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(service.mine(caller, term, status, PageRequest.of(page, size, Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.asc("id")))));
    }
    @GetMapping("/{id}")
    public ResponseEntity<AdminResponse> get(@CurrentUser AuthenticatedUser caller, @PathVariable UUID id) {
        return ResponseEntity.ok(service.detail(caller, id));
    }
    @PostMapping
    public ResponseEntity<AdminResponse> create(@CurrentUser AuthenticatedUser caller, @Valid @RequestBody CreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(caller, request));
    }
    @PutMapping("/{id}")
    public ResponseEntity<AdminResponse> update(@CurrentUser AuthenticatedUser caller, @PathVariable UUID id, @Valid @RequestBody WriteRequest request) {
        return ResponseEntity.ok(service.update(caller, id, request));
    }
    @PostMapping("/{id}/publish")
    public ResponseEntity<AdminResponse> publish(@CurrentUser AuthenticatedUser caller, @PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
        return ResponseEntity.ok(service.publish(caller, id, request.expectedVersion()));
    }
    @PostMapping("/{id}/unpublish")
    public ResponseEntity<AdminResponse> unpublish(@CurrentUser AuthenticatedUser caller, @PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
        return ResponseEntity.ok(service.unpublish(caller, id, request.expectedVersion()));
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@CurrentUser AuthenticatedUser caller, @PathVariable UUID id, @RequestParam @PositiveOrZero Long expectedVersion) {
        service.delete(caller, id, expectedVersion);
        return ResponseEntity.noContent().build();
    }
}
