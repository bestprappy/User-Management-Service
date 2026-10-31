package com.navio.usermanagementservice.controller;

import com.navio.usermanagementservice.config.JacksonConfiguration;
import com.navio.usermanagementservice.config.SecurityConfig;
import com.navio.usermanagementservice.config.WebMvcConfig;
import com.navio.usermanagementservice.dto.PublicUserProfileResponse;
import com.navio.usermanagementservice.dto.SecurityOverviewResponse;
import com.navio.usermanagementservice.dto.UsernameAvailabilityResponse;
import com.navio.usermanagementservice.exception.UserManagementExceptions.ReauthenticationRequiredException;
import com.navio.usermanagementservice.exception.UserManagementExceptions.UserGoneException;
import com.navio.usermanagementservice.exception.UserManagementExceptions.UsernameTakenException;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import com.navio.usermanagementservice.security.CurrentUserArgumentResolver;
import com.navio.usermanagementservice.security.RestAccessDeniedHandler;
import com.navio.usermanagementservice.security.RestAuthenticationEntryPoint;
import com.navio.usermanagementservice.service.AccountSecurityService;
import com.navio.usermanagementservice.service.UserBannerService;
import com.navio.usermanagementservice.service.UserProfileService;
import com.navio.usermanagementservice.service.UserProvisioningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = {UserController.class, UserBannerController.class, AccountSecurityController.class}, properties = {
        "spring.config.name=public-profile-controller-test", "spring.cloud.config.enabled=false", "spring.cloud.bus.enabled=false",
        "navio.security.keycloak.issuer-uri=https://example.com/realms/test",
        "navio.security.keycloak.audiences=navio-api",
        "navio.security.keycloak.admin.base-url=https://example.com",
        "navio.security.keycloak.admin.realm=test", "navio.security.keycloak.admin.client-id=test",
        "navio.security.keycloak.admin.client-secret=test-only"
})
@Import({SecurityConfig.class, JacksonConfiguration.class, WebMvcConfig.class,
        CurrentUserArgumentResolver.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class})
class PublicProfileControllerTests {

    private static final UUID USER = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID SUBJECT = UUID.fromString("00000000-0000-4000-8000-0000000000aa");

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean UserProfileService profiles;
    @MockitoBean UserBannerService banners;
    @MockitoBean AccountSecurityService security;
    @MockitoBean UserProvisioningService provisioning;

    private final AuthenticatedUser caller = new AuthenticatedUser(USER, SUBJECT.toString(), "user@example.com", "User", Set.of());

