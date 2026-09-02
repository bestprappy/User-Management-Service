# User Management Service — security model

## Trust boundaries

```
Browser ──TLS──► nginx ──► API Gateway ──────► user-management-service
                            │                    │
                   validates JWT           validates JWT again
                   strips X-User-*         ignores X-User-* entirely
                   injects verified id     derives identity from token
                            │                    │
                            └──────► Keycloak ◄──┘
                                (authoritative for credentials,
                                 sessions, account state, roles)
```

Two independent validations on purpose. If the gateway is bypassed — a
misconfigured network, a service port exposed, a future internal caller — this
service still refuses anything without a valid Keycloak token.

## What authenticates a caller

Only a Keycloak-issued JWT, verified locally against the realm JWKS. Checked on
every request:

| Check | Failure mode it prevents |
| --- | --- |
| Signature (JWKS) | Forged or altered tokens |
| `iss` | Tokens from another realm or a rogue issuer |
| `exp` / `nbf` (30s skew) | Replay of expired tokens |
| `aud` contains `navio-api` | A token minted for a different client in the same realm authenticating here |
| Active-ban lookup | A suspended user whose token has not yet expired |

Audience is the one Spring Security does **not** check by default. Without
`AudienceValidator`, every other client in the realm becomes a confused deputy.

## What does *not* authenticate a caller

**The `X-User-Id` header.** Other services in this system read the acting user
from it. Because it arrives from the client, that was a full authorization
bypass:

```bash
# Previously returned another user's trips:
curl -H 'X-User-Id: 11111111-1111-4111-8111-111111111111' \
     https://navio.example/v1/trips
```

Two fixes:

1. **Gateway** (`IdentityPropagationFilter`) strips `X-User-Id`, `X-User-Roles`
   and `X-User-Email` from every inbound request — including unauthenticated
   ones, so a public route cannot be used to smuggle a forged header — then
   re-injects them from the validated token.
2. **This service** never reads the header at all. Identity comes from
   `@CurrentUser`, resolved from the JWT.

The strip is unconditional and the injection requires a valid token, so a
request can only ever carry an identity the gateway proved.

## Authorization layers

1. **Filter chain** — `/v1/admin/**` requires `MODERATOR` or `ADMIN`;
   `anyRequest().authenticated()` means a new endpoint is protected by default.
2. **`@PreAuthorize`** — narrows per operation. Suspension allows moderators;
   role changes require `ADMIN`.
3. **Service rules** — the ones a role check cannot express:
   - Nobody moderates their own account.
   - A moderator cannot suspend another moderator or an admin, so one
     compromised moderator account cannot disable the people able to revoke it.
   - An admin cannot revoke their own `ADMIN` role — the classic way to lock the
     last administrator out of a realm.

Roles are read from the **validated token**, never from `iam.user_roles`. That
table is a display snapshot; treating it as an authorization source would mean a
stale row could grant access.

## Making suspension actually take effect

Disabling an account in Keycloak stops *new* tokens being issued. It does not
invalidate a token the client already holds. Three controls close that window:

1. `logoutUser` revokes all sessions and refresh tokens at suspension time.
2. `BanStatusService` re-checks ban state per request, so this service rejects a
   suspended caller immediately.
3. `accessTokenLifespan` is 300s, bounding the gap for services that only
   validate the token.

Ordering is deliberate: the local write and the Keycloak call share one
transaction. If Keycloak rejects, everything rolls back. If the commit fails
after Keycloak succeeded, the account is disabled while the local row is
unchanged — locked out, not let in.

## Data protection

- **Ownership scoping is structural.** Vehicle queries take `userId` in the
  query itself (`findByIdAndUserIdAndDeletedAtIsNull`); there is no
  fetch-then-check path to forget. The garage route is `/me/vehicles`, never
  `/{userId}/vehicles`, so there is no id for a caller to tamper with.
- **404, not 403,** for another user's vehicle, so ids cannot be enumerated.
- **`PublicUserProfileResponse` omits email**, locale, country, preferences and
  roles. `GET /v1/users/{userId}` is reachable by any authenticated caller, so
  anything in it is effectively public.
- **Mass assignment is impossible by construction** — request records simply
  have no `status`, `roles`, `email` or `id` component to bind to.
- **Preferences are a typed record, not a free-form map**, so the JSONB column
  cannot be filled with arbitrary keys.
- **Error responses disclose nothing.** The fallback handler returns a fixed
  string; exception text, stack traces and constraint names stay in the log.

## Audit trail

`iam.audit_log` is append-only, enforced twice: Hibernate `@Immutable`, and a
database trigger rejecting `UPDATE`/`DELETE`. A compromised application
credential cannot erase its own tracks.

Client IP comes from `getRemoteAddr()` after Spring's `ForwardedHeaderFilter`
(`forward-headers-strategy: framework`) — never from raw `X-Forwarded-For`
parsing, which any client can set and which would poison the record an
investigation depends on.

## Secrets

- `KEYCLOAK_ADMIN_CLIENT_SECRET` and `USER_DB_PASSWORD` have **no defaults**;
  the service fails to start rather than run with a guessable value.
- `KeycloakProperties.Admin.toString()` is overridden to mask the secret, since
  records would otherwise print it in logs and error output.
- Actuator exposes only `health`, `info`, `metrics`, `prometheus`. `env`,
  `configprops`, `beans` and `heapdump` stay closed — they would expose the
  Keycloak client secret.
- The service account holds `manage-users` + `view-users` only, never
  `realm-admin`. A leaked secret is then limited to user management rather than
  full realm control.

## Known gaps

| Gap | Impact | Suggested fix |
| --- | --- | --- |
| No rate limiting | Brute force / scraping against the API | Gateway `RequestRateLimiter` (needs Redis). Keycloak brute-force protection already covers the login endpoint. |
| Ban cache is per-instance | With multiple replicas, an eviction on one does not reach the others; the 60s TTL bounds the gap | Shared cache (Redis), or accept the TTL |
| Actuator open on the service port | Reachable by anything on the backend network | Separate `management.server.port`, or network policy |
| Keycloak admin console publicly routed | `nginx` proxies `/admin/` to Keycloak | IP-allowlist that location |
| Full-context tests disabled | No startup regression coverage | Testcontainers (Postgres + Keycloak) |
