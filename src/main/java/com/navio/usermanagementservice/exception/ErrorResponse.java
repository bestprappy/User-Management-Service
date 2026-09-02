package com.navio.usermanagementservice.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Consistent error payload across every endpoint.
 *
 * <p>Matches the shape used by the trip planning service so clients can share
 * one error handler. Null fields are omitted rather than serialised as
 * {@code null}, which keeps responses tight and avoids implying that a detail
 * exists but is empty.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    private Instant timestamp;

    private int status;

    /** Safe, user-facing summary. Never contains internal detail. */
    private String message;

    /** Additional context, present only when it is safe to disclose. */
    private String error;

    private Map<String, String> validationErrors;
}
