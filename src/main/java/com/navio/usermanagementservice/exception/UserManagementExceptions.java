package com.navio.usermanagementservice.exception;

import java.util.UUID;

/**
 * Domain exceptions for the user management service.
 *
 * <p>Grouped in one file because each is a short, closely related signal to the
 * global handler; splitting them into eight near-empty files would add noise
 * without adding clarity.
 */
public final class UserManagementExceptions {

    private UserManagementExceptions() {
    }

    /** The requested Navio profile does not exist, or is soft-deleted. */
    public static class UserNotFoundException extends RuntimeException {
        public UserNotFoundException(UUID userId) {
            super("No user with id " + userId);
        }

        public UserNotFoundException(String detail) {
            super(detail);
        }
    }

    /**
     * A saved vehicle does not exist <em>for this owner</em>.
     *
     * <p>Thrown for both "no such vehicle" and "belongs to somebody else" on
     * purpose. Distinguishing the two would let a caller enumerate which vehicle
     * ids exist across the platform.
     */
    public static class VehicleNotFoundException extends RuntimeException {
        public VehicleNotFoundException(UUID vehicleId) {
            super("No vehicle with id " + vehicleId + " for this user");
        }
    }

    /** The caller's account is currently suspended. */
    public static class AccountSuspendedException extends RuntimeException {
        public AccountSuspendedException(String reason) {
            super(reason);
        }
    }

    /** The request is well-formed but violates a domain rule. */
    public static class BusinessRuleException extends RuntimeException {
        public BusinessRuleException(String message) {
            super(message);
        }
    }

    /** A moderation action cannot be applied in the target's current state. */
    public static class ModerationConflictException extends RuntimeException {
        public ModerationConflictException(String message) {
            super(message);
        }
    }

    /**
     * A privileged action was refused for a reason beyond role membership — for
     * example an admin attempting to strip their own ADMIN role.
     */
    public static class ForbiddenOperationException extends RuntimeException {
        public ForbiddenOperationException(String message) {
            super(message);
        }
    }
}
