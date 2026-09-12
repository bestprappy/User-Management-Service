package com.navio.usermanagementservice.controller;

import com.navio.usermanagementservice.config.JacksonConfiguration;
import com.navio.usermanagementservice.config.SecurityConfig;
import com.navio.usermanagementservice.config.WebMvcConfig;
import com.navio.usermanagementservice.dto.SavedPlaceResponse;
import com.navio.usermanagementservice.exception.UserManagementExceptions.SavedPlaceNotFoundException;
import com.navio.usermanagementservice.security.*;
import com.navio.usermanagementservice.service.UserProvisioningService;
import com.navio.usermanagementservice.service.UserSavedPlaceService;
import org.junit.jupiter.api.BeforeEach;
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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = UserSavedPlaceController.class, properties = {
        "spring.config.name=saved-place-controller-test", "spring.cloud.config.enabled=false",
        "spring.cloud.bus.enabled=false",
        "navio.security.keycloak.issuer-uri=https://example.com/realms/test",
        "navio.security.keycloak.audiences=navio-api",
        "navio.security.keycloak.admin.base-url=https://example.com",
        "navio.security.keycloak.admin.realm=test", "navio.security.keycloak.admin.client-id=test",
        "navio.security.keycloak.admin.client-secret=test-only"
})
@Import({SecurityConfig.class, JacksonConfiguration.class, WebMvcConfig.class,
        CurrentUserArgumentResolver.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class})
class UserSavedPlaceControllerTests {

    private static final UUID OWNER = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final String BASE = "/v1/users/me/places";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean UserSavedPlaceService savedPlaces;
    @MockitoBean UserProvisioningService provisioning;

    private final AuthenticatedUser caller =
            new AuthenticatedUser(OWNER, "subject", "test@example.com", "Test", Set.of());

    @BeforeEach void setUp() {
        lenient().when(provisioning.resolve(any(), anySet())).thenReturn(caller);
    }

    @Test void anonymousRequestsCannotReadOrMutateSavedPlaces() throws Exception {
        for (var request : List.of(get(BASE), get(BASE + "/" + ID), post(BASE),
                patch(BASE + "/" + ID), delete(BASE + "/" + ID))) {
            mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401));
        }
        verifyNoInteractions(savedPlaces, provisioning);
    }

    @Test void anotherUsersSavedPlaceReturnsNotFoundWithoutRevealingExistence() throws Exception {
        when(savedPlaces.getMySavedPlace(caller, ID)).thenThrow(new SavedPlaceNotFoundException(ID));
        mvc.perform(get(BASE + "/" + ID).with(jwt()).header("X-User-Id", ID.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Saved place not found"));
        verify(savedPlaces).getMySavedPlace(caller, ID);
    }

    @Test void createRejectsCoordinatesOutsideTheWorld() throws Exception {
        mvc.perform(post(BASE).with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label":"Home","name":"Home","lat":120.0,"lng":100.5}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.validationErrors.lat").exists());
        verifyNoInteractions(savedPlaces);
    }

    @Test void createRequiresALabel() throws Exception {
        mvc.perform(post(BASE).with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Home","lat":13.75,"lng":100.5}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.label").exists());
        verifyNoInteractions(savedPlaces);
    }

    @Test void createReturnsTheSavedPlaceWithCreatedStatus() throws Exception {
        when(savedPlaces.addSavedPlace(eq(caller), any())).thenReturn(response());
        mvc.perform(post(BASE).with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label":"Home","kind":"HOME","name":"Sukhumvit 24",
                                 "address":"Bangkok","lat":13.75,"lng":100.5,"isDefault":true}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.label").value("Home"))
                .andExpect(jsonPath("$.kind").value("HOME"))
                .andExpect(jsonPath("$.lat").value(13.75))
                .andExpect(jsonPath("$.isDefault").value(true));
    }

    @Test void deleteReturnsNoContent() throws Exception {
        mvc.perform(delete(BASE + "/" + ID).with(jwt()))
                .andExpect(status().isNoContent());
        verify(savedPlaces).deleteSavedPlace(caller, ID);
    }

    private SavedPlaceResponse response() {
        return new SavedPlaceResponse(ID, "Home", "HOME", "Sukhumvit 24", "Bangkok",
                13.75, 100.5, null, true, Instant.parse("2026-09-12T00:00:00Z"),
                Instant.parse("2026-09-12T00:00:00Z"));
    }
}
