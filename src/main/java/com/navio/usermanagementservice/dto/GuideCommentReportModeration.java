package com.navio.usermanagementservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The platform moderators' view of reported Guide comments. */
public final class GuideCommentReportModeration {
    private GuideCommentReportModeration() {}

    /**
     * One reported comment that is still visible, with every report on it folded together.
     *
     * @param guideSource     CURATED or TRAVELER, as the public Guide reader addresses it
     * @param guideTitle      null when the Guide itself has since been deleted
     * @param authorAccountId the author's account id (Keycloak subject), as community profiles use it
     */
    public record ReportedComment(UUID commentId, String guideSource, UUID guideId, String guideTitle, String content,
            String authorAccountId, String authorName, long reportCount, List<String> reasons,
            Instant firstReportedAt, Instant lastReportedAt) {}

    /** {@code remove} hides the comment for everyone; {@code dismiss} clears its reports and keeps it. */
    public record ResolveRequest(
            @NotBlank @Pattern(regexp = "^(remove|dismiss)$", message = "decision must be remove or dismiss")
            String decision) {}
}
