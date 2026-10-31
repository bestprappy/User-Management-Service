package com.navio.usermanagementservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navio.usermanagementservice.model.User;
import com.navio.usermanagementservice.model.UserStatus;
import com.navio.usermanagementservice.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserProvisioningServiceTests {

    private static final String SUBJECT = "00000000-0000-4000-8000-0000000000aa";
    private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000001");

    @Mock UserRepository users;
    @Mock BanStatusService bans;
    @Mock AuditService audit;
    @Mock OutboxService outbox;
    @Mock UsernameService usernames;
    private UserProvisioningService service;

    @BeforeEach
    void setUp() {
        service = new UserProvisioningService(users, bans, audit, outbox, new ObjectMapper(), usernames);
        lenient().when(bans.activeBan(any())).thenReturn(Optional.empty());
        lenient().when(users.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static Jwt token(String name) {
        return Jwt.withTokenValue("token").header("alg", "none").subject(SUBJECT)
                .claim("email", "me@example.com").claim("name", name)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
    }

    private static User existing(String displayName, String username) {
        return User.builder().id(ID).authSubject(SUBJECT).email("me@example.com").displayName(displayName)
                .username(username).status(UserStatus.ACTIVE).build();
    }

    @Test
    void aNameEditedInNavioIsNotRevertedByTheKeycloakName() {
        User user = existing("Road Tripper", "road_tripper");
        when(users.findByAuthSubject(SUBJECT)).thenReturn(Optional.of(user));
        var caller = service.resolve(token("Keycloak Name"), Set.of());
        assertThat(caller.displayName()).isEqualTo("Road Tripper");
        assertThat(user.getDisplayName()).isEqualTo("Road Tripper");
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void aProfileWrittenByAnOlderReleaseGetsAUsernameOnItsNextRequest() {
        User user = existing("Alex", null);
        when(users.findByAuthSubject(SUBJECT)).thenReturn(Optional.of(user));
        when(usernames.ensureAssigned(user)).thenAnswer(invocation -> {
            user.setUsername("alex");
            return true;
        });
        service.resolve(token("Alex"), Set.of());
        verify(users).saveAndFlush(user);
        assertThat(user.getUsername()).isEqualTo("alex");
    }
}
