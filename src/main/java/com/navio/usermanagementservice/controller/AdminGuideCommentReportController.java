package com.navio.usermanagementservice.controller;

import com.navio.usermanagementservice.dto.GuideCommentReportModeration.ReportedComment;
import com.navio.usermanagementservice.dto.GuideCommentReportModeration.ResolveRequest;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import com.navio.usermanagementservice.security.CurrentUser;
import com.navio.usermanagementservice.service.GuideCommentReportModerationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Reported Guide comments, for platform moderators, admins and owners. */
@RestController
@RequestMapping("/v1/admin/guide-comment-reports")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN', 'OWNER')")
public class AdminGuideCommentReportController {

    private final GuideCommentReportModerationService moderation;

    @GetMapping
    public ResponseEntity<Page<ReportedComment>> queue(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(moderation.queue(page, size));
    }

    @PatchMapping("/{commentId}")
    public ResponseEntity<Void> resolve(@CurrentUser AuthenticatedUser caller, @PathVariable UUID commentId,
            @Valid @RequestBody ResolveRequest request) {
        moderation.resolve(caller.id(), commentId, request.decision());
        return ResponseEntity.noContent().build();
    }
}
