# User Testing

Testing-surface findings, setup notes, and concurrency guidance for validators.

**What belongs here:** how to exercise the real user-facing surfaces, what tools to use, setup prerequisites, and resource-cost guidance.

---

## Validation Surface

### Surface 1: JVM / local fast feedback
- Tools: `./gradlew testDebugUnitTest`, `./gradlew lintDebug`
- Purpose: repository logic, use cases, crypto wrappers, snippet/history state machines, host/identity validation rules
- Notes: run throughout implementation for fast red/green cycles

### Surface 2: Android instrumentation on emulator
- Tool: `./gradlew connectedDebugAndroidTest`
- Environment: emulator `atermApi35` on `emulator-5554`
- Real integration path: emulator reaches host SSH target at `10.0.2.2:22`
- Use this surface for host/identity CRUD, trust prompts, real SSH connection, snippet execution, theme/font persistence, and repeat-use flows

### Surface 3: Manual terminal QA on emulator/device
- Tools: manual QA plus screen capture/logs as evidence
- Required for: special-key bar behavior, full-screen terminal programs, PTY resize, orientation/background behavior, keyboard viewport checks, and truthfulness after disconnects or process recreation

### Surface 4: Fault-injection recovery checks
- Tool: targeted instrumentation or debug-only hooks used by tests
- Required for: Keystore invalidation/failure recovery paths where ordinary end-user action cannot reliably force the state on demand

## Validation Readiness

- Pre-bootstrap audit confirmed the environment has sufficient CPU/RAM/disk and `/dev/kvm`, but no Android toolchain was installed initially
- Full Android validation becomes executable only after the bootstrap/platform feature installs JDK/SDK, creates the AVD, and the app shell builds
- Immediately after bootstrap, validators should perform a dry run:
  1. run `./.factory/init.sh --check`
  2. boot emulator service
  3. assemble/install the debug app
  4. verify the app launches to the local-only shell without crash

## Validation Concurrency

### JVM / lint / unit surfaces
- Max concurrent heavy Gradle work: **4 workers**
- Rationale: 16 logical CPUs and ample RAM, but no swap; keep headroom conservative

### Emulator instrumentation surface
- Max concurrent validators: **1**
- Rationale: one emulator plus app plus Gradle instrumentation is the heaviest replicated surface; nested virtualization risk is real even though `/dev/kvm` exists

### Manual QA surface
- Max concurrent validators: **1**
- Rationale: requires exclusive control of the emulator/device session and real SSH interaction

## Evidence Expectations

- Prefer screenshots/recordings plus instrumented assertions for user-visible flows
- For SSH correctness, always include proof of remote execution from the intended target rather than only local UI state
- For repeat-use flows, capture relaunch/reopen steps to prove persistence and local-only behavior
