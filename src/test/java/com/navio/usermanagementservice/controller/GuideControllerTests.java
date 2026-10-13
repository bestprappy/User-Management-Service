package com.navio.usermanagementservice.controller;

import com.navio.usermanagementservice.config.*;
import com.navio.usermanagementservice.exception.UserManagementExceptions.*;
import com.navio.usermanagementservice.security.*;
import com.navio.usermanagementservice.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = {AdminGuideController.class, GuideController.class}, properties = {
    "spring.config.name=guide-controller-test", "spring.cloud.config.enabled=false", "spring.cloud.bus.enabled=false",
    "navio.security.keycloak.issuer-uri=https://example.com/realms/test", "navio.security.keycloak.audiences=navio-api",
    "navio.security.keycloak.admin.base-url=https://example.com", "navio.security.keycloak.admin.realm=test",
    "navio.security.keycloak.admin.client-id=test", "navio.security.keycloak.admin.client-secret=test-only"
})
@Import({SecurityConfig.class, JacksonConfiguration.class, WebMvcConfig.class, CurrentUserArgumentResolver.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class})
class GuideControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean GuideService service;
    @MockitoBean UserProvisioningService provisioning;

    static final String BASE = "/v1/admin/guides";
    static final UUID ID = UUID.fromString("8f2b6c1e-5d3a-4c7b-9e1f-2a6d4b8c0e01");
    static final String GUIDE = """
        {"name":"Chiang Mai","country":"Thailand","countryCode":"TH",
         "destination":{"provider":"GOOGLE","providerPlaceId":"ChIJ-chiang-mai","name":"Chiang Mai","lat":18.79,"lng":98.98},
         "description":"","coverPlaceId":"GOOGLE:ChIJ-chiang-mai",
         "content":{"categories":[{"id":"visit","name":"Places to visit","iconId":"landmark","colorId":"blue"}],
           "lists":{"visit":{"id":"visit","categoryId":"visit"}},
           "places":{"ChIJ-temple":{"id":"GOOGLE:ChIJ-temple","provider":"GOOGLE","providerPlaceId":"ChIJ-temple","name":"Temple","address":"","lat":18.8,"lng":98.92,"tags":["Temple"]}},
           "body":"<div data-embed=\\"placeList\\" data-list-id=\\"visit\\"></div>"}}
        """;
    static final String CREATE = "{\"guide\":" + GUIDE + "}";
    static final String WRITE = "{\"expectedVersion\":0,\"guide\":" + GUIDE + "}";
    static final String VERSION = "{\"expectedVersion\":0}";

    @BeforeEach void setUp() {
        lenient().when(provisioning.resolve(any(), anySet())).thenReturn(new AuthenticatedUser(UUID.randomUUID(), "admin", "admin@example.com", "Admin", Set.of(NavioRole.ADMIN)));
    }

    List<MockHttpServletRequestBuilder> adminRoutes() {
        return List.of(
                get(BASE), get(BASE + "/" + ID),
                post(BASE).contentType(MediaType.APPLICATION_JSON).content(CREATE),
                put(BASE + "/" + ID).contentType(MediaType.APPLICATION_JSON).content(WRITE),
                post(BASE + "/" + ID + "/publish").contentType(MediaType.APPLICATION_JSON).content(VERSION),
                post(BASE + "/" + ID + "/unpublish").contentType(MediaType.APPLICATION_JSON).content(VERSION),
                delete(BASE + "/" + ID).param("expectedVersion", "0"));
    }

    static SimpleGrantedAuthority role(String name) { return new SimpleGrantedAuthority("ROLE_" + name); }

    @Test void anonymousCallersCannotReachAnyAdminGuideOperation() throws Exception {
        for (var request : adminRoutes()) mvc.perform(request).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void usersAndModeratorsCannotReachAnyAdminGuideOperation() throws Exception {
        for (String name : List.of("USER", "MODERATOR")) for (var request : adminRoutes()) {
            mvc.perform(request.with(jwt().authorities(role(name)))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    @Test void adminsAndOwnersReachEveryAdminGuideOperationWithItsStatus() throws Exception {
        for (String name : List.of("ADMIN", "OWNER")) {
            mvc.perform(get(BASE).with(jwt().authorities(role(name)))).andExpect(status().isOk());
            mvc.perform(post(BASE).with(jwt().authorities(role(name))).contentType(MediaType.APPLICATION_JSON).content(CREATE)).andExpect(status().isCreated());
            mvc.perform(put(BASE + "/" + ID).with(jwt().authorities(role(name))).contentType(MediaType.APPLICATION_JSON).content(WRITE)).andExpect(status().isOk());
            mvc.perform(post(BASE + "/" + ID + "/publish").with(jwt().authorities(role(name))).contentType(MediaType.APPLICATION_JSON).content(VERSION)).andExpect(status().isOk());
            mvc.perform(post(BASE + "/" + ID + "/unpublish").with(jwt().authorities(role(name))).contentType(MediaType.APPLICATION_JSON).content(VERSION)).andExpect(status().isOk());
            mvc.perform(delete(BASE + "/" + ID).param("expectedVersion", "0").with(jwt().authorities(role(name)))).andExpect(status().isNoContent());
        }
        verify(service, times(2)).create(any(), any());
        verify(service, times(2)).update(any(), eq(ID), any());
        verify(service, times(2)).publish(any(), eq(ID), eq(0L));
        verify(service, times(2)).unpublish(any(), eq(ID), eq(0L));
        verify(service, times(2)).delete(any(), eq(ID), eq(0L));
    }

    @Test void publishedGuidesAreReadableWithoutASessionButNothingElseIs() throws Exception {
        when(service.published(any())).thenReturn(Page.empty());
        mvc.perform(get("/v1/guides")).andExpect(status().isOk());
        mvc.perform(get("/v1/guides/" + ID)).andExpect(status().isOk());
        verifyNoInteractions(provisioning);
        mvc.perform(post("/v1/guides").contentType(MediaType.APPLICATION_JSON).content(CREATE)).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/guides/" + ID + "/draft")).andExpect(status().isUnauthorized());
    }

    @Test void malformedIdsAndPagingAreRejectedBeforeTheService() throws Exception {
        mvc.perform(get("/v1/guides/not-a-uuid")).andExpect(status().isBadRequest());
        for (String query : List.of("size=101", "size=0", "page=-1")) mvc.perform(get("/v1/guides?" + query)).andExpect(status().isBadRequest());
        mvc.perform(delete(BASE + "/" + ID).with(jwt().authorities(role("ADMIN")))).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void invalidGuideContentIsRejectedWithFieldErrors() throws Exception {
        List<String> invalid = List.of(
                CREATE.replace("\"iconId\":\"landmark\"", "\"iconId\":\"skull\""),
                CREATE.replace("\"colorId\":\"blue\"", "\"colorId\":\"#ff0000\""),
                CREATE.replace("\"lat\":18.8", "\"lat\":91"),
                CREATE.replace("\"name\":\"Chiang Mai\",\"country\"", "\"name\":\" \",\"country\""),
                CREATE.replace("\"places\":{\"ChIJ-temple\"", "\"places\":{\"bad id\""),
                CREATE.replace("\"tags\":[\"Temple\"]", "\"tags\":[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\",\"7\"]"),
                CREATE.replace("\"countryCode\":\"TH\"", "\"countryCode\":\"Thailand\""));
        for (String body : invalid) {
            mvc.perform(post(BASE).with(jwt().authorities(role("ADMIN"))).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        }
        mvc.perform(put(BASE + "/" + ID).with(jwt().authorities(role("ADMIN"))).contentType(MediaType.APPLICATION_JSON).content(CREATE))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void missingGuidesMapTo404() throws Exception {
        when(service.publishedDetail(ID)).thenThrow(new GuideNotFoundException());
        mvc.perform(get("/v1/guides/" + ID)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404)).andExpect(jsonPath("$.message").value("This guide was not found"));
    }

    @Test void aSecondGuideForADestinationMapsTo409() throws Exception {
        when(service.create(any(), any())).thenThrow(new GuideConflictException("Chiang Mai already has a guide"));
        mvc.perform(post(BASE).with(jwt().authorities(role("ADMIN"))).contentType(MediaType.APPLICATION_JSON).content(CREATE))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Chiang Mai already has a guide"));
    }

    @Test void anotherAdminsSaveMapsTo409() throws Exception {
        when(service.update(any(), eq(ID), any())).thenThrow(new ObjectOptimisticLockingFailureException("Guide", ID));
        mvc.perform(put(BASE + "/" + ID).with(jwt().authorities(role("ADMIN"))).contentType(MediaType.APPLICATION_JSON).content(WRITE))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
    }

    @Test void publishingAnEmptyGuideMapsTo422() throws Exception {
        when(service.publish(any(), eq(ID), eq(0L))).thenThrow(new BusinessRuleException("Add at least one place before publishing"));
        mvc.perform(post(BASE + "/" + ID + "/publish").with(jwt().authorities(role("ADMIN"))).contentType(MediaType.APPLICATION_JSON).content(VERSION))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.message").value("Add at least one place before publishing"));
    }
}
