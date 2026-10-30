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

@WebMvcTest(value = com.navio.usermanagementservice.library.SavedLibraryController.class, properties = {
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
class SavedLibraryControllerTests {

    private static final UUID OWNER = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final String BASE = "/v1/users/me/saved";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean com.navio.usermanagementservice.library.SavedLibraryService savedPlaces;
    @MockitoBean UserProvisioningService provisioning;

    private final AuthenticatedUser caller =
            new AuthenticatedUser(OWNER, "subject", "test@example.com", "Test", Set.of());

    @BeforeEach void setUp() {
        lenient().when(provisioning.resolve(any(), anySet())).thenReturn(caller);
    }

    @Test void anonymousCannotEnumerateOrMutateLibrary() throws Exception {
        for(var r:List.of(get(BASE),post(BASE+"/states"),post(BASE+"/places"),post(BASE+"/plans"),post(BASE+"/"+ID+"/open"),post(BASE+"/"+ID+"/restore"),delete(BASE+"/"+ID),delete(BASE+"/"+ID+"/permanent"),get(BASE+"/"+ID+"/sources/token")))
            mvc.perform(r.contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(savedPlaces);
    }
    @Test void identityComesFromSessionAndPageIsPrivate() throws Exception {
        when(savedPlaces.list(eq(OWNER),any())).thenReturn(new com.navio.usermanagementservice.library.LibraryContracts.Results(List.of(),0,0,24,0,0));
        mvc.perform(get(BASE).with(jwt())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        verify(savedPlaces).list(eq(OWNER),any());
    }
    @Test void malformedCoordinatesAndOversizedStateBatchesAreRejected() throws Exception {
        mvc.perform(post(BASE+"/places").with(jwt()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Place\",\"lat\":100,\"lng\":2}")).andExpect(status().isBadRequest());
        String keys=String.join(",",java.util.Collections.nCopies(49,"\"google:one\""));
        mvc.perform(post(BASE+"/states").with(jwt()).contentType(MediaType.APPLICATION_JSON).content("{\"identities\":["+keys+"]}")).andExpect(status().isBadRequest());
        verifyNoInteractions(savedPlaces);
    }
    @Test void mutationsUseCallerScopeAndMissingLooksLikeForeign() throws Exception {
        doThrow(new SavedPlaceNotFoundException(ID)).when(savedPlaces).remove(OWNER,ID);
        mvc.perform(delete(BASE+"/"+ID).with(jwt())).andExpect(status().isNotFound());
        verify(savedPlaces).remove(OWNER,ID);
    }
}
