---
description: "How Ister handles users and access: the mandatory OIDC 'user' role, admin roles, per-library visibility for restricted libraries, and owner-controlled playback-session sharing."
---

# Users, sharing, and access

Ister does not own its user accounts — the OIDC provider does ([Introduction](00-introduction.md)).
What Ister *does* own is what each authenticated user is allowed to see and do: an **admin** flag
derived from the token, per-library **visibility**, and per-user **playback-session sharing**.

A user only becomes known to Ister after logging in at least once — the server learns that a user
exists from their first token. That is when they start appearing in the `users` and `shareableUsers`
lists.

## The `user` role is mandatory

Every GraphQL and REST operation is annotated `@PreAuthorize("hasRole('user')")` — a valid JWT
alone is not enough. So when you set up your OIDC provider, create **two** roles and make sure both
land in the token's `roles` claim:

1. a role named **`user`**, assigned to *everyone* who may use the server, and
2. a role named **`admin`** for administrators (see below).

Without the `user` role the login itself succeeds but every query and mutation is rejected — a
confusing failure mode, because the client appears authenticated yet shows no data. The bundled dev
realm (`keycloak/Ister-realm.json`) already assigns both roles to its test user.

## Admins

Admin status comes entirely from the OIDC token, not from a setting inside Ister. The JWT's `roles`
claim is mapped to Spring authorities with a `ROLE_` prefix, so a realm (or client) role named
**`admin`** becomes `ROLE_admin`. The `me` query surfaces it as `isAdmin`; the database keeps a
snapshot (`user_entity.admin`, refreshed on each request) but the token is authoritative. The
snapshot exists for the requests that carry no JWT at all: HLS segments, playlists, and images can
authenticate with a short-lived stream token (`StreamTokenAuthenticationFilter`), and on that path
the admin bypass for library access falls back to the snapshot on the user row.

**To make someone an admin:** in Keycloak (or your OIDC provider) create a role named `admin` and
assign it to the user, making sure it lands in the `roles` claim of the access token. No server
restart is needed — it takes effect on that user's next login.

Everything is available to any user with the `user` role **except** these admin-only operations
(enforced with `@PreAuthorize("hasRole('admin')")`):

- Library scanning and metadata refresh — `scanLibraries`, `refreshMetadata`, and the per-item
  `refreshEpisode`, `refreshMovie`, `refreshShow`, `refreshPerson`, `refreshAlbum`, `refreshTrack`
- Search maintenance — `rebuildSearchIndex`
- Podcast subscriptions — `subscribePodcast`, `unsubscribePodcast`
- Library access management — `setLibraryVisibleToAll`, `setUserLibraryAccess`
- The full `users` listing and the `User.grantedLibraries` field on it

Note that `refreshPodcasts` (re-fetch all subscribed feeds) is deliberately **not** admin-only —
any user can trigger it.

A few endpoints are intentionally unauthenticated (`OIDCSecurityConfig`): `/actuator/**` (keep the
management port internal, see [Configuration](03-configuration.md)) and `/time`, the clock probe
that listen-along clients use to measure their offset — it must stay auth-free so the round-trip
measurement is as light as possible.

## Library visibility

Each library is either **visible to all** or **restricted**:

- New and existing libraries default to **visible to all** (`library_entity.visible_to_all`,
  migration V27), so installs from before this feature keep working unchanged.
- `setLibraryVisibleToAll(libraryId, visibleToAll)` (admin) flips a library between the two states.
- For a restricted library, `setUserLibraryAccess(userId, libraryId, granted)` (admin) grants or
  revokes one user's access. It is idempotent — granting twice, or revoking a grant that does not
  exist, is fine.

**Admins always see every library**, restricted or not. A non-admin sees the visible-to-all
libraries plus the restricted ones explicitly granted to them. Access is enforced everywhere media
is served (`LibraryAccessService`, `MediaAccessEnforcementFilter`), and a denial reads as **not
found**, never a 403 — a restricted library is invisible, not merely forbidden. The same
deny-as-not-found rule applies everywhere access is checked in Ister, including the
playback-session sharing below.

## Personal data is per-user

Playlists (manual and smart), saved views, registered devices, ratings, and playback history are
owned by the user who created them: another user cannot read or modify them, and here too a denial
reads as not found. There is nothing to administer — see
[Personal library and devices](../../architecture/en/09-personal-library-and-devices.md) in the
architecture guide for the model.

## Playback-session sharing

Whether other users can *see* what you are playing (now-playing) and *control* it (remote control /
party mode) is owner-controlled, per account, with an optional per-session override. Users set this
from the client; as an operator you mainly need to know the defaults and the model.

Two independent scopes make up a user's preferences (`playbackSharingSettings`, saved with
`updatePlaybackSharingSettings`; stored by migration V28, `user_sharing_settings`):

- **Now-playing visibility** — default **`EVERYONE`** (the original behaviour: every session is
  visible to everyone). Can be tightened to `PRIVATE` or an `ALLOWLIST` of users.
- **Remote control** — default **`PRIVATE`** (owner only). This is a deliberate tightening of the
  old "any user controls any session" party mode. Can be `EVERYONE`, an `ALLOWLIST`, or
  `SAME_AS_NOW_PLAYING` (reuse the now-playing audience).

An allowlist holds user ids; `shareableUsers` gives a normal user a name-only list to pick from
(no admin rights required). A single session can override its control scope with `setSessionSharing`
— for example opening one movie night up to everyone without changing the account default.
Enforcement follows the deny-as-not-found rule above, and the owner always passes both checks.

The internals — where the scopes are stored, how they are enforced without a database session on the
now-playing stream — are in the architecture guide,
[Continue watching and live status](../../architecture/en/05-continue-watching-and-status.md#session-sharing--privacy).
