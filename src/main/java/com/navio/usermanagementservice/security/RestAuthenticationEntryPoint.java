package com.navio.usermanagementservice.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** Handles requests that arrive without a usable token. */
@Component
@RequiredArgsConstructor
@Slf4j
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        // The precise cause (expired, bad signature, wrong audience) is logged
        // for operators but never returned to the caller.
        log.debug("Rejected unauthenticated request to {}: {}",
                request.getRequestURI(), authException.getMessage());

        SecurityErrorWriter.write(response, objectMapper, HttpStatus.UNAUTHORIZED,
                "Authentication is required");
    }
}
