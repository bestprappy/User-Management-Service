package com.navio.usermanagementservice.service;

import com.navio.usermanagementservice.exception.UserManagementExceptions.BusinessRuleException;
import com.navio.usermanagementservice.exception.UserManagementExceptions.UsernameTakenException;
import com.navio.usermanagementservice.model.User;
import com.navio.usermanagementservice.repository.UserRepository;
import com.navio.usermanagementservice.repository.UsernameHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UsernameServiceTests {

    private static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");
    private static final UUID ME = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-4000-8000-000000000002");

    @Mock UserRepository users;
    @Mock UsernameHistoryRepository history;
    private UsernameService service;

    @BeforeEach
    void setUp() {
        service = new UsernameService(users, history, Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(users.findByUsername(anyString())).thenReturn(Optional.empty());
        lenient().when(history.heldByAnotherAccount(anyString(), any(), any())).thenReturn(false);
    }

    private User me(String username, Instant changedAt) {
        return User.builder().id(ME).displayName("Alex").username(username).usernameChangedAt(changedAt).build();
    }

    @Test
    void changingAHandleNormalizesItAndReleasesTheOldOne() {
        User user = me("alex", null);
        assertThat(service.change(user, " @Alex_Road ")).isTrue();
        assertThat(user.getUsername()).isEqualTo("alex_road");
        assertThat(user.getUsernameChangedAt()).isEqualTo(NOW);
        verify(history).recordRelease("alex", ME, NOW);
    }

    @Test
    void sendingTheCurrentHandleIsNotAChange() {
        User user = me("alex", NOW.minus(1, ChronoUnit.DAYS));
        assertThat(service.change(user, "ALEX")).isFalse();
        verify(history, never()).recordRelease(anyString(), any(), any());
    }

    @Test
    void aSecondChangeInsideTheCooldownIsRefused() {
        User user = me("alex", NOW.minus(10, ChronoUnit.DAYS));
        assertThatThrownBy(() -> service.change(user, "alex2")).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("change your username again");
        assertThat(user.getUsername()).isEqualTo("alex");
    }

    @Test
    void aHandleHeldByAnotherAccountIsTaken() {
        when(users.findByUsername("sam")).thenReturn(Optional.of(User.builder().id(OTHER).username("sam").build()));
        assertThatThrownBy(() -> service.change(me("alex", null), "sam")).isInstanceOf(UsernameTakenException.class);
    }

    @Test
    void aHandleAnotherAccountRecentlyReleasedStaysReserved() {
        when(history.heldByAnotherAccount(eq("sam"), eq(ME), eq(NOW.minus(UsernamePolicy.RELEASE_HOLD)))).thenReturn(true);
        assertThatThrownBy(() -> service.change(me("alex", null), "sam")).isInstanceOf(UsernameTakenException.class);
    }

    @Test
    void reservedAndMalformedHandlesAreRejectedWithAReason() {
        assertThatThrownBy(() -> service.change(me("alex", null), "admin")).isInstanceOf(BusinessRuleException.class)
                .hasMessage("This username is reserved");
        assertThatThrownBy(() -> service.change(me("alex", null), "9lives")).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.change(me("alex", null), "ab")).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.change(me("alex", null), "has space")).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void availabilityExplainsWhyAHandleCannotBeUsed() {
        when(users.findByUsername("sam")).thenReturn(Optional.of(User.builder().id(OTHER).username("sam").build()));
        assertThat(service.availability("sam", me("alex", null)).available()).isFalse();
        assertThat(service.availability("sam", me("alex", null)).message()).isEqualTo("This username is taken");
        assertThat(service.availability("Alex", me("alex", null)).available()).isTrue();
        assertThat(service.availability("x", me("alex", null)).message()).isEqualTo("Usernames are 3-30 characters");
    }

    @Test
    void generatedHandlesStartFromTheDisplayNameAndSkipTakenOnes() {
        when(users.findByUsername("alexmorgan")).thenReturn(Optional.of(User.builder().id(OTHER).build()));
        assertThat(service.generateFor("Alex Morgan", ME)).isEqualTo("alexmorgan2");
        assertThat(service.generateFor("999", ME)).isEqualTo("traveler999");
        assertThat(service.generateFor("Navio Team", ME)).startsWith("traveler");
    }

    @Test
    void profilesWithoutAHandleGetOneButExistingHandlesAreKept() {
        User missing = me(null, null);
        assertThat(service.ensureAssigned(missing)).isTrue();
        assertThat(missing.getUsername()).isEqualTo("alex");
        User present = me("road_alex", null);
        assertThat(service.ensureAssigned(present)).isFalse();
        assertThat(present.getUsername()).isEqualTo("road_alex");
    }
}
