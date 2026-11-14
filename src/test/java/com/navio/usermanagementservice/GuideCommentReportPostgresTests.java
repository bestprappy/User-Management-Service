package com.navio.usermanagementservice;

import com.navio.usermanagementservice.dto.GuideFeedback.CommentRequest;
import com.navio.usermanagementservice.dto.GuideFeedback.Reason;
import com.navio.usermanagementservice.dto.GuideFeedback.ReportRequest;
import com.navio.usermanagementservice.repository.GuideCommentReportRepository;
import com.navio.usermanagementservice.repository.GuideFeedbackRepository;
import com.navio.usermanagementservice.service.GuideCommentReportModerationService;
import com.navio.usermanagementservice.service.GuideFeedbackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {"spring.datasource.url=${NAVIO_TEST_DB_URL}", "spring.datasource.username=${NAVIO_TEST_DB_USERNAME:tripplanner}",
        "spring.datasource.password=${NAVIO_TEST_DB_PASSWORD:tripplanner}", "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.flyway.enabled=true", "spring.flyway.locations=classpath:db/migration", "spring.flyway.schemas=iam",
        "spring.flyway.default-schema=iam", "spring.flyway.create-schemas=true", "spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "NAVIO_TEST_DB_URL", matches = ".+")
@Import({GuideFeedbackRepository.class, GuideFeedbackService.class, GuideCommentReportRepository.class, GuideCommentReportModerationService.class})
class GuideCommentReportPostgresTests {

    /** A curated Guide the migrations publish. */
    private static final String KEY = "CURATED:8f2b6c1e-5d3a-4c7b-9e1f-2a6d4b8c0e01";

    @Autowired GuideFeedbackService feedback;
    @Autowired GuideCommentReportModerationService moderation;
    @Autowired JdbcTemplate jdbc;

    final UUID alice = UUID.randomUUID(), bob = UUID.randomUUID(), carol = UUID.randomUUID(), moderator = UUID.randomUUID();

    @BeforeEach
    void accounts() {
        for (UUID id : new UUID[]{alice, bob, carol}) {
            jdbc.update("INSERT INTO iam.users(id,auth_subject,email,display_name) VALUES(?,?,?,?)", id, "subject-" + id, id + "@example.com", "Traveler");
        }
    }

    private UUID reportedComment() {
        feedback.add(KEY, alice, new CommentRequest("Buy cheap followers", "Alice", null));
        UUID comment = feedback.comments(KEY, alice, null, 0).content().getFirst().id();
        feedback.report(KEY, bob, comment, new ReportRequest(Reason.SPAM));
        feedback.report(KEY, carol, comment, new ReportRequest(Reason.HARASSMENT));
        return comment;
    }

    @Test
    void moderatorsSeeAReportedGuideCommentWithItsGuideAndAuthorAccount() {
        assertThat(moderation.queue(0, 20).getTotalElements()).isZero();
        UUID comment = reportedComment();

        var page = moderation.queue(0, 20);

        assertThat(page.getTotalElements()).isEqualTo(1);
        var row = page.getContent().getFirst();
        assertThat(row.commentId()).isEqualTo(comment);
        assertThat(row.guideSource()).isEqualTo("CURATED");
        assertThat(row.guideId()).isEqualTo(UUID.fromString(KEY.substring(8)));
        assertThat(row.guideTitle()).isNotBlank();
        assertThat(row.content()).isEqualTo("Buy cheap followers");
        assertThat(row.authorAccountId()).isEqualTo("subject-" + alice);
        assertThat(row.reportCount()).isEqualTo(2);
        assertThat(row.reasons()).containsExactly("HARASSMENT", "SPAM");
        assertThat(row.firstReportedAt()).isBeforeOrEqualTo(row.lastReportedAt());
    }

    @Test
    void dismissingKeepsTheCommentAndALaterReportBringsItBack() {
        UUID comment = reportedComment();

        moderation.resolve(moderator, comment, "dismiss");

        assertThat(moderation.queue(0, 20).getTotalElements()).isZero();
        assertThat(feedback.comments(KEY, alice, null, 0).content().getFirst().deleted()).isFalse();
        feedback.report(KEY, bob, comment, new ReportRequest(Reason.OTHER));
        assertThat(moderation.queue(0, 20).getContent()).singleElement()
                .satisfies(row -> assertThat(row.reportCount()).isEqualTo(1));
    }

    @Test
    void removingHidesTheCommentForEveryoneAndEmptiesTheQueue() {
        UUID comment = reportedComment();

        moderation.resolve(moderator, comment, "remove");

        assertThat(moderation.queue(0, 20).getTotalElements()).isZero();
        assertThat(feedback.comments(KEY, bob, null, 0).content().getFirst().deleted()).isTrue();
        assertThat(feedback.summary(KEY, null).commentCount()).isZero();
        assertThatThrownBy(() -> moderation.resolve(moderator, comment, "dismiss"))
                .isInstanceOfSatisfying(GuideFeedbackService.FeedbackException.class, error -> assertThat(error.status()).isEqualTo(404));
    }
}
