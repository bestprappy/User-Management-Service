package com.navio.usermanagementservice.dto;

/** Result of checking a candidate handle; {@code message} explains a refusal. */
public record UsernameAvailabilityResponse(String username, boolean available, String message) {
}
