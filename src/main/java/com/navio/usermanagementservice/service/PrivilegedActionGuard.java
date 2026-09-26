package com.navio.usermanagementservice.service;

import com.navio.usermanagementservice.exception.UserManagementExceptions.ForbiddenOperationException;
import com.navio.usermanagementservice.integration.keycloak.KeycloakAdminClient;
import com.navio.usermanagementservice.repository.UserBanRepository;
import com.navio.usermanagementservice.repository.UserRepository;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import com.navio.usermanagementservice.security.NavioRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Keeps at least one administrator able to act.
 *
 * <p>An administrator can never suspend themselves or revoke their own ADMIN
 * role, and a suspended caller is rejected before reaching a handler. What
 * remains is the race: two administrators suspending or demoting each other at
 * the same instant both pass those checks, and both succeed. With two admins
 * that leaves none.
 *
 * <p>The guard closes it by locking the actor's and the target's profile rows
 * (in a fixed order, so opposite actions cannot deadlock) and then re-checking,
 * against committed state, that the actor is still an administrator in good
 * standing. The second of two crossing actions therefore waits for the first,
 * sees that its own actor was just suspended or demoted, and is refused.
 *
 * <p>Must run inside the caller's transaction, since the row locks are what
 * serialize the two actions.
 */
@Component
@RequiredArgsConstructor
public class PrivilegedActionGuard {

    private final UserRepository userRepository;
    private final UserBanRepository userBanRepository;
    private final KeycloakAdminClient keycloakAdminClient;

    @Transactional(propagation = Propagation.MANDATORY)
    public void confirmAdministratorMayAct(AuthenticatedUser actor, UUID targetUserId) {
        userRepository.lockForModeration(List.of(actor.id(), targetUserId));

        if (!userBanRepository.findActiveBans(actor.id(), Instant.now()).isEmpty()) {
            throw new ForbiddenOperationException(
                    "Your account was suspended while this action was pending");
        }
        // Read from Keycloak, not the token: the token still says ADMIN for up to
        // its lifetime after the role was revoked.
        if (!keycloakAdminClient.realmRolesOf(actor.authSubject()).contains(NavioRole.ADMIN)) {
            throw new ForbiddenOperationException(
                    "Your administrator role was removed while this action was pending");
        }
    }
}
