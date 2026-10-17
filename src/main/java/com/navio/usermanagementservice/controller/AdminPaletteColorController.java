package com.navio.usermanagementservice.controller;

import com.navio.usermanagementservice.dto.PaletteColorDtos;
import com.navio.usermanagementservice.dto.PaletteColorDtos.AdminResponse;
import com.navio.usermanagementservice.dto.PaletteColorDtos.CreateRequest;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import com.navio.usermanagementservice.security.CurrentUser;
import com.navio.usermanagementservice.service.PaletteColorService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Administrators add colors and retire or restore them. There is no delete and no hex edit. */
@RestController @RequestMapping("/v1/admin/palette-colors")
@RequiredArgsConstructor @Validated @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
public class AdminPaletteColorController {
    private final PaletteColorService service;

    @GetMapping
    public ResponseEntity<List<AdminResponse>> list(@CurrentUser AuthenticatedUser caller) {
        return ResponseEntity.ok(service.adminList(caller));
    }

    @PostMapping
    public ResponseEntity<AdminResponse> create(@CurrentUser AuthenticatedUser caller, @Valid @RequestBody CreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(caller, request));
    }

    @PostMapping("/{id}/retire")
    public ResponseEntity<AdminResponse> retire(@CurrentUser AuthenticatedUser caller,
            @PathVariable @Pattern(regexp = PaletteColorDtos.ID) String id) {
        return ResponseEntity.ok(service.retire(caller, id));
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<AdminResponse> restore(@CurrentUser AuthenticatedUser caller,
            @PathVariable @Pattern(regexp = PaletteColorDtos.ID) String id) {
        return ResponseEntity.ok(service.restore(caller, id));
    }
}
