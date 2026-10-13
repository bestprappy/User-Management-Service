package com.navio.usermanagementservice.controller;

import com.navio.usermanagementservice.dto.GuideDtos.*;
import com.navio.usermanagementservice.model.Guide.Status;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import com.navio.usermanagementservice.security.CurrentUser;
import com.navio.usermanagementservice.service.GuideService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Guides as administrators manage them: every administrator sees and edits every guide. */
@RestController @RequestMapping("/v1/admin/guides")
@RequiredArgsConstructor @Validated @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
public class AdminGuideController {
    private final GuideService service;

    @GetMapping
    public ResponseEntity<Page<Summary>> list(@CurrentUser AuthenticatedUser caller,
            @RequestParam(required = false) @Size(max = 100) String term,
            @RequestParam(required = false) Status status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(service.search(caller, term, status, PageRequest.of(page, size, Sort.by("country", "name", "id"))));
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
    public ResponseEntity<Void> delete(@CurrentUser AuthenticatedUser caller, @PathVariable UUID id,
            @RequestParam @NotNull @PositiveOrZero Long expectedVersion) {
        service.delete(caller, id, expectedVersion);
        return ResponseEntity.noContent().build();
    }
}
