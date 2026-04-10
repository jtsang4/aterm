# Architecture

How the system works at a high level.

**What belongs here:** product structure, major components, boundaries, data flow, and invariants workers must preserve.
**What does NOT belong here:** step-by-step implementation tasks, port commands, or low-level class inventories.

---

## Product Shape

The app is a **single-device, local-only Android SSH client**. It deliberately excludes login, cloud sync, multi-device sync, team sharing, Vaults, SFTP, and port forwarding.

The core user-facing areas are:
- host library
- identity library
- SSH session / terminal surface
- snippets
- preferences / repeat-use discovery

## Major Boundaries

### App Shell
Compose navigation and top-level screen chrome live here. This layer routes between hosts, identities, session, snippets, and settings. It should not own long-lived SSH session state directly.

### Hosts Boundary
Hosts store connection metadata such as label, hostname/address, port, username, favorite state, recents metadata, and a reference to an identity. Hosts are reusable records, not ephemeral connection forms.

### Identities Boundary
Identities are reusable authentication objects. A host points at an identity instead of owning duplicated secret material. Supported identity families in this mission are:
- password identities
- imported key identities
- generated key identities

Imported-key flows accept OpenSSH and PEM private keys.

The identity layer is responsible for distinguishing same-named identities safely in pickers and repair flows.

### Security Boundary
Secret material is stored through the approved security layer using **Keystore-wrapped encryption**. The app may persist non-secret metadata in Room/DataStore, but passwords, private keys, and passphrases must not bypass the security boundary.

Identity secret availability is modeled explicitly with `SecretStorageState` values:
- `AVAILABLE` — required secret material is present and usable
- `MISSING` — required secret material is absent
- `BLOCKED` — secret material exists but is temporarily unusable until the user repairs or re-enters it

Auth surfaces must gate on `Identity.isAuthenticationReady` rather than only checking whether an identity once had secret material.

### SSH Boundary
SSH transport, authentication, host-key trust, connection lifecycle, and channel/session ownership live behind a dedicated SSH client abstraction. This mission assumes **Apache MINA SSHD** unless the orchestrator updates the library docs.

For this mission, the authoritative session owner should live in `core/ssh` as a process-local session manager/repository rather than in a screen-level ViewModel. It may persist lightweight last-known session metadata for truthful recovery, but the live SSH connection itself remains an in-memory runtime object.

### Terminal Boundary
Terminal emulation and rendering are separate from SSH transport. Compose owns surrounding UI chrome; the terminal surface is an adapter around a dedicated terminal view/emulator implementation. The terminal layer must be able to:
- consume remote IO
- emit local keyboard/special-key input
- maintain scrollback
- reflect resize changes back to the remote PTY
- represent truthful disconnected / reconnect-needed state

### Snippets Boundary
Snippets are local saved command payloads with optional organization metadata and optional saved host association. Snippet execution may target either:
- an explicitly selected host, or
- the currently active session

Execution must never guess a stale or ambiguous target.

For this mission, snippet execution history should record a run as **successful** only after the snippet is dispatched into a still-live SSH session and the session transcript confirms the run reached the remote side. Do not wait for remote exit-code `0`; the success bar is remote dispatch into the intended live session, not business-level command success.

## Data Flow

### Host and Identity Flow
1. User creates or selects an identity.
2. User creates or edits a host that references that identity.
3. Host form may deep-link into identity create/import/generate/relink flows.
4. Saved host + identity metadata become the source of truth for connection attempts.

Host records own endpoint metadata and username. Identity records own the reusable secret or key material plus display metadata. For v1, each host links to exactly **one primary identity** and there is no fallback auth chain; recovery after auth failure happens through explicit host or identity edits.

### Connection Flow
1. User starts a connection from a saved host.
2. SSH layer resolves the endpoint, negotiates, checks known-host trust, and authenticates with the linked identity.
3. A long-lived session/channel is created if authentication succeeds.
4. The terminal boundary binds to that live session.

Known-host trust should be persisted against the actual endpoint context (`address/hostname + port`) together with the trusted host-key material. Editing a host to point somewhere else must not silently reuse trust unless the presented key matches the stored trust record for that endpoint.

### Terminal Flow
1. Local keyboard and special-key input are translated into terminal/SSH input.
2. Remote output updates terminal transcript and scrollback.
3. Lifecycle events (rotate, background, process recreation, disconnect) update visible state truthfully through the session manager.

Reconnect policy for this mission is conservative: user-initiated reconnect is supported; automatic silent reconnect is not required. After process death or unrecoverable interruption, the restored UI should default to an explicit reconnect-needed state unless a genuinely live session was preserved.

### Snippet Flow
1. User saves snippet metadata + body locally.
2. User selects execution target or uses active session.
3. Confirmation surface shows enough target/body context to prevent accidental dispatch.
4. Execution result updates snippet history only after a real successful run.

## Invariants

- No account or sync gate can appear in the critical path of local core flows.
- Hosts never silently inherit trust for a different endpoint after address/port edits.
- Secret material is never surfaced in plaintext during ordinary browsing flows.
- Disconnected or dead sessions must never look live.
- Snippet execution must never silently retarget when its saved target becomes stale or ambiguous.
- Favorites, recents, snippets, hosts, and identities all remain **local** state for this mission.

## Suggested Module Shape

A practical module split for this mission is:
- `app` — navigation, DI, top-level app shell
- `core:designsystem` — shared Compose theme, scaffolds, and reusable UI primitives
- `core/domain` — entities + use cases + repository interfaces
- `core/data` — Room/DataStore repositories and mappers
- `core/security` — Keystore + crypto wrappers
- `core/ssh` — SSH client abstraction, trust, connection/session lifecycle
- `core/terminal` — terminal adapter and state bridge
- `feature/hosts`
- `feature/identities`
- `feature/session`
- `feature/snippets`
- `feature/settings`

Repeat-use ownership should remain simple:
- host favorites/recents metadata lives with host persistence
- snippet execution history lives with snippet persistence
- theme/font preferences live with settings/preferences storage

Workers may collapse modules when necessary, but the above boundaries should remain conceptually intact.
