package com.navio.usermanagementservice.repository;

import com.navio.usermanagementservice.model.OutboxEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Claims a batch of unpublished events for this relay instance.
     *
     * <p>{@code PESSIMISTIC_WRITE} with {@code SKIP LOCKED} lets several service
     * replicas drain the outbox concurrently without publishing the same event
     * twice or blocking each other.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("SELECT e FROM OutboxEvent e WHERE e.publishedAt IS NULL ORDER BY e.createdAt ASC")
    List<OutboxEvent> claimUnpublished(Pageable pageable);

    long countByPublishedAtIsNull();

    long countByPublishedAtIsNullAndCreatedAtBefore(Instant threshold);
}
