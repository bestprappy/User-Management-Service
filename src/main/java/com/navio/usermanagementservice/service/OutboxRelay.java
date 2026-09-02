package com.navio.usermanagementservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navio.usermanagementservice.model.OutboxEvent;
import com.navio.usermanagementservice.repository.OutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Publishes staged outbox rows to Kafka.
 *
 * <p>Runs on a short interval and claims rows with {@code SELECT ... FOR UPDATE
 * SKIP LOCKED}, so several replicas can drain the table concurrently without
 * publishing an event twice.
 *
 * <p>Delivery is at-least-once: a broker acknowledgement that arrives after the
 * transaction fails will be re-sent. Consumers of {@code user.events.v1} must
 * therefore be idempotent — the event id is included for de-duplication.
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "navio.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    /** Bounded so one slow cycle cannot hold locks over the whole table. */
    private static final int BATCH_SIZE = 100;

    /** After this many failures a row is left for an operator to inspect. */
    private static final int MAX_ATTEMPTS = 10;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;

    public OutboxRelay(OutboxEventRepository outboxEventRepository,
                       KafkaTemplate<String, String> kafkaTemplate,
                       ObjectMapper objectMapper,
                       @Value("${navio.outbox.topic:user.events.v1}") String topic) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    @Scheduled(fixedDelayString = "${navio.outbox.relay.interval-ms:2000}")
    @Transactional
    public void relay() {
        List<OutboxEvent> batch = outboxEventRepository.claimUnpublished(PageRequest.of(0, BATCH_SIZE));
        if (batch.isEmpty()) {
            return;
        }

        for (OutboxEvent event : batch) {
            if (event.getAttemptCount() >= MAX_ATTEMPTS) {
                // Skip rather than retry forever. countByPublishedAtIsNull is
                // exported as a metric so a stuck row raises an alert.
                continue;
            }
            publish(event);
        }
    }

    private void publish(OutboxEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(new EventEnvelope(
                    event.getId().toString(),
                    event.getEventType(),
                    event.getAggregateType(),
                    event.getAggregateId().toString(),
                    event.getCreatedAt(),
                    event.getPayload()));

            // Keyed by aggregate id so all events for one user land on the same
            // partition and stay in order — a reactivation must never be
            // consumed before the suspension it reverses.
            kafkaTemplate.send(topic, event.getAggregateId().toString(), payload).join();

            event.setPublishedAt(Instant.now());
            event.setLastError(null);
            outboxEventRepository.save(event);
        } catch (JsonProcessingException exception) {
            // Unserialisable payload will never succeed; record it and move on.
            recordFailure(event, "Payload could not be serialised: " + exception.getOriginalMessage());
        } catch (RuntimeException exception) {
            recordFailure(event, exception.getMessage());
        }
    }

    private void recordFailure(OutboxEvent event, String message) {
        event.setAttemptCount(event.getAttemptCount() + 1);
        event.setLastError(truncate(message));
        outboxEventRepository.save(event);
        log.warn("Outbox event {} ({}) failed to publish, attempt {}",
                event.getId(), event.getEventType(), event.getAttemptCount());
    }

    private String truncate(String message) {
        if (message == null) {
            return "unknown error";
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    /** Wire format for {@code user.events.v1}. */
    private record EventEnvelope(
            String eventId,
            String eventType,
            String aggregateType,
            String aggregateId,
            Instant occurredAt,
            Object data
    ) {
    }
}
