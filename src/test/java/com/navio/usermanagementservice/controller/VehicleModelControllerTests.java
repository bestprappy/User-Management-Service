package com.navio.usermanagementservice.controller;

import com.navio.usermanagementservice.config.*;
import com.navio.usermanagementservice.media.*;
import com.navio.usermanagementservice.dto.VehicleModelDtos.*;
import com.navio.usermanagementservice.exception.UserManagementExceptions.*;
import com.navio.usermanagementservice.security.*;
import com.navio.usermanagementservice.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import java.util.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = {AdminVehicleModelController.class, VehicleModelController.class}, properties = {
    "spring.config.name=vehicle-model-controller-test", "spring.cloud.config.enabled=false", "spring.cloud.bus.enabled=false",
    "navio.security.keycloak.issuer-uri=https://example.com/realms/test", "navio.security.keycloak.audiences=navio-api",
    "navio.security.keycloak.admin.base-url=https://example.com", "navio.security.keycloak.admin.realm=test",
    "navio.security.keycloak.admin.client-id=test", "navio.security.keycloak.admin.client-secret=test-only"
})
@Import({SecurityConfig.class, JacksonConfiguration.class, WebMvcConfig.class, CurrentUserArgumentResolver.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class})
class VehicleModelControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean VehicleModelService service;
    @MockitoBean VehicleModelImageService images;
    static final UUID IMAGE_ID = UUID.fromString("6f1c2f1e-8a54-4a0c-9f7e-1c2d3e4f5a6b");
    static final MockMultipartFile PHOTO = new MockMultipartFile("file", "car.png", "image/png", new byte[]{1, 2, 3});
    @MockitoBean UserProvisioningService provisioning;
    static final String BASE = "/v1/admin/vehicle-models";
    static final String DRAFT = """
        {"specification":{"make":"BYD","model":"Test","trim":"","market":"TH","batteryCapacityBasis":"UNKNOWN","connectorTypes":[]},"expectedVersion":0}
        """;
    @BeforeEach void setUp() {
        lenient().when(provisioning.resolve(any(), anySet())).thenReturn(new AuthenticatedUser(UUID.randomUUID(), "admin", "admin@example.com", "Admin", Set.of(NavioRole.ADMIN)));
    }
    List<AbstractMockHttpServletRequestBuilder<?>> routes() { return List.<AbstractMockHttpServletRequestBuilder<?>>of(get(BASE), get(BASE + "/car"), post(BASE).contentType(MediaType.APPLICATION_JSON).content(DRAFT), put(BASE + "/car").contentType(MediaType.APPLICATION_JSON).content(DRAFT), post(BASE + "/car/publish").contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"), post(BASE + "/car/archive").contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"), multipart(BASE + "/images").file(PHOTO)); }
    @Test void anonymousCannotReachAnyAdminOperation() throws Exception {
        for (var request : routes()) mvc.perform(request).andExpect(status().isUnauthorized());
        verifyNoInteractions(service, images);
    }
    @Test void usersAndModeratorsCannotReachAnyAdminOperation() throws Exception {
        for (String role : List.of("USER", "MODERATOR")) for (var request : routes()) mvc.perform(request.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role)))).andExpect(status().isForbidden());
        verifyNoInteractions(service, images);
    }
    @Test void adminCanReachEveryOperation() throws Exception {
        for (var request : routes()) mvc.perform(request.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))).andExpect(status().is2xxSuccessful());
        verify(service).create(any(), any()); verify(service).update(any(), eq("car"), any()); verify(images).upload(any(), any());
    }
    @Test void publicReadsNeedNoSessionAndDoNotProvisionUsers() throws Exception {
        when(service.published(any(), any(), any())).thenReturn(Page.empty());
        mvc.perform(get("/v1/vehicle-models")).andExpect(status().isOk());
        mvc.perform(get("/v1/vehicle-models/car")).andExpect(status().isOk());
        verifyNoInteractions(provisioning);
        mvc.perform(post("/v1/vehicle-models")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/vehicle-models/car/private")).andExpect(status().isUnauthorized());
    }
    @Test void adminUploadReturnsTheImagePathToSave() throws Exception {
        when(images.upload(any(), any())).thenReturn(new ImageResponse(IMAGE_ID, VehicleImagePaths.uploaded(IMAGE_ID)));
        mvc.perform(multipart(BASE + "/images").file(PHOTO).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.imageUrl").value("/api/vehicle-models/images/" + IMAGE_ID));
    }
    @Test void rejectedUploadsKeepTheErrorShape() throws Exception {
        when(images.upload(any(), any())).thenThrow(PictureException.invalid("Only PNG and JPEG pictures are supported"));
        mvc.perform(multipart(BASE + "/images").file(PHOTO).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.message").value("Only PNG and JPEG pictures are supported"));
    }
    @Test void guestsCanReadUploadedPhotosWithoutProvisioning() throws Exception {
        when(images.read(IMAGE_ID)).thenReturn(new PictureValidator.Picture(new byte[]{9, 8, 7}, "image/png"));
        mvc.perform(get("/v1/vehicle-models/images/" + IMAGE_ID))
            .andExpect(status().isOk()).andExpect(content().contentType("image/png"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("immutable")));
        verifyNoInteractions(provisioning);
    }
    @Test void missingPhotoMapsTo404AndMalformedIdTo400() throws Exception {
        when(images.read(IMAGE_ID)).thenThrow(new PictureException(HttpStatus.NOT_FOUND, "Vehicle image not found"));
        mvc.perform(get("/v1/vehicle-models/images/" + IMAGE_ID)).andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Vehicle image not found"));
        mvc.perform(get("/v1/vehicle-models/images/not-a-uuid")).andExpect(status().isBadRequest());
    }
    @Test void publicPagingAndFiltersAreBounded() throws Exception {
        for (String query : List.of("size=101", "page=-1", "market=INVALID", "size=0")) mvc.perform(get("/v1/vehicle-models?" + query)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void invalidSpecsAndMissingVersionAreRejected() throws Exception {
        for (String body : List.of(DRAFT.replace("\"make\":\"BYD\"", "\"make\":\"\""), DRAFT.replace("\"connectorTypes\":[]", "\"connectorTypes\":[\"INVALID\"]"), DRAFT.replace("\"market\":\"TH\"", "\"market\":\"TH\",\"batteryCapacityKwh\":-1"))) {
            mvc.perform(post(BASE).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(post(BASE + "/car/publish").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void catalogMissingMapsTo404() throws Exception {
        when(service.publishedDetail("missing")).thenThrow(new CatalogNotFoundException());
        mvc.perform(get("/v1/vehicle-models/missing")).andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("This vehicle model was not found"));
    }
    @Test void publicationValidationAndConflictsHaveMappedResponses() throws Exception {
        when(service.transition(any(), eq("car"), eq(0L), any())).thenThrow(new BusinessRuleException("Add a dated source"));
        mvc.perform(post(BASE + "/car/publish").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}")).andExpect(status().isUnprocessableContent());
        when(service.update(any(), eq("car"), any())).thenThrow(new ObjectOptimisticLockingFailureException("VehicleModel", "car"));
        mvc.perform(put(BASE + "/car").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))).contentType(MediaType.APPLICATION_JSON).content(DRAFT)).andExpect(status().isConflict());
    }
}
