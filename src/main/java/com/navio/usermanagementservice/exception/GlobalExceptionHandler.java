package com.navio.usermanagementservice.exception;

import com.navio.usermanagementservice.exception.UserManagementExceptions.AccountSuspendedException;
import com.navio.usermanagementservice.exception.UserManagementExceptions.BusinessRuleException;
import com.navio.usermanagementservice.exception.UserManagementExceptions.ForbiddenOperationException;
import com.navio.usermanagementservice.exception.UserManagementExceptions.ModerationConflictException;
import com.navio.usermanagementservice.exception.UserManagementExceptions.UserNotFoundException;
import com.navio.usermanagementservice.exception.UserManagementExceptions.SavedPlaceNotFoundException;
import com.navio.usermanagementservice.exception.UserManagementExceptions.VehicleNotFoundException;
import com.navio.usermanagementservice.integration.keycloak.KeycloakAdminException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Consistent error responses for every endpoint.
 *
 * <h2>Disclosure policy</h2>
 * Handlers for expected, caller-fixable conditions return a specific message.
 * Handlers for unexpected failures return a fixed string and log the detail
 * server-side. Echoing {@code ex.getMessage()} on a 500 is how stack frames,
 * SQL fragments, and internal hostnames end up in a client's console — so it is
 * deliberately not done here.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(com.navio.usermanagementservice.media.PictureException.class)
    public ResponseEntity<ErrorResponse> handlePicture(com.navio.usermanagementservice.media.PictureException ex) {
        return build(ex.getStatus(), ex.getMessage(), null);
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handlePictureTooLarge(Exception ex) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "Picture must be at most 5 MiB", null);
    }

    @ExceptionHandler(org.springframework.web.multipart.support.MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> handleMissingPicture(Exception ex) {
        return build(HttpStatus.BAD_REQUEST, "A picture file is required", null);
    }

    // --- Not found -----------------------------------------------------------

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFound(UserNotFoundException ex) {
        log.debug("User not found: {}", ex.getMessage());
        return build(HttpStatus.NOT_FOUND, "User not found", null);
    }

    @ExceptionHandler(VehicleNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleVehicleNotFound(VehicleNotFoundException ex) {
        log.debug("Vehicle not found: {}", ex.getMessage());
        // Identical response whether the vehicle is missing or owned by another
        // user, so vehicle ids cannot be enumerated.
        return build(HttpStatus.NOT_FOUND, "Vehicle not found", null);
    }

    @ExceptionHandler(SavedPlaceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleSavedPlaceNotFound(SavedPlaceNotFoundException ex) {
        log.debug("Saved place not found: {}", ex.getMessage());
        // Identical response whether the place is missing or owned by another
        // user. These rows can hold a home address, so leaking existence would
        // be worse here than for a vehicle.
        return build(HttpStatus.NOT_FOUND, "Saved place not found", null);
    }

    // --- Authorization -------------------------------------------------------

    /**
     * A suspended account gets 403 with the moderator's stated reason.
     *
     * <p>The reason is shown because the user is already authenticated as
     * themselves and telling them why they are locked out is what lets them
     * appeal. It reveals nothing about anyone else.
     */
    @ExceptionHandler(AccountSuspendedException.class)
    public ResponseEntity<ErrorResponse> handleAccountSuspended(AccountSuspendedException ex) {
        log.info("Blocked request from a suspended account");
        return build(HttpStatus.FORBIDDEN, "Your account is suspended", ex.getMessage());
    }

    @ExceptionHandler(ForbiddenOperationException.class)
    public ResponseEntity<ErrorResponse> handleForbiddenOperation(ForbiddenOperationException ex) {
        log.warn("Refused privileged operation: {}", ex.getMessage());
        return build(HttpStatus.FORBIDDEN, ex.getMessage(), null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        // Reached when @PreAuthorize rejects a call after the filter chain let it
        // through. Logged at WARN: a burst is a privilege-escalation signal.
        log.warn("Access denied by method security: {}", ex.getMessage());
        return build(HttpStatus.FORBIDDEN, "You do not have permission to perform this action", null);
    }

    // --- Validation and malformed input --------------------------------------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String field = error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName();
            errors.put(field, error.getDefaultMessage());
        });

        return ResponseEntity.badRequest().body(ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .message("Validation failed")
                .validationErrors(errors)
                .build());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getConstraintViolations().forEach(violation ->
                errors.put(violation.getPropertyPath().toString(), violation.getMessage()));

        return ResponseEntity.badRequest().body(ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .message("Validation failed")
                .validationErrors(errors)
                .build());
    }

    @ExceptionHandler({
            ServletRequestBindingException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<ErrorResponse> handleMalformedRequest(Exception ex) {
        log.debug("Malformed request: {}", ex.getMessage());
        // The parser message can quote the raw request body, so it is not echoed.
        return build(HttpStatus.BAD_REQUEST, "Request is invalid",
                "Check the request body, path parameters and query parameters");
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ErrorResponse> handleBusinessRule(BusinessRuleException ex) {
        // UNPROCESSABLE_CONTENT is the Spring 7 name for 422; the older
        // UNPROCESSABLE_ENTITY constant is deprecated.
        return build(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage(), null);
    }

    // --- Conflicts -----------------------------------------------------------

    @ExceptionHandler(ModerationConflictException.class)
    public ResponseEntity<ErrorResponse> handleModerationConflict(ModerationConflictException ex) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), null);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        log.debug("Optimistic lock conflict", ex);
        return build(HttpStatus.CONFLICT, "This record changed while you were editing it",
                "Reload and try again");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex) {
        // Constraint names and column values must not reach the client.
        log.error("Write violated a database constraint", ex);
        return build(HttpStatus.CONFLICT, "The change conflicts with existing data", null);
    }

    // --- Upstream dependency -------------------------------------------------

    @ExceptionHandler(KeycloakAdminException.class)
    public ResponseEntity<ErrorResponse> handleKeycloakUnavailable(KeycloakAdminException ex) {
        log.error("Keycloak admin operation failed", ex);
        return build(HttpStatus.SERVICE_UNAVAILABLE,
                "The identity provider is temporarily unavailable", "Try again in a moment");
    }

    // --- Fallback ------------------------------------------------------------

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        // Fixed message: no exception text, no stack detail, nothing that
        // describes the internals to a caller.
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", null);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message, String error) {
        return ResponseEntity.status(status).body(ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(status.value())
                .message(message)
                .error(error)
                .build());
    }
}
