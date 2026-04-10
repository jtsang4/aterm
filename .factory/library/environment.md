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
