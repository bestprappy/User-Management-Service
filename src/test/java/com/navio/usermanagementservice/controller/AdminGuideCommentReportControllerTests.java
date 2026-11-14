package com.navio.usermanagementservice.controller;

import com.navio.usermanagementservice.config.JacksonConfiguration;
import com.navio.usermanagementservice.config.SecurityConfig;
import com.navio.usermanagementservice.config.WebMvcConfig;
import com.navio.usermanagementservice.dto.GuideCommentReportModeration.ReportedComment;
import com.navio.usermanagementservice.repository.GuideCommentReportRepository;
import com.navio.usermanagementservice.repository.GuideCommentReportRepository.Target;
import com.navio.usermanagementservice.repository.GuideFeedbackRepository;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import com.navio.usermanagementservice.security.CurrentUserArgumentResolver;
import com.navio.usermanagementservice.security.NavioRole;
import com.navio.usermanagementservice.security.RestAccessDeniedHandler;
import com.navio.usermanagementservice.security.RestAuthenticationEntryPoint;
import com.navio.usermanagementservice.service.GuideCommentReportModerationService;
import com.navio.usermanagementservice.service.UserProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = AdminGuideCommentReportController.class, properties = {
        "spring.config.name=guide-comment-report-test", "spring.cloud.config.enabled=false", "spring.cloud.bus.enabled=false",
        "navio.security.keycloak.issuer-uri=https://example.com/realms/test", "navio.security.keycloak.audiences=navio-api",
        "navio.security.keycloak.admin.base-url=https://example.com", "navio.security.keycloak.admin.realm=test",
        "navio.security.keycloak.admin.client-id=test", "navio.security.keycloak.admin.client-secret=test-only"})
@Import({SecurityConfig.class, JacksonConfiguration.class, WebMvcConfig.class, CurrentUserArgumentResolver.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, GuideCommentReportModerationService.class})
class AdminGuideCommentReportControllerTests {

    private static final String PATH = "/v1/admin/guide-comment-reports";
    private static final UUID MODERATOR = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID COMMENT = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID PUBLICATION = UUID.fromString("00000000-0000-4000-8000-000000000003");
    private static final UUID GUIDE = UUID.fromString("00000000-0000-4000-8000-000000000004");

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean UserProvisioningService provisioning;
    @MockitoBean GuideCommentReportRepository reports;
    @MockitoBean GuideFeedbackRepository feedback;

    @BeforeEach
    void actor() {
        when(provisioning.resolve(any(), anySet())).thenReturn(
                new AuthenticatedUser(MODERATOR, "subject", "mod@example.com", "Moderator", Set.of(NavioRole.MODERATOR)));
    }

    @Test
    void guestsCannotReadOrResolveReports() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(patch(PATH + "/" + COMMENT).contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"remove\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(reports, feedback);
    }

    @Test
    void travelersWithoutAModerationRoleAreForbidden() throws Exception {
        var traveler = jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"));
        mvc.perform(get(PATH).with(traveler)).andExpect(status().isForbidden());
        mvc.perform(patch(PATH + "/" + COMMENT).with(traveler).contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"remove\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(reports, feedback);
    }

    @Test
    void moderatorsSeeEveryReportedCommentWithItsGuide() throws Exception {
        when(reports.countOpen()).thenReturn(1L);
        when(reports.open(20, 0)).thenReturn(List.of(new ReportedComment(COMMENT, "CURATED", GUIDE, "Kyoto",
                "Rude reply", "author-subject", "Someone", 1, List.of("HARASSMENT"),
                Instant.parse("2026-11-14T01:00:00Z"), Instant.parse("2026-11-14T01:00:00Z"))));

        mvc.perform(get(PATH).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MODERATOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].commentId").value(COMMENT.toString()))
                .andExpect(jsonPath("$.content[0].guideSource").value("CURATED"))
                .andExpect(jsonPath("$.content[0].guideTitle").value("Kyoto"))
                .andExpect(jsonPath("$.content[0].authorAccountId").value("author-subject"))
                .andExpect(jsonPath("$.content[0].reasons[0]").value("HARASSMENT"));
    }

    @Test
    void removingAReportedCommentHidesItForEveryone() throws Exception {
        when(reports.openTarget(COMMENT)).thenReturn(Optional.of(new Target(COMMENT, PUBLICATION)));

        mvc.perform(patch(PATH + "/" + COMMENT).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"remove\"}"))
                .andExpect(status().isNoContent());

        verify(feedback).delete(PUBLICATION, COMMENT);
        verify(reports, never()).dismiss(any());
    }

    @Test
    void dismissingClearsTheReportsAndKeepsTheComment() throws Exception {
        when(reports.openTarget(COMMENT)).thenReturn(Optional.of(new Target(COMMENT, PUBLICATION)));

        mvc.perform(patch(PATH + "/" + COMMENT).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OWNER")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"dismiss\"}"))
                .andExpect(status().isNoContent());

        verify(reports).dismiss(COMMENT);
        verifyNoInteractions(feedback);
    }

    @Test
    void anAlreadyHandledReportIsNotFound() throws Exception {
        when(reports.openTarget(COMMENT)).thenReturn(Optional.empty());

        mvc.perform(patch(PATH + "/" + COMMENT).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"dismiss\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
        verify(reports, never()).dismiss(any());
    }

    @Test
    void rejectsAnUnknownDecisionBeforeTouchingReports() throws Exception {
        mvc.perform(patch(PATH + "/" + COMMENT).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"ban\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(reports, feedback);
    }
}
