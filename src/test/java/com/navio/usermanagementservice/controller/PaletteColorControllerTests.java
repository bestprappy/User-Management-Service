package com.navio.usermanagementservice.controller;

import com.navio.usermanagementservice.config.*;
import com.navio.usermanagementservice.dto.PaletteColorDtos.*;
import com.navio.usermanagementservice.exception.UserManagementExceptions.*;
import com.navio.usermanagementservice.security.*;
import com.navio.usermanagementservice.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.time.Instant;
import java.util.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = {AdminPaletteColorController.class, PaletteColorController.class}, properties = {
    "spring.config.name=palette-color-controller-test", "spring.cloud.config.enabled=false", "spring.cloud.bus.enabled=false",
    "navio.security.keycloak.issuer-uri=https://example.com/realms/test", "navio.security.keycloak.audiences=navio-api",
    "navio.security.keycloak.admin.base-url=https://example.com", "navio.security.keycloak.admin.realm=test",
    "navio.security.keycloak.admin.client-id=test", "navio.security.keycloak.admin.client-secret=test-only"
})
@Import({SecurityConfig.class, JacksonConfiguration.class, WebMvcConfig.class, CurrentUserArgumentResolver.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class})
class PaletteColorControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean PaletteColorService service;
    @MockitoBean UserProvisioningService provisioning;
    static final String BASE = "/v1/admin/palette-colors";
    static final String CREATE = "{\"name\":\"Sunset\",\"hex\":\"#E8590C\"}";
    static final String TEXT = "{\"textOnLight\":\"black\",\"textOnDark\":\"white\"}";
    static final AdminResponse SUNSET = new AdminResponse("sunset", "Sunset", "#e8590c", 250, false, null,
            Instant.parse("2026-10-14T00:00:00Z"), Instant.parse("2026-10-14T00:00:00Z"), "black", "white");

    @BeforeEach void setUp() {
        lenient().when(provisioning.resolve(any(), anySet())).thenReturn(new AuthenticatedUser(UUID.randomUUID(), "admin", "admin@example.com", "Admin", Set.of(NavioRole.ADMIN)));
    }

    List<MockHttpServletRequestBuilder> adminRoutes() {
        return List.of(get(BASE), post(BASE).contentType(MediaType.APPLICATION_JSON).content(CREATE),
                patch(BASE + "/teal").contentType(MediaType.APPLICATION_JSON).content(TEXT),
                post(BASE + "/teal/retire"), post(BASE + "/teal/restore"), delete(BASE + "/old"));
    }

    static SimpleGrantedAuthority role(String name) { return new SimpleGrantedAuthority("ROLE_" + name); }

    @Test void anonymousCallersCannotReachAnyAdminOperation() throws Exception {
        for (var request : adminRoutes()) mvc.perform(request).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void usersAndModeratorsCannotReachAnyAdminOperation() throws Exception {
        for (String name : List.of("USER", "MODERATOR")) {
            for (var request : adminRoutes()) mvc.perform(request.with(jwt().authorities(role(name)))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    @Test void adminsCanListAddRetireAndRestore() throws Exception {
        when(service.create(any(), any())).thenReturn(SUNSET);
        when(service.retire(any(), eq("teal"))).thenReturn(SUNSET);
        when(service.restore(any(), eq("teal"))).thenReturn(SUNSET);
        mvc.perform(get(BASE).with(jwt().authorities(role("ADMIN")))).andExpect(status().isOk());
        mvc.perform(post(BASE).with(jwt().authorities(role("OWNER"))).contentType(MediaType.APPLICATION_JSON).content(CREATE))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value("sunset")).andExpect(jsonPath("$.hex").value("#e8590c"));
        mvc.perform(post(BASE + "/teal/retire").with(jwt().authorities(role("ADMIN")))).andExpect(status().isOk());
        mvc.perform(post(BASE + "/teal/restore").with(jwt().authorities(role("ADMIN")))).andExpect(status().isOk());
        verify(service).create(any(), eq(new CreateRequest("Sunset", "#E8590C")));
    }

    @Test void adminsCanChangeALabelColorAndDeleteARetiredColor() throws Exception {
        when(service.updateTextColor(any(), eq("sunset"), any())).thenReturn(SUNSET);
        mvc.perform(patch(BASE + "/sunset").with(jwt().authorities(role("ADMIN"))).contentType(MediaType.APPLICATION_JSON).content(TEXT))
                .andExpect(status().isOk()).andExpect(jsonPath("$.textOnLight").value("black")).andExpect(jsonPath("$.textOnDark").value("white"));
        verify(service).updateTextColor(any(), eq("sunset"), eq(new UpdateTextColorRequest("black", "white")));
        mvc.perform(delete(BASE + "/old").with(jwt().authorities(role("OWNER")))).andExpect(status().isNoContent());
        verify(service).delete(any(), eq("old"));
    }

    @Test void thereIsNoNameOrHexEditRoute() throws Exception {
        mvc.perform(put(BASE + "/teal").with(jwt().authorities(role("ADMIN"))).contentType(MediaType.APPLICATION_JSON).content(CREATE))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(put(BASE + "/teal/retire").with(jwt().authorities(role("ADMIN"))).contentType(MediaType.APPLICATION_JSON).content(CREATE))
                .andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(service);
    }

    @Test void invalidLabelColorsAreRejectedBeforeTheService() throws Exception {
        for (String body : List.of("{\"textOnLight\":\"grey\",\"textOnDark\":\"white\"}", "{\"textOnLight\":\"black\"}", "{}")) {
            mvc.perform(patch(BASE + "/teal").with(jwt().authorities(role("ADMIN"))).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        }
        mvc.perform(post(BASE).with(jwt().authorities(role("ADMIN"))).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sunset\",\"hex\":\"#E8590C\",\"textOnLight\":\"pink\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete(BASE + "/Not_An_Id").with(jwt().authorities(role("ADMIN")))).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void guestsCanReadThePaletteWithoutProvisioningAnAccount() throws Exception {
        when(service.all()).thenReturn(List.of(new PublicResponse("teal", "Teal", "#00ae84", false, false, "white", "white"),
                new PublicResponse("old", "Old", "#123456", true, false, "auto", "auto"),
                new PublicResponse("red", "Deleted color", "#00ae84", true, true, "auto", "auto")));
        mvc.perform(get("/v1/palette-colors")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("teal")).andExpect(jsonPath("$[0].hex").value("#00ae84"))
                .andExpect(jsonPath("$[0].textOnLight").value("white")).andExpect(jsonPath("$[0].deleted").value(false))
                .andExpect(jsonPath("$[1].retired").value(true))
                .andExpect(jsonPath("$[2].deleted").value(true));
        verifyNoInteractions(provisioning);
        mvc.perform(post("/v1/palette-colors").contentType(MediaType.APPLICATION_JSON).content(CREATE)).andExpect(status().isUnauthorized());
        mvc.perform(delete("/v1/palette-colors")).andExpect(status().isUnauthorized());
    }

    @Test void invalidColorsAndIdsAreRejectedBeforeTheService() throws Exception {
        for (String body : List.of("{\"name\":\"\",\"hex\":\"#E8590C\"}", "{\"name\":\"Sunset\",\"hex\":\"E8590C\"}",
                "{\"name\":\"Sunset\",\"hex\":\"#E859\"}", "{\"name\":\"Sunset\",\"hex\":\"red\"}", "{\"name\":\"Sunset\"}",
                "{\"name\":\"" + "x".repeat(41) + "\",\"hex\":\"#E8590C\"}")) {
            mvc.perform(post(BASE).with(jwt().authorities(role("ADMIN"))).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        }
        mvc.perform(post(BASE + "/Not_An_Id/retire").with(jwt().authorities(role("ADMIN")))).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void anUnknownColorMapsTo404() throws Exception {
        when(service.retire(any(), eq("missing"))).thenThrow(new PaletteColorNotFoundException());
        mvc.perform(post(BASE + "/missing/retire").with(jwt().authorities(role("ADMIN"))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("This color was not found"));
    }

    @Test void aDuplicateColorMapsTo409() throws Exception {
        when(service.create(any(), any())).thenThrow(new PaletteColorConflictException("This color is already in the palette"));
        mvc.perform(post(BASE).with(jwt().authorities(role("ADMIN"))).contentType(MediaType.APPLICATION_JSON).content(CREATE))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("This color is already in the palette"));
    }

    @Test void deletingAColorStillInThePaletteMapsTo422() throws Exception {
        doThrow(new BusinessRuleException("Remove this color from the palette before deleting it")).when(service).delete(any(), eq("teal"));
        mvc.perform(delete(BASE + "/teal").with(jwt().authorities(role("ADMIN"))))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.message").value("Remove this color from the palette before deleting it"));
    }

    @Test void aRejectedColorMapsTo422() throws Exception {
        when(service.retire(any(), eq("teal"))).thenThrow(new BusinessRuleException("Keep at least one color in the palette"));
        mvc.perform(post(BASE + "/teal/retire").with(jwt().authorities(role("ADMIN"))))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.message").value("Keep at least one color in the palette"));
    }
}
