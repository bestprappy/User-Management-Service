package com.navio.usermanagementservice.controller;

import com.navio.usermanagementservice.config.JacksonConfiguration;
import com.navio.usermanagementservice.config.SecurityConfig;
import com.navio.usermanagementservice.config.WebMvcConfig;
import com.navio.usermanagementservice.dto.ModerationDtos.AdminStatisticsResponse;
import com.navio.usermanagementservice.dto.ModerationDtos.ModerationResponse;
import com.navio.usermanagementservice.exception.UserManagementExceptions.ForbiddenOperationException;
import com.navio.usermanagementservice.exception.UserManagementExceptions.ModerationConflictException;
import com.navio.usermanagementservice.exception.UserManagementExceptions.UserNotFoundException;
import com.navio.usermanagementservice.integration.keycloak.KeycloakAdminException;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import com.navio.usermanagementservice.security.CurrentUserArgumentResolver;
import com.navio.usermanagementservice.security.NavioRole;
import com.navio.usermanagementservice.security.RestAccessDeniedHandler;
import com.navio.usermanagementservice.security.RestAuthenticationEntryPoint;
import com.navio.usermanagementservice.service.AdminUserService;
import com.navio.usermanagementservice.service.RoleManagementService;
import com.navio.usermanagementservice.service.UserModerationService;
import com.navio.usermanagementservice.service.UserProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = AdminUserController.class, properties = {
        "spring.config.name=admin-user-controller-test", "spring.cloud.config.enabled=false", "spring.cloud.bus.enabled=false",
        "navio.security.keycloak.issuer-uri=https://example.com/realms/test",
        "navio.security.keycloak.audiences=navio-api",
        "navio.security.keycloak.admin.base-url=https://example.com",
        "navio.security.keycloak.admin.realm=test", "navio.security.keycloak.admin.client-id=test",
        "navio.security.keycloak.admin.client-secret=test-only"
})
@Import({SecurityConfig.class, JacksonConfiguration.class, WebMvcConfig.class,
        CurrentUserArgumentResolver.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class})
class AdminUserControllerTests {

    private static final UUID CALLER_ID = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID TARGET_ID = UUID.fromString("00000000-0000-4000-8000-0000000000b1");
    private static final String BASE = "/v1/admin/users";
    private static final String REASON = "{\"reason\":\"Spam reports confirmed\"}";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean AdminUserService adminUserService;
    @MockitoBean UserModerationService userModerationService;
    @MockitoBean RoleManagementService roleManagementService;
    @MockitoBean UserProvisioningService provisioning;

    private final AuthenticatedUser moderator = new AuthenticatedUser(CALLER_ID, "kc-mod", "mod@example.com",
            "Mod", Set.of(NavioRole.USER, NavioRole.MODERATOR));

    @BeforeEach
    void setUp() {
        lenient().when(provisioning.resolve(any(), anySet())).thenReturn(moderator);
    }

    @Test
    void anonymousCallersAreRejectedOnEveryAdminRouteAndNothingRuns() throws Exception {
        for (MockHttpServletRequestBuilder request : everyRoute()) {
            mvc.perform(request).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
        }
        verifyNoInteractions(adminUserService, userModerationService, roleManagementService, provisioning);
    }

    @Test
    void ordinaryUsersAreForbiddenOnEveryAdminRouteAndNothingRuns() throws Exception {
        for (MockHttpServletRequestBuilder request : everyRoute()) {
            mvc.perform(request.with(as(NavioRole.USER)))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
        }
        verifyNoInteractions(adminUserService, userModerationService, roleManagementService);
    }

