package com.navio.usermanagementservice.controller;

import com.navio.usermanagementservice.config.*;
import com.navio.usermanagementservice.exception.UserManagementExceptions.*;
import com.navio.usermanagementservice.security.*;
import com.navio.usermanagementservice.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value=GuideDiscoveryController.class,properties={
    "spring.config.name=guide-controller-test", "spring.cloud.config.enabled=false", "spring.cloud.bus.enabled=false",
    "navio.security.keycloak.issuer-uri=https://example.com/realms/test", "navio.security.keycloak.audiences=navio-api",
    "navio.security.keycloak.admin.base-url=https://example.com", "navio.security.keycloak.admin.realm=test",
    "navio.security.keycloak.admin.client-id=test", "navio.security.keycloak.admin.client-secret=test-only"})
@Import({SecurityConfig.class,JacksonConfiguration.class,WebMvcConfig.class,CurrentUserArgumentResolver.class,RestAuthenticationEntryPoint.class,RestAccessDeniedHandler.class})
class GuideDiscoveryControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean GuideDiscoveryService service;
    @MockitoBean UserProvisioningService provisioning;
    static final String BASE="/v1/traveler-guides/discovery";
    static final UUID ID=UUID.randomUUID();
    @Test void anonymousSearchIsPublicAndValidatesBounds() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isOk());
        verify(service).discover("","","any","","","relevance,trending,recent",0,24);
        mvc.perform(get(BASE).param("size","25")).andExpect(status().isBadRequest());
        mvc.perform(get(BASE).param("page","-1")).andExpect(status().isBadRequest());
        mvc.perform(get(BASE).param("country","Thailand")).andExpect(status().isBadRequest());
        mvc.perform(get(BASE).param("term","x".repeat(201))).andExpect(status().isBadRequest());
    }
    @Test void anonymousViewRequiresUuidSessionAndDoesNotTrustForwardedIdentity() throws Exception {
        mvc.perform(post(BASE+"/CURATED/"+ID+"/view")).andExpect(status().isUnprocessableEntity());
        mvc.perform(post(BASE+"/CURATED/"+ID+"/view").contentType(MediaType.APPLICATION_JSON).content("{\"visitorId\":\"bad\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(BASE+"/CURATED/"+ID+"/view").contentType(MediaType.APPLICATION_JSON).content("{\"visitorId\":\""+ID+"\"}").header("X-User-Id",ID).header("X-Forwarded-For","fake")).andExpect(status().isNoContent());
        verify(service).recordView(eq("CURATED"),eq(ID),matches("[0-9a-f]{64}"),matches("[0-9a-f]{64}"));
    }
    @Test void authenticatedSubjectWinsOverUntrustedSessionAndPublishedFailuresReturn404() throws Exception {
        var captor=org.mockito.ArgumentCaptor.forClass(String.class);
        mvc.perform(post(BASE+"/TRAVELER/"+ID+"/view").with(jwt().jwt(j->j.subject("owner")))).andExpect(status().isNoContent());
        mvc.perform(post(BASE+"/TRAVELER/"+ID+"/view").with(jwt().jwt(j->j.subject("owner"))).contentType(MediaType.APPLICATION_JSON).content("{\"visitorId\":\""+ID+"\"}")).andExpect(status().isNoContent());
        verify(service,times(2)).recordView(eq("TRAVELER"),eq(ID),captor.capture(),anyString());
        assertThat(captor.getAllValues()).containsOnly(captor.getValue());
        doThrow(new GuideNotFoundException()).when(service).recordView(eq("CURATED"),eq(ID),anyString(),anyString());
        mvc.perform(post(BASE+"/CURATED/"+ID+"/view").with(jwt())).andExpect(status().isNotFound());
        mvc.perform(post(BASE+"/PRIVATE/"+ID+"/view").with(jwt())).andExpect(status().isBadRequest());
    }
}
