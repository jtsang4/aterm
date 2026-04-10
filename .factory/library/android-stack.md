# Android Stack

Current technology choices and constraints for this mission.

**What belongs here:** chosen libraries/frameworks, stack decisions, and trade-offs workers should preserve.

---

## UI

- **Kotlin + Jetpack Compose** for application UI and app shell
- Prefer unidirectional state flow with view-model/state-holder boundaries per feature

## SSH

- Use **Apache MINA SSHD** as the SSH client stack unless the orchestrator updates this decision
- Own host-key trust behavior explicitly; do not rely on implicit trust shortcuts

## Terminal Rendering

- Compose should wrap, not reimplement, the terminal surface
- Prefer a dedicated Android terminal view/emulator implementation bridged into Compose (`AndroidView` or equivalent)
- Keep terminal emulation concerns separate from SSH transport

## Persistence

- **Room** for local structured data (hosts, identities metadata, snippets, known-hosts, recents/history metadata)
- **DataStore** for lightweight preferences (theme, font size, similar UI settings)
- **Keystore-wrapped AES-GCM field encryption** for secret material

## Testing Stack

- JVM unit/integration tests for repositories, use cases, crypto wrappers, and non-UI logic
- Compose UI tests for local UI validation
- `connectedDebugAndroidTest` for end-to-end Android user flows
- Manual QA for terminal fidelity, lifecycle, and special-key interactions that instrumentation alone may miss

## Deliberate Non-Goals in This Mission

- login / account system
- cloud sync / multi-device sync
- team sharing / Vaults
- SFTP
- port forwarding
