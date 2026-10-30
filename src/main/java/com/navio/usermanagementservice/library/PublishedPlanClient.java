package com.navio.usermanagementservice.library;

import com.navio.usermanagementservice.exception.UserManagementExceptions.BusinessRuleException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/** Never reads trip tables or accepts a caller-supplied title as publication metadata. */
@Component
public class PublishedPlanClient {
    private final RestClient client;
    public PublishedPlanClient(@Value("${navio.library.trip-base-url:http://trip-planning-service:8082}") String base) {
        var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(3000);factory.setReadTimeout(5000);
        client=RestClient.builder().baseUrl(base).requestFactory(factory).build();
    }
    public LibraryContracts.PlanReference require(String token) {
        if(token==null||!token.matches("[A-Za-z0-9_-]{1,255}")) throw new BusinessRuleException("This shared plan is unavailable.");
        try {
            var result=client.get().uri("/v1/shared-plans/{token}/reference",token).retrieve().body(LibraryContracts.PlanReference.class);
            if(result==null) throw new BusinessRuleException("This shared plan is unavailable.");
            return result;
        } catch(RestClientResponseException e) {
            if(e.getStatusCode().value()==404)throw new BusinessRuleException("This shared plan is no longer available.");
            throw new BusinessRuleException("We could not check this shared plan. Please try again.");
        } catch(org.springframework.web.client.ResourceAccessException e) {
            throw new BusinessRuleException("We could not reach shared plans. Please try again.");
        }
    }
}
