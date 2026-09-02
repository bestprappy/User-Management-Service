package com.navio.usermanagementservice.service;

import com.navio.usermanagementservice.model.OutboxEvent;
import com.navio.usermanagementservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Stages domain events for the {@code user.events.v1} topic.
 *
 * <p>{@link Propagation#MANDATORY} enforces the point of the pattern: an event
 * may only be staged inside the transaction that performs the state change.
 * Calling this outside a transaction is a bug and fails immediately rather than
 * writing an event for a change that might not commit.
 */
@Service
@RequiredArgsConstructor
public class OutboxService {

    public static final String AGGREGATE_USER = "USER";

    public static final String EVENT_USER_SUSPENDED = "UserSuspended.v1";
    public static final String EVENT_USER_REACTIVATED = "UserReactivated.v1";
    public static final String EVENT_USER_ROLE_CHANGED = "UserRoleChanged.v1";
    public static final String EVENT_USER_PROFILE_UPDATED = "UserProfileUpdated.v1";
    public static final String EVENT_USER_PROVISIONED = "UserProvisioned.v1";

    private final OutboxEventRepository outboxEventRepository;

    /**
     * @param aggregateId the user this event concerns.
     * @param eventType   versioned event name.
     * @param payload     event body. Must carry no credentials or tokens — this
     *                    is published to a broker other services consume.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(UUID aggregateId, String eventType, Map<String, Object> payload) {
        outboxEventRepository.save(OutboxEvent.builder()
                .aggregateType(AGGREGATE_USER)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .payload(payload)
                .build());
    }
}
