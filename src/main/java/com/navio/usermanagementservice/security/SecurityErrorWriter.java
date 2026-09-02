package com.navio.usermanagementservice.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navio.usermanagementservice.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.time.Instant;

/**
 * Writes authentication and authorization failures using the same
 * {@link ErrorResponse} envelope as the rest of the API.
 *
 * <p>Without this, Spring Security's default 401/403 pages return an empty body
 * with a {@code WWW-Authenticate} header, so clients would need one error parser
 * for security failures and another for everything else.
 *
 * <p>Messages are deliberately generic. Telling an unauthenticated caller
 * <em>why</em> their token failed — expired, wrong audience, unknown issuer —
 * helps them tune an attack; the specific reason goes to the server log instead.
 */
public final class SecurityErrorWriter {

    private SecurityErrorWriter() {
    }

    public static void write(HttpServletResponse response,
                             ObjectMapper objectMapper,
                             HttpStatus status,
                             String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        ErrorResponse body = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(status.value())
                .message(message)
                .build();

        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
