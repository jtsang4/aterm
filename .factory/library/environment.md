# Environment

Environment variables, external dependencies, and setup notes.

**What belongs here:** required env vars, local toolchain paths, external system dependencies, emulator host-mapping notes, and setup constraints.
**What does NOT belong here:** service ports/commands (use `.factory/services.yaml`).

---

## Required Local Tooling

- JDK 17 installed under `/root/.local/share/aterm-jdk-17`
- Android SDK root at `/root/Android/Sdk`
- Android cmdline-tools, platform-tools, emulator, build-tools, and platform packages installed by `./.factory/init.sh`
- Baseline AVD name: `atermApi35`
- Host bootstrap prerequisites present on the machine: `curl`, `tar`, `unzip`, `python3`, `yes`, and `openssl`

## Required Environment Variables

- `JAVA_HOME=/root/.local/share/aterm-jdk-17`
- `ANDROID_SDK_ROOT=/root/Android/Sdk`

Commands in `.factory/services.yaml` inline these values because each shell is isolated.

## Real SSH Target for Validation

- Host machine SSH server is expected at `127.0.0.1:22`
- From the Android emulator, the host machine is reachable at `10.0.2.2:22`
- Do not use `localhost` from inside the emulator when targeting the host SSH service

## Security / Secret Handling Notes

- No external API credentials are required for this mission
- Secret test data must remain local and disposable
- Never commit plaintext passwords, private keys, passphrases, or exported secrets

## Current Bootstrap Reality

- The repository started greenfield with no Android project
- Toolchain bootstrap is handled by `./.factory/init.sh` and the first platform feature
- Full Android validation is blocked until the project skeleton and AVD are working
- Keep `org.gradle.daemon=false` in `gradle.properties` for this environment. Gradle daemon reuse from transient temp directories caused wrapper launches to fail with `NoSuchFileException` against `/tmp/.../gradle-8.10.2/lib/...`, while single-use builds remain stable.

## SSH Validation Constraint

- The host SSH service at `127.0.0.1:22` is reachable from the emulator as `10.0.2.2:22`, but it is currently publickey-only and not automatable for Android app end-to-end auth proof with the credentials available in this mission.
- If sessions-terminal work needs true emulator-to-SSH authentication proof, implement and use a repo-local SSH fixture on an allowed helper port instead of modifying system `sshd`.

## Repo-local SSH Fixture

- The repo now provides a disposable SSH fixture service on host `127.0.0.1:3122`, reachable from the emulator as `10.0.2.2:3122`.
- Start and stop it only through the `ssh_fixture` service entry in `.factory/services.yaml`.
- Runtime state is generated under `tools/sshfixture/runtime/` and must stay uncommitted.
- The fixture writes `tools/sshfixture/runtime/fixture-metadata.env` with the current endpoint, username, password export name, client-key paths, host public key, and host fingerprint for trust-flow validation.
- Host-key identity stays stable across fixture restarts as long as the same runtime directory is preserved.

- Repo-local fixture metadata must expose only secret references or retrieval hints, never plaintext password values.
## Follow-up Mission Key-Fixture Notes

- The focused follow-up mission may generate disposable legacy PEM fixtures locally (for example `BEGIN RSA PRIVATE KEY` encrypted with `AES-128-CBC`) to validate parser and UI behavior. These fixtures must stay local-only and uncommitted.
- Prefer generating such fixtures at test time or under ignored runtime paths rather than committing static private-key samples.

