package com.navio.usermanagementservice.controller;

import com.navio.usermanagementservice.config.JacksonConfiguration;
import com.navio.usermanagementservice.config.SecurityConfig;
import com.navio.usermanagementservice.config.WebMvcConfig;
import com.navio.usermanagementservice.dto.UserPreferences;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import com.navio.usermanagementservice.security.CurrentUserArgumentResolver;
import com.navio.usermanagementservice.security.RestAccessDeniedHandler;
import com.navio.usermanagementservice.security.RestAuthenticationEntryPoint;
import com.navio.usermanagementservice.service.UserProfileService;
import com.navio.usermanagementservice.service.UserProvisioningService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** `PATCH /v1/users/me/preferences`, focused on the first-time tip fields. */
@WebMvcTest(value = {UserController.class}, properties = {
        "spring.config.name=user-preferences-controller-test", "spring.cloud.config.enabled=false", "spring.cloud.bus.enabled=false",
        "navio.security.keycloak.issuer-uri=https://example.com/realms/test",
        "navio.security.keycloak.audiences=navio-api",
        "navio.security.keycloak.admin.base-url=https://example.com",
        "navio.security.keycloak.admin.realm=test", "navio.security.keycloak.admin.client-id=test",
        "navio.security.keycloak.admin.client-secret=test-only"
})
@Import({SecurityConfig.class, JacksonConfiguration.class, WebMvcConfig.class,
        CurrentUserArgumentResolver.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class})
class UserPreferencesControllerTests {

    private static final UUID USER = UUID.fromString("00000000-0000-4000-8000-000000000001");

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean UserProfileService profiles;
    @MockitoBean UserProvisioningService provisioning;

    private final AuthenticatedUser caller = new AuthenticatedUser(USER, "subject", "user@example.com", "User", Set.of());

    @Test
    void anonymousCallersCannotRecordTips() throws Exception {
        mvc.perform(patch("/v1/users/me/preferences").contentType(MediaType.APPLICATION_JSON).content("{\"tipsHidden\":true}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
        verifyNoInteractions(profiles, provisioning);
    }

    @Test
    void aSeenTipReachesTheServiceForTheCallerOnly() throws Exception {
        when(provisioning.resolve(any(), anySet())).thenReturn(caller);
        ArgumentCaptor<UserPreferences> captured = ArgumentCaptor.forClass(UserPreferences.class);

        mvc.perform(patch("/v1/users/me/preferences").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seenTips\":{\"planner-auto-plan\":1}}"))
                .andExpect(status().isOk());

        verify(profiles).updatePreferences(eq(caller), captured.capture());
        assertThat(captured.getValue().seenTips()).containsExactlyEntriesOf(Map.of("planner-auto-plan", 1));
        assertThat(captured.getValue().tipsHidden()).isNull();
        assertThat(captured.getValue().language()).isNull();
    }

    @Test
    void dontShowAgainReachesTheService() throws Exception {
        when(provisioning.resolve(any(), anySet())).thenReturn(caller);
        ArgumentCaptor<UserPreferences> captured = ArgumentCaptor.forClass(UserPreferences.class);

        mvc.perform(patch("/v1/users/me/preferences").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipsHidden\":true}"))
                .andExpect(status().isOk());

        verify(profiles).updatePreferences(eq(caller), captured.capture());
        assertThat(captured.getValue().tipsHidden()).isTrue();
    }

    @Test
    void malformedTipIdsAreRejectedBeforeTheService() throws Exception {
        when(provisioning.resolve(any(), anySet())).thenReturn(caller);

        mvc.perform(patch("/v1/users/me/preferences").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seenTips\":{\"<b>x</b>\":1}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.validationErrors").exists());

        verifyNoInteractions(profiles);
    }
}
