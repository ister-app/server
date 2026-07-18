# Users, sharing, and access

Ister does not own its user accounts — the OIDC provider does ([Introduction](00-introduction.md)).
What Ister *does* own is what each authenticated user is allowed to see and do: an **admin** flag
derived from the token, per-library **visibility**, and per-user **playback-session sharing**.

A user only becomes known to Ister after logging in at least once — the server learns that a user
exists from their first token. That is when they start appearing in the `users` and `shareableUsers`
lists.

## Admins

Admin status comes entirely from the OIDC token, not from a setting inside Ister. The JWT's `roles`
claim is mapped to Spring authorities with a `ROLE_` prefix, so a realm (or client) role named
**`admin`** becomes `ROLE_admin`. The `me` query surfaces it as `isAdmin`; the database keeps a
snapshot (`user_entity.is_admin`, refreshed on each request) but the token is authoritative.

**To make someone an admin:** in Keycloak (or your OIDC provider) create a role named `admin` and
assign it to the user, making sure it lands in the `roles` claim of the access token. No server
restart is needed — it takes effect on that user's next login.

Everything is available to any authenticated user **except** these admin-only operations (enforced
with `@PreAuthorize("hasRole('admin')")`):

- Library scanning and analysis — `scanLibrary`, `analyzeLibrary`, and the `analyzeData…` mutations
- Search maintenance — `reindexSearch`
- Podcast subscriptions — `subscribePodcast`, `unsubscribePodcast`
- Library access management — `setLibraryVisibleToAll`, `setUserLibraryAccess`
- The full `users` listing

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
found**, never a 403 — a restricted library is invisible, not merely forbidden.

## Playback-session sharing

Whether other users can *see* what you are playing (now-playing) and *control* it (remote control /
party mode) is owner-controlled, per account, with an optional per-session override. Users set this
from the client; as an operator you mainly need to know the defaults and the model.

Two independent scopes make up a user's preferences (`playbackSharingSettings`, saved with
`updatePlaybackSharingSettings`):

- **Now-playing visibility** — default **`EVERYONE`** (the original behaviour: every session is
  visible to everyone). Can be tightened to `PRIVATE` or an `ALLOWLIST` of users.
- **Remote control** — default **`PRIVATE`** (owner only). This is a deliberate tightening of the
  old "any user controls any session" party mode. Can be `EVERYONE`, an `ALLOWLIST`, or
  `SAME_AS_NOW_PLAYING` (reuse the now-playing audience).

An allowlist holds user ids; `shareableUsers` gives a normal user a name-only list to pick from
(no admin rights required). A single session can override its control scope with `setSessionSharing`
— for example opening one movie night up to everyone without changing the account default.
Enforcement is again deny-as-not-found, and the owner always passes both checks.

The internals — where the scopes are stored, how they are enforced without a database session on the
now-playing stream — are in the architecture guide,
[Continue watching and live status](../../architecture/en/05-continue-watching-and-status.md#session-sharing--privacy).