    @Test
    void anonymousCallersCannotReadProfilesBannersOrSecurityOrChangeAnything() throws Exception {
        mvc.perform(get("/v1/users/by-username/alex")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
        mvc.perform(get("/v1/users/username-availability").param("username", "alex")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/users/" + USER + "/banner")).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/v1/users/me/banner").file("file", new byte[]{1})).andExpect(status().isUnauthorized());
        mvc.perform(delete("/v1/users/me/banner")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/users/me/security")).andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/users/me/sessions/revoke")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/v1/users/me").contentType(MediaType.APPLICATION_JSON).content("{\"bio\":\"hi\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(profiles, banners, security, provisioning);
    }

    @Test
    void theAvailabilityLiteralIsNotTreatedAsAUserId() throws Exception {
        when(provisioning.resolve(any(), anySet())).thenReturn(caller);
        when(profiles.checkUsername(caller, "alex")).thenReturn(new UsernameAvailabilityResponse("alex", true, null));
        mvc.perform(get("/v1/users/username-availability").param("username", "alex").with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(true));
        verify(profiles).checkUsername(caller, "alex");
    }

    @Test
    void theSearchLiteralIsNotTreatedAsAUserIdAndNeedsSignIn() throws Exception {
        mvc.perform(get("/v1/users/search").param("q", "alex")).andExpect(status().isUnauthorized());
        when(provisioning.resolve(any(), anySet())).thenReturn(caller);
        when(profiles.searchPeople("alex", 0, 20)).thenReturn(org.springframework.data.domain.Page.empty());
        mvc.perform(get("/v1/users/search").param("q", "alex").with(jwt())).andExpect(status().isOk());
        verify(profiles).searchPeople("alex", 0, 20);
    }

    @Test
    void publicProfilesNeverCarryEmailOrAccountSettings() throws Exception {
        when(provisioning.resolve(any(), anySet())).thenReturn(caller);
        when(profiles.getPublicProfileByUsername("alex")).thenReturn(new PublicUserProfileResponse(USER, "Alex",
                null, Instant.parse("2026-01-01T00:00:00Z"), "alex", SUBJECT, "Road trips", null, List.of("food"), "relaxed"));
        mvc.perform(get("/v1/users/by-username/alex").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alex"))
                .andExpect(jsonPath("$.accountId").value(SUBJECT.toString()))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.preferences").doesNotExist())
                .andExpect(jsonPath("$.roles").doesNotExist());
    }

    @Test
    void deletedAccountsAnswerGoneWithTheSharedErrorShape() throws Exception {
        when(provisioning.resolve(any(), anySet())).thenReturn(caller);
        when(profiles.getPublicProfileByUsername("closed")).thenThrow(new UserGoneException());
        mvc.perform(get("/v1/users/by-username/closed").with(jwt()))
                .andExpect(status().isGone()).andExpect(jsonPath("$.status").value(410))
                .andExpect(jsonPath("$.message").value("This account was deleted"));
    }

    @Test
    void aTakenUsernameIsAConflict() throws Exception {
        when(provisioning.resolve(any(), anySet())).thenReturn(caller);
        when(profiles.updateMyProfile(eq(caller), any())).thenThrow(new UsernameTakenException());
        mvc.perform(patch("/v1/users/me").with(jwt()).contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"alex\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("This username is taken"));
    }

    @Test
    void profilePatchesHaveNowhereToPutEmailRolesOrStatus() throws Exception {
        when(provisioning.resolve(any(), anySet())).thenReturn(caller);
        var captured = org.mockito.ArgumentCaptor.forClass(com.navio.usermanagementservice.dto.UpdateProfileRequest.class);
        mvc.perform(patch("/v1/users/me").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bio\":\"hi\",\"roles\":[\"ADMIN\"],\"email\":\"x@example.com\",\"status\":\"active\"}"))
                .andExpect(status().isOk());
        verify(profiles).updateMyProfile(eq(caller), captured.capture());
        // The request type has no email, role or status field, so those keys are dropped at binding.
        assertThat(captured.getValue()).isEqualTo(new com.navio.usermanagementservice.dto.UpdateProfileRequest(
                null, null, null, null, null, "hi", null, null, null));
    }

    @Test
    void unknownTravelPacesAreRejectedBeforeTheService() throws Exception {
        when(provisioning.resolve(any(), anySet())).thenReturn(caller);
        mvc.perform(patch("/v1/users/me").with(jwt()).contentType(MediaType.APPLICATION_JSON).content("{\"travelPace\":\"warp\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.validationErrors.travelPace").exists());
        verifyNoInteractions(profiles);
    }

    @Test
    void signingOutEverywhereWithAStaleSignInAsksForReauthentication() throws Exception {
        when(provisioning.resolve(any(), anySet())).thenReturn(caller);
        org.mockito.Mockito.doThrow(new ReauthenticationRequiredException()).when(security).signOutEverywhere(eq(caller), any());
        mvc.perform(post("/v1/users/me/sessions/revoke").with(jwt()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("reauthentication_required"));
    }

    @Test
    void theSecurityOverviewReadsTheTokensSignInTime() throws Exception {
        when(provisioning.resolve(any(), anySet())).thenReturn(caller);
        Instant signedIn = Instant.parse("2026-10-31T10:00:00Z");
        when(security.overview(caller, signedIn)).thenReturn(new SecurityOverviewResponse(false, List.of("google"), true, signedIn, true));
        mvc.perform(get("/v1/users/me/security").with(jwt().jwt(token -> token.claim("auth_time", signedIn.getEpochSecond()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordSet").value(false))
                .andExpect(jsonPath("$.linkedProviders[0]").value("google"));
    }
}
