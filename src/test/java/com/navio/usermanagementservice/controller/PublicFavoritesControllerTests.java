package com.navio.usermanagementservice.controller;

import com.navio.usermanagementservice.library.PublicFavoritesController;
import com.navio.usermanagementservice.library.PublicFavoritesRepository;
import org.junit.jupiter.api.Test;
import com.navio.usermanagementservice.config.*;
import com.navio.usermanagementservice.security.*;
import com.navio.usermanagementservice.service.UserProvisioningService;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;

@WebMvcTest(value = PublicFavoritesController.class, properties = {
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
class PublicFavoritesControllerTests {
 @Autowired MockMvc mvc;
 @MockitoBean JwtDecoder decoder;
 @MockitoBean UserProvisioningService provisioning;
 @MockitoBean PublicFavoritesRepository repository;
 @Test void anonymousReaderGetsOnlyAggregateCounts() throws Exception {
  when(repository.counts(List.of("plan:public-token"))).thenReturn(List.of(new PublicFavoritesRepository.Count("plan:public-token",3)));
  mvc.perform(get("/v1/saved-content/favorites").param("identity","plan:public-token"))
   .andExpect(status().isOk()).andExpect(jsonPath("$[0].count").value(3)).andExpect(jsonPath("$[0].userId").doesNotExist());
 }
 @Test void rejectsPrivatePlaceAndMalformedIdentitiesAndOversizedBatches() throws Exception {
  for(String identity:List.of("google:secret-place","guide:curated:bad-id","plan:../private",""))
   mvc.perform(get("/v1/saved-content/favorites").param("identity",identity)).andExpect(status().isBadRequest());
  mvc.perform(get("/v1/saved-content/favorites").param("identity",Collections.nCopies(49,"plan:public-token").toArray(String[]::new))).andExpect(status().isBadRequest());
  verifyNoInteractions(repository);
 }
 @Test void noAnonymousOrAuthenticatedMutationRouteExists() throws Exception {
  mvc.perform(post("/v1/saved-content/favorites")).andExpect(status().isUnauthorized());
  mvc.perform(post("/v1/saved-content/favorites").with(jwt())).andExpect(status().isMethodNotAllowed());
 }
}
