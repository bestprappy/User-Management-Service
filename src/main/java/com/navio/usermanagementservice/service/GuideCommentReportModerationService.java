package com.navio.usermanagementservice.service;

import com.navio.usermanagementservice.dto.GuideCommentReportModeration.ReportedComment;
import com.navio.usermanagementservice.repository.GuideCommentReportRepository;
import com.navio.usermanagementservice.repository.GuideFeedbackRepository;
import com.navio.usermanagementservice.service.GuideFeedbackService.FeedbackException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * The platform moderation queue for reported Guide comments. Guide publishers
 * still review reports on their own Guides; this lets Navio's moderators see
 * every report too. Only platform moderators reach this: the admin security
 * gate and the controller's role check enforce that first.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class GuideCommentReportModerationService {

    static final int MAX_PAGE_SIZE = 50;

    private final GuideCommentReportRepository reports;
    private final GuideFeedbackRepository feedback;

    public Page<ReportedComment> queue(int page, int size) {
        var request = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
        long total = reports.countOpen();
        List<ReportedComment> rows = total == 0 ? List.of() : reports.open(request.getPageSize(), request.getOffset());
        return new PageImpl<>(rows, request, total);
    }

    @Transactional
    public void resolve(UUID moderator, UUID commentId, String decision) {
        var target = reports.openTarget(commentId)
                .orElseThrow(() -> new FeedbackException(404, "This report was already handled or the comment is gone."));
        switch (decision) {
            case "remove" -> feedback.delete(target.publicationId(), target.commentId());
            case "dismiss" -> reports.dismiss(target.commentId());
            default -> throw new FeedbackException(400, "Choose remove or dismiss.");
        }
        log.info("Guide comment report on {} resolved as {} by platform moderator {}", commentId, decision, moderator);
    }
}