    @Test
    void moderatorsCanReadStatisticsAndTheLiteralWinsOverTheUserIdRoute() throws Exception {
        when(adminUserService.statistics()).thenReturn(new AdminStatisticsResponse(42, 39, 3, 7,
                Instant.parse("2026-08-25T10:00:00Z"), Instant.parse("2026-09-24T10:00:00Z")));

        mvc.perform(get(BASE + "/statistics").with(as(NavioRole.MODERATOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalUsers").value(42))
                .andExpect(jsonPath("$.suspendedUsers").value(3))
                .andExpect(jsonPath("$.joinedSince").value("2026-08-25T10:00:00Z"))
                .andExpect(jsonPath("$.asOf").value("2026-09-24T10:00:00Z"));

        verify(adminUserService).statistics();
        verify(adminUserService, org.mockito.Mockito.never()).detail(any());
    }

    @Test
    void searchReturnsThePageShapeTheAdminConsoleReads() throws Exception {
        var row = new com.navio.usermanagementservice.dto.ModerationDtos.AdminUserSummaryResponse(TARGET_ID, "Jane",
                "jane@example.com", "suspended", List.of(NavioRole.USER), Instant.parse("2026-09-01T00:00:00Z"), null);
        when(adminUserService.search(eq("jane"), eq(com.navio.usermanagementservice.model.UserStatus.SUSPENDED), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(row),
                        org.springframework.data.domain.PageRequest.of(0, 20), 1));

        mvc.perform(get(BASE).param("term", "jane").param("status", "SUSPENDED").with(as(NavioRole.MODERATOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].email").value("jane@example.com"))
                .andExpect(jsonPath("$.content[0].status").value("suspended"))
                .andExpect(jsonPath("$.content[0].roles[0]").value("USER"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void moderatorsCanBanAnOrdinaryUser() throws Exception {
        when(userModerationService.suspend(eq(moderator), eq(TARGET_ID), any())).thenReturn(new ModerationResponse(
                TARGET_ID, "suspended", "Spam reports confirmed", null, Instant.parse("2026-09-24T10:00:00Z")));

        mvc.perform(post(BASE + "/" + TARGET_ID + "/suspend").with(as(NavioRole.MODERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content(REASON))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("suspended"));
    }

    @Test
    void moderatorsCannotChangeRoles() throws Exception {
        mvc.perform(post(BASE + "/" + TARGET_ID + "/roles").with(as(NavioRole.MODERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\",\"reason\":\"Promote me\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
        verifyNoInteractions(roleManagementService);
    }

    @Test
    void ownerMayReachRoleManagementEndpoint() throws Exception {
        mvc.perform(post(BASE + "/" + TARGET_ID + "/roles").with(as(NavioRole.OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\",\"reason\":\"New administrator\"}"))
                .andExpect(status().isOk());
        verify(roleManagementService).grant(any(), eq(TARGET_ID), any());
    }

    @Test
    void aBanWithoutAReasonIsRejectedBeforeAnythingChanges() throws Exception {
        mvc.perform(post(BASE + "/" + TARGET_ID + "/suspend").with(as(NavioRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.validationErrors.reason").exists());
        verifyNoInteractions(userModerationService);
    }

    @Test
    void unknownUsersAreNotFound() throws Exception {
        when(adminUserService.detail(TARGET_ID)).thenThrow(new UserNotFoundException(TARGET_ID));

        mvc.perform(get(BASE + "/" + TARGET_ID).with(as(NavioRole.MODERATOR)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("User not found"));
    }

    @Test
    void banningAnAlreadyBannedAccountConflicts() throws Exception {
        when(userModerationService.suspend(eq(moderator), eq(TARGET_ID), any()))
                .thenThrow(new ModerationConflictException("This account is already suspended"));

        mvc.perform(post(BASE + "/" + TARGET_ID + "/suspend").with(as(NavioRole.MODERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content(REASON))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("This account is already suspended"));
    }

    @Test
    void privilegedTargetRefusalsAreForbiddenWithTheirReason() throws Exception {
        when(userModerationService.suspend(eq(moderator), eq(TARGET_ID), any())).thenThrow(new ForbiddenOperationException(
                "Only an administrator can moderate a moderator or administrator account"));

        mvc.perform(post(BASE + "/" + TARGET_ID + "/suspend").with(as(NavioRole.MODERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content(REASON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Only an administrator can moderate a moderator or administrator account"));
    }

    @Test
    void anIdentityProviderOutageIsReportedAsUnavailableNotAsSuccess() throws Exception {
        when(userModerationService.suspend(eq(moderator), eq(TARGET_ID), any()))
                .thenThrow(new KeycloakAdminException("connection refused to keycloak:8080"));

        mvc.perform(post(BASE + "/" + TARGET_ID + "/suspend").with(as(NavioRole.MODERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content(REASON))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("Try again in a moment"));
    }

    @Test
    void moderationHistoryPagesAreCapped() throws Exception {
        mvc.perform(get(BASE + "/" + TARGET_ID + "/moderation-events").param("size", "51").with(as(NavioRole.ADMIN)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(adminUserService);
    }

    private static JwtRequestPostProcessor as(NavioRole role) {
        return jwt().jwt(token -> token.subject("kc-caller")).authorities(new SimpleGrantedAuthority(role.authority()));
    }

    private static List<MockHttpServletRequestBuilder> everyRoute() {
        String user = BASE + "/" + TARGET_ID;
        return List.of(
                get(BASE),
                get(BASE + "/statistics"),
                get(user),
                get(user + "/moderation-events"),
                post(user + "/suspend").contentType(MediaType.APPLICATION_JSON).content(REASON),
                post(user + "/reactivate").contentType(MediaType.APPLICATION_JSON).content(REASON),
                post(user + "/roles").contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\",\"reason\":\"x y z\"}"),
                delete(user + "/roles/ADMIN"));
    }
}
