package com.navio.usermanagementservice.controller;

import com.navio.usermanagementservice.config.*;
import com.navio.usermanagementservice.security.*;
import com.navio.usermanagementservice.service.*;
import com.navio.usermanagementservice.media.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = {UserPictureController.class, UserController.class}, properties = {
        "spring.config.name=vehicle-controller-test", "spring.cloud.config.enabled=false", "spring.cloud.bus.enabled=false",
        "navio.security.keycloak.issuer-uri=https://example.com/realms/test",
        "navio.security.keycloak.audiences=navio-api",
        "navio.security.keycloak.admin.base-url=https://example.com",
        "navio.security.keycloak.admin.realm=test", "navio.security.keycloak.admin.client-id=test",
        "navio.security.keycloak.admin.client-secret=test-only"
})
@Import({SecurityConfig.class, JacksonConfiguration.class, WebMvcConfig.class,
        CurrentUserArgumentResolver.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class})
class UserPictureControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean UserPictureService pictures;
    @MockitoBean UserProfileService profiles;
    @MockitoBean UserProvisioningService provisioning;
    private static final UUID USER = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private final AuthenticatedUser caller = new AuthenticatedUser(USER, "subject", "user@example.com", "User", Set.of());

    @Test void anonymousRequestsCannotUploadReadOrRemoveProfilePictures() throws Exception {
        mvc.perform(multipart("/v1/users/me/picture").file("file",new byte[]{1})).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
        mvc.perform(get("/v1/users/me/picture")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/v1/users/me/picture")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/users/"+USER+"/picture")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/users/by-subject/"+USER)).andExpect(status().isUnauthorized());
        verifyNoInteractions(pictures, profiles, provisioning);
    }

    @Test void forgedIdentityHeadersCannotChangeTheUploadTarget() throws Exception {
        when(provisioning.resolve(any(),anySet())).thenReturn(caller);
        mvc.perform(multipart("/v1/users/me/picture").file("file",new byte[]{1}).param("userId",UUID.randomUUID().toString())
                .header("X-User-Id",UUID.randomUUID()).with(jwt())).andExpect(status().isCreated());
        verify(pictures).upload(eq(caller),any());
    }

    @Test void ownPictureLiteralResolvesTheCallerInsteadOfAUserPathVariable() throws Exception {
        when(provisioning.resolve(any(),anySet())).thenReturn(caller);
        when(pictures.read(USER)).thenReturn(new PictureValidator.Picture(new byte[]{1,2}, "image/png"));
        mvc.perform(get("/v1/users/me/picture").with(jwt())).andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                .andExpect(header().string("X-Content-Type-Options","nosniff"));
        verify(pictures).read(USER);
    }

    @Test void malformedPicturesUseTheSharedErrorContract() throws Exception {
        when(provisioning.resolve(any(),anySet())).thenReturn(caller);
        when(pictures.upload(eq(caller),any())).thenThrow(PictureException.invalid("Invalid picture"));
        mvc.perform(multipart("/v1/users/me/picture").file("file",new byte[]{1}).with(jwt()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400)).andExpect(jsonPath("$.message").value("Invalid picture"));
    }

    @Test void absentPicturesReturnNotFoundWithoutInternalDetails() throws Exception {
        when(provisioning.resolve(any(),anySet())).thenReturn(caller);
        when(pictures.read(USER)).thenThrow(PictureException.notFound());
        mvc.perform(get("/v1/users/me/picture").with(jwt())).andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
    }

    @Test void missingFilePartsReturnBadRequest() throws Exception {
        when(provisioning.resolve(any(),anySet())).thenReturn(caller);
        mvc.perform(multipart("/v1/users/me/picture").with(jwt())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(pictures);
    }

    @Test void subjectLookupReturnsOnlyThePublicProfile() throws Exception {
        when(provisioning.resolve(any(),anySet())).thenReturn(caller);
        mvc.perform(get("/v1/users/by-subject/"+USER).with(jwt())).andExpect(status().isOk());
        verify(profiles).getPublicProfileBySubject(USER);
    }
}
