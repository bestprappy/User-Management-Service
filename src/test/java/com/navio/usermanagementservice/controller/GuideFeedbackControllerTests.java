package com.navio.usermanagementservice.controller;

import com.navio.usermanagementservice.config.*;
import com.navio.usermanagementservice.dto.GuideFeedback.*;
import com.navio.usermanagementservice.repository.UserRepository;
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
import java.util.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value=GuideFeedbackController.class,properties={
    "spring.config.name=guide-feedback-test","spring.cloud.config.enabled=false","spring.cloud.bus.enabled=false",
    "navio.security.keycloak.issuer-uri=https://example.com/realms/test","navio.security.keycloak.audiences=navio-api",
    "navio.security.keycloak.admin.base-url=https://example.com","navio.security.keycloak.admin.realm=test",
    "navio.security.keycloak.admin.client-id=test","navio.security.keycloak.admin.client-secret=test-only"})
@Import({SecurityConfig.class,JacksonConfiguration.class,WebMvcConfig.class,CurrentUserArgumentResolver.class,RestAuthenticationEntryPoint.class,RestAccessDeniedHandler.class})
class GuideFeedbackControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean GuideFeedbackService feedback;
    @MockitoBean UserRepository users;
    @MockitoBean UserProvisioningService provisioning;
    final UUID id=UUID.randomUUID(),user=UUID.randomUUID();
    String base(){return "/v1/traveler-guides/discovery/CURATED/"+id+"/feedback";}
    String key(){return "CURATED:"+id;}
    @BeforeEach void actor(){when(provisioning.resolve(any(),anySet())).thenReturn(new AuthenticatedUser(user,"subject","reader@example.com","Real name",Set.of(NavioRole.USER)));}
    @Test void guestsCanReadButCannotMutateOrReviewReports() throws Exception {
        mvc.perform(get(base())).andExpect(status().isOk());
        verify(feedback).summary(key(),null,false);
        mvc.perform(get(base()+"/comments")).andExpect(status().isOk());
        verify(feedback).comments(key(),null,null,0,false);
        mvc.perform(put(base()+"/rating").contentType(MediaType.APPLICATION_JSON).content("{\"score\":5}")).andExpect(status().isUnauthorized());
        mvc.perform(post(base()+"/comments").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Hi\"}")).andExpect(status().isUnauthorized());
        mvc.perform(delete(base()+"/comments/"+UUID.randomUUID())).andExpect(status().isUnauthorized());
        mvc.perform(get(base()+"/reports")).andExpect(status().isUnauthorized());
    }
    @Test void callerAndAuthorCannotBeSpoofedAndValidationRejectsInvalidPayloads() throws Exception {
        mvc.perform(post(base()+"/comments").with(jwt()).header("X-User-Id",UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"Helpful\",\"authorName\":\"Forged\"}")).andExpect(status().isCreated());
        verify(feedback).add(key(),user,new CommentRequest("Helpful","Real name",null));
        for(int score:List.of(0,6))mvc.perform(put(base()+"/rating").with(jwt()).contentType(MediaType.APPLICATION_JSON).content("{\"score\":"+score+"}")).andExpect(status().isBadRequest());
        mvc.perform(post(base()+"/comments").with(jwt()).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\" \"}")).andExpect(status().isBadRequest());
        mvc.perform(put(base()+"/comments/"+UUID.randomUUID()).with(jwt()).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Changed\"}")).andExpect(status().isBadRequest());
    }
    @Test void onlyValidatedAuthoritiesCanEnableModeration() throws Exception {
        mvc.perform(get(base()).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))).andExpect(status().isOk());
        verify(feedback).summary(key(),null,true);
        mvc.perform(get(base()).header("X-Roles","ADMIN")).andExpect(status().isOk());
        verify(feedback).summary(key(),null,false);
    }
    @Test void serviceOwnershipAndMappedFailuresArePreserved() throws Exception {
        doThrow(new GuideFeedbackService.FeedbackException(403,"Not permitted")).when(feedback).delete(eq(key()),eq(user),any(),eq(false));
        mvc.perform(delete(base()+"/comments/"+UUID.randomUUID()).with(jwt())).andExpect(status().isForbidden());
        doThrow(new GuideFeedbackService.FeedbackException(409,"Reload first")).when(feedback).edit(eq(key()),eq(user),any(),any());
        mvc.perform(put(base()+"/comments/"+UUID.randomUUID()).with(jwt()).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Changed\",\"version\":0}")).andExpect(status().isConflict());
    }
}
