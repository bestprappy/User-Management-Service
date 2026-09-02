package com.navio.usermanagementservice.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Handles authenticated callers who lack the required role.
 *
 * <p>Denials are logged at WARN with the subject and path: a burst of these is a
 * strong privilege-escalation signal and should be alertable.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String subject = authentication != null ? authentication.getName() : "anonymous";

        log.warn("Denied {} {} for subject {}: insufficient authority",
                request.getMethod(), request.getRequestURI(), subject);

        SecurityErrorWriter.write(response, objectMapper, HttpStatus.FORBIDDEN,
                "You do not have permission to perform this action");
    }
}
