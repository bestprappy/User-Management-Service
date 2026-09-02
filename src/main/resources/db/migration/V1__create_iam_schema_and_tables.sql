-- =============================================================================
--  iam schema — User Management Service
-- =============================================================================
--  Keycloak stays authoritative for credentials, sessions, account enablement,
--  and global roles. This schema stores Navio profiles, preferences, saved
--  vehicles, suspension/audit history, and a synchronized role snapshot.
--
--  Deviation from docs/database/Navio Database.md 8.5: ip_address is
--  VARCHAR(45) rather than INET. INET requires a custom Hibernate type and is
--  not portable to the H2 database used by the test slice. 45 characters holds
--  a full IPv4-mapped IPv6 address.
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS iam;

-- -----------------------------------------------------------------------------
-- 8.1 iam.users — Navio profile keyed by the Keycloak subject
-- -----------------------------------------------------------------------------
CREATE TABLE iam.users
(
    id                UUID         PRIMARY KEY,
    auth_subject      VARCHAR(128) NOT NULL,
    email             VARCHAR(255) NOT NULL,
    display_name      VARCHAR(120) NOT NULL,
    avatar_media_id   UUID,
    status            VARCHAR(30)  NOT NULL DEFAULT 'active',
    locale            VARCHAR(20),
    country_code      CHAR(2),
    preferences_jsonb JSONB        NOT NULL DEFAULT '{}'::jsonb,
    version           BIGINT       NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at        TIMESTAMPTZ,
    CONSTRAINT ck_iam_users_status CHECK (status IN ('active', 'suspended', 'deleted'))
);

-- The Keycloak subject is the join key for every authenticated request; it must
-- be unique or a token could resolve to more than one Navio profile.
CREATE UNIQUE INDEX uq_iam_users_auth_subject ON iam.users (auth_subject);

-- Case-insensitive email uniqueness prevents "User@x.com" and "user@x.com"
-- becoming two accounts that each look canonical.
CREATE UNIQUE INDEX uq_iam_users_email_lower ON iam.users (lower(email));

CREATE INDEX idx_iam_users_status ON iam.users (status);

-- -----------------------------------------------------------------------------
-- 8.2 iam.user_roles — read-only snapshot of Keycloak global roles
-- -----------------------------------------------------------------------------
-- Authorization decisions use the roles inside the validated Keycloak JWT.
-- This table exists for display and audit support only and must never be the
-- source of truth for an access check.
CREATE TABLE iam.user_roles
(
    user_id            UUID        NOT NULL,
    role               VARCHAR(30) NOT NULL,
    granted_by_user_id UUID,
    granted_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_iam_user_roles PRIMARY KEY (user_id, role),
    CONSTRAINT fk_iam_user_roles_user FOREIGN KEY (user_id)
        REFERENCES iam.users (id) ON DELETE CASCADE,
    CONSTRAINT ck_iam_user_roles_role CHECK (role IN ('USER', 'MODERATOR', 'ADMIN'))
);

-- -----------------------------------------------------------------------------
-- 8.3 iam.user_bans — suspension history, mirrored to Keycloak account state
-- -----------------------------------------------------------------------------
CREATE TABLE iam.user_bans
(
    id                UUID        PRIMARY KEY,
    user_id           UUID        NOT NULL,
    reason            TEXT        NOT NULL,
    banned_by_user_id UUID        NOT NULL,
    starts_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    ends_at           TIMESTAMPTZ,
    revoked_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_iam_user_bans_user FOREIGN KEY (user_id)
        REFERENCES iam.users (id) ON DELETE CASCADE,
    CONSTRAINT ck_iam_user_bans_window CHECK (ends_at IS NULL OR ends_at > starts_at)
);

-- Drives the per-request "is this caller suspended?" lookup, so it is indexed
-- for the active-ban predicate specifically.
CREATE INDEX idx_iam_user_bans_user_active
    ON iam.user_bans (user_id, ends_at)
    WHERE revoked_at IS NULL;

-- At most one open-ended active suspension per user keeps reactivation
-- unambiguous.
CREATE UNIQUE INDEX uq_iam_user_bans_user_active
    ON iam.user_bans (user_id)
    WHERE revoked_at IS NULL AND ends_at IS NULL;

