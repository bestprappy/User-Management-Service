-- OWNER is assigned by a trusted Keycloak operator only. The database is a
-- display snapshot and never the authority for access decisions.
ALTER TABLE iam.user_roles DROP CONSTRAINT ck_iam_user_roles_role;
ALTER TABLE iam.user_roles ADD CONSTRAINT ck_iam_user_roles_role
    CHECK (role IN ('USER', 'MODERATOR', 'ADMIN', 'OWNER'));
