package com.navio.usermanagementservice.controller;

import com.navio.usermanagementservice.dto.ModerationDtos.AdminUserSummaryResponse;
import com.navio.usermanagementservice.dto.ModerationDtos.ModerationRequest;
import com.navio.usermanagementservice.dto.ModerationDtos.ModerationResponse;
import com.navio.usermanagementservice.dto.ModerationDtos.RoleAssignmentRequest;
import com.navio.usermanagementservice.dto.ModerationDtos.RoleAssignmentResponse;
import com.navio.usermanagementservice.model.UserStatus;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import com.navio.usermanagementservice.security.CurrentUser;
import com.navio.usermanagementservice.security.NavioRole;
import com.navio.usermanagementservice.service.AdminUserService;
import com.navio.usermanagementservice.service.RoleManagementService;
import com.navio.usermanagementservice.service.UserModerationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Moderation and role administration.
 *
 * <h2>Two layers of authorization</h2>
 * The filter chain already restricts {@code /v1/admin/**} to MODERATOR or ADMIN.
 * The {@code @PreAuthorize} annotations here narrow that per operation:
 * suspension is open to moderators, while role changes require ADMIN. Stating it
 * on the handler means the rule travels with the code rather than living only in
 * a path pattern somebody could later re-scope.
 *
 * <p>A third layer sits in the services: a moderator cannot act on another
 * moderator, and an admin cannot revoke their own ADMIN role.
 */
@RestController
@RequestMapping("/v1/admin/users")
@RequiredArgsConstructor
@Validated
public class AdminUserController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AdminUserService adminUserService;
    private final UserModerationService userModerationService;
    private final RoleManagementService roleManagementService;

    /**
     * Searches users.
     *
     * <p>{@code size} is capped so a caller cannot request an unbounded page and
     * pull the entire user table — with emails — in one request.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
    public ResponseEntity<Page<AdminUserSummaryResponse>> searchUsers(
            @RequestParam(required = false) @Size(max = 200) String term,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {

        Pageable pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(adminUserService.search(term, status, pageable));
    }

    @PostMapping("/{userId}/suspend")
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
    public ResponseEntity<ModerationResponse> suspendUser(
            @CurrentUser AuthenticatedUser caller,
            @PathVariable UUID userId,
            @Valid @RequestBody ModerationRequest request) {
        return ResponseEntity.ok(userModerationService.suspend(caller, userId, request));
    }

    @PostMapping("/{userId}/reactivate")
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
    public ResponseEntity<ModerationResponse> reactivateUser(
            @CurrentUser AuthenticatedUser caller,
            @PathVariable UUID userId,
            @Valid @RequestBody ModerationRequest request) {
        return ResponseEntity.ok(userModerationService.reactivate(caller, userId, request));
    }

    /** Granting a global role is an ADMIN-only operation. */
    @PostMapping("/{userId}/roles")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RoleAssignmentResponse> grantRole(
            @CurrentUser AuthenticatedUser caller,
            @PathVariable UUID userId,
            @Valid @RequestBody RoleAssignmentRequest request) {
        return ResponseEntity.ok(roleManagementService.grant(caller, userId, request));
    }

    /**
     * Revokes a global role.
     *
     * <p>{@code reason} is a query parameter because DELETE bodies are unreliable
     * across proxies and clients; it is still recorded in the audit entry.
     */
    @DeleteMapping("/{userId}/roles/{role}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RoleAssignmentResponse> revokeRole(
            @CurrentUser AuthenticatedUser caller,
            @PathVariable UUID userId,
            @PathVariable NavioRole role,
            @RequestParam(required = false) @Size(max = 2000) String reason) {
        return ResponseEntity.ok(roleManagementService.revoke(caller, userId, role, reason));
    }
}