-- -----------------------------------------------------------------------------
-- 8.4 iam.user_vehicles — reusable saved EV profiles (the user's garage)
-- -----------------------------------------------------------------------------
CREATE TABLE iam.user_vehicles
(
    id                        UUID           PRIMARY KEY,
    user_id                   UUID           NOT NULL,
    nickname                  VARCHAR(100),
    make                      VARCHAR(100)   NOT NULL,
    model                     VARCHAR(100)   NOT NULL,
    year                      SMALLINT,
    battery_capacity_kwh      NUMERIC(8, 2)  NOT NULL,
    range_km                  NUMERIC(8, 2)  NOT NULL,
    consumption_kwh_per_100km NUMERIC(8, 3),
    connector_types           TEXT[]         NOT NULL DEFAULT '{}',
    is_default                BOOLEAN        NOT NULL DEFAULT FALSE,
    metadata_jsonb            JSONB          NOT NULL DEFAULT '{}'::jsonb,
    version                   BIGINT         NOT NULL DEFAULT 0,
    created_at                TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ    NOT NULL DEFAULT now(),
    deleted_at                TIMESTAMPTZ,
    CONSTRAINT fk_iam_user_vehicles_user FOREIGN KEY (user_id)
        REFERENCES iam.users (id) ON DELETE CASCADE,
    CONSTRAINT ck_iam_user_vehicles_battery CHECK (battery_capacity_kwh > 0),
    CONSTRAINT ck_iam_user_vehicles_range CHECK (range_km > 0),
    CONSTRAINT ck_iam_user_vehicles_year CHECK (year IS NULL OR year BETWEEN 1900 AND 2200)
);

CREATE INDEX idx_iam_user_vehicles_user_deleted
    ON iam.user_vehicles (user_id, deleted_at);

-- At most one active default vehicle per user.
CREATE UNIQUE INDEX uq_iam_user_vehicles_user_default
    ON iam.user_vehicles (user_id)
    WHERE is_default = TRUE AND deleted_at IS NULL;

-- -----------------------------------------------------------------------------
-- 8.5 iam.audit_log — append-only security log
-- -----------------------------------------------------------------------------
CREATE TABLE iam.audit_log
(
    id             UUID         PRIMARY KEY,
    actor_user_id  UUID,
    action         VARCHAR(100) NOT NULL,
    resource_type  VARCHAR(50),
    resource_id    UUID,
    ip_address     VARCHAR(45),
    user_agent     TEXT,
    before_jsonb   JSONB,
    after_jsonb    JSONB,
    metadata_jsonb JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_iam_audit_log_actor_created ON iam.audit_log (actor_user_id, created_at DESC);
CREATE INDEX idx_iam_audit_log_resource ON iam.audit_log (resource_type, resource_id);
CREATE INDEX idx_iam_audit_log_action_created ON iam.audit_log (action, created_at DESC);

-- The audit trail is evidence: block in-place rewrites and deletes at the
-- database so a compromised application account cannot quietly erase its own
-- tracks. Superusers and migrations can still drop the trigger deliberately.
CREATE OR REPLACE FUNCTION iam.reject_audit_log_mutation() RETURNS TRIGGER AS
$$
BEGIN
    RAISE EXCEPTION 'iam.audit_log is append-only (attempted %)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_iam_audit_log_append_only
    BEFORE UPDATE OR DELETE
    ON iam.audit_log
    FOR EACH ROW
EXECUTE FUNCTION iam.reject_audit_log_mutation();

-- -----------------------------------------------------------------------------
-- iam.outbox — transactional event outbox for user.events.v1
-- -----------------------------------------------------------------------------
-- Events are written in the same transaction as the state change they describe,
-- then relayed to Kafka by a poller. This is what keeps "user suspended" from
-- being committed locally but lost on the wire.
CREATE TABLE iam.outbox
(
    id             UUID         PRIMARY KEY,
    aggregate_type VARCHAR(50)  NOT NULL,
    aggregate_id   UUID         NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    payload_jsonb  JSONB        NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,
    attempt_count  INTEGER      NOT NULL DEFAULT 0,
    last_error     TEXT
);

CREATE INDEX idx_iam_outbox_unpublished
    ON iam.outbox (created_at)
    WHERE published_at IS NULL;
