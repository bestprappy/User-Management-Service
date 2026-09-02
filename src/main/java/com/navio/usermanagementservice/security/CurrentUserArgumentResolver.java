package com.navio.usermanagementservice.security;

import com.navio.usermanagementservice.service.UserProvisioningService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Resolves {@link CurrentUser} parameters from the security context.
 *
 * <p>Reads the authentication that the resource-server filter chain has already
 * validated, maps its authorities back to {@link NavioRole}, and hands both to
 * {@link UserProvisioningService} to obtain — or create — the Navio profile.
 * Suspension is enforced there, so a suspended caller never reaches a handler.
 */
@Component
@RequiredArgsConstructor
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    private final UserProvisioningService userProvisioningService;

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && AuthenticatedUser.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {

        if (!(SecurityContextHolder.getContext().getAuthentication()
                instanceof JwtAuthenticationToken authentication)) {
            // Unreachable through the configured filter chain, which denies
            // unauthenticated requests. Fail closed rather than returning null
            // and letting a handler operate with no identity.
            throw new IllegalStateException("No validated JWT is present for this request");
        }

        Jwt jwt = authentication.getToken();
        Set<NavioRole> roles = toRoles(authentication.getAuthorities());
        return userProvisioningService.resolve(jwt, roles);
    }

    private Set<NavioRole> toRoles(java.util.Collection<? extends GrantedAuthority> authorities) {
        Set<NavioRole> roles = new LinkedHashSet<>();
        for (GrantedAuthority authority : authorities) {
            String value = authority.getAuthority();
            if (value != null && value.startsWith(NavioRole.ROLE_PREFIX)) {
                NavioRole.fromClaim(value.substring(NavioRole.ROLE_PREFIX.length())).ifPresent(roles::add);
            }
        }
        return roles;
    }
}
