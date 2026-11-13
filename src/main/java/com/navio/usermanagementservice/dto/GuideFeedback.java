package com.navio.usermanagementservice.dto;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Feedback never includes account IDs or email addresses. */
public final class GuideFeedback {
    private GuideFeedback() {}
    public record RatingRequest(@NotNull @Min(1) @Max(5) Integer score) {}
    public record CommentRequest(@NotBlank @Size(max=1500) String content,
            @Size(max=100) String authorName, UUID parentId) {}
    public record EditRequest(@NotBlank @Size(max=1500) String content, @NotNull @Min(0) Integer version) {}
    public enum Reason { SPAM, HARASSMENT, INAPPROPRIATE, OTHER }
    public record ReportRequest(@NotNull Reason reason) {}
    public record Summary(Double ratingAverage, long ratingCount, long commentCount, Integer myRating, boolean publisher, long viewCount, long uniqueViewCount, boolean canModerate) {}
    public record Comment(UUID id, UUID parentId, String authorName, String content, boolean deleted,
            Instant createdAt, Instant updatedAt, int version, boolean canEdit, boolean canDelete, long replyCount) {}
    public record Comments(List<Comment> content, boolean hasMore, long total, int page) {}
    public record Report(UUID commentId, String content, long reportCount) {}
}
