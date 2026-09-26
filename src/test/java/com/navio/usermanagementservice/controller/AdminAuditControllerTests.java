package com.navio.usermanagementservice.controller;

import com.navio.usermanagementservice.config.*;
import com.navio.usermanagementservice.security.*;
import com.navio.usermanagementservice.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = AdminAuditController.class, properties = {
    "spring.config.name=admin-audit-controller-test", "spring.cloud.config.enabled=false", "spring.cloud.bus.enabled=false",
    "navio.security.keycloak.issuer-uri=https://example.com/realms/test", "navio.security.keycloak.audiences=navio-api",
    "navio.security.keycloak.admin.base-url=https://example.com", "navio.security.keycloak.admin.realm=test",
    "navio.security.keycloak.admin.client-id=test", "navio.security.keycloak.admin.client-secret=test-only"
})
@Import({SecurityConfig.class, JacksonConfiguration.class, WebMvcConfig.class, CurrentUserArgumentResolver.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class})
class AdminAuditControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean AdminAuditService service;
    @MockitoBean UserProvisioningService provisioning;

    @BeforeEach void setUp() {
        lenient().when(provisioning.resolve(any(), anySet())).thenReturn(new AuthenticatedUser(
                UUID.randomUUID(), "admin", "admin@example.com", "Admin", Set.of(NavioRole.ADMIN)));
    }

    @Test void onlyAdministratorsCanReadTheGlobalTrail() throws Exception {
        mvc.perform(get("/v1/admin/audit-events")).andExpect(status().isUnauthorized());
        for (String role : new String[] {"USER", "MODERATOR"})
            mvc.perform(get("/v1/admin/audit-events").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role))))
                    .andExpect(status().isForbidden());
        verifyNoInteractions(service);
        when(service.search(any(), any(), any(), any(), any(), any(), any())).thenReturn(Page.empty());
        mvc.perform(get("/v1/admin/audit-events").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isArray());
    }

    @Test void invalidPagingAndFiltersAreRejectedBeforeTheService() throws Exception {
        for (String query : new String[] {"size=51", "page=-1", "actorId=someone", "from=tomorrow"})
            mvc.perform(get("/v1/admin/audit-events?" + query)
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                    .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
