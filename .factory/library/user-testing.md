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
- Real integration path: emulator reaches host services via `10.0.2.2:<port>`; the system sshd on `10.0.2.2:22` is reachable but currently not automatable for app auth proof.
- Use this surface for host/identity CRUD, trust prompts, real SSH connection, snippet execution, theme/font persistence, and repeat-use flows. For authenticated SSH end-to-end proof, prefer the repo-local fixture once it exists.
- When filtering to app-only instrumentation classes, prefer `./gradlew :app:connectedDebugAndroidTest` so other modules do not try to load `:app` test classes
- For any fixture-backed session instrumentation, start `ssh_fixture` first; otherwise fixture tests can fail with `ECONNREFUSED` to `10.0.2.2:3122`
- Continuation mission note: the blocked snippets proof path should use `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.jtsang4.aterm.SnippetRealFixtureExecutionInstrumentedTest --max-workers=1` after the fixture is up, and the broader repeat-use contract including `VAL-CROSS-009` should still be validated on this emulator surface rather than a separate physical-device QA path.

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

## Flow Validator Guidance: Android instrumentation on emulator

- Use only the shared `atermApi35` emulator on `emulator-5554`; do not start another emulator or run validators in parallel on this surface.
- Treat the emulator as a single shared isolation boundary. Before running a flow, clear app state through the test setup already used by the instrumentation suites instead of inventing external seed files.
- When a test uses `createAndroidComposeRule<MainActivity>()`, remember that `MainActivity` launches before ordinary `@Before` methods run. Any reset that must happen before the activity binds its `AppContainer` or opens Room/DataStore state should live in a pre-launch rule such as `RuleChain` / `ExternalResource`, not only in `@Before`.
- For lifecycle-heavy session proofs (rotation, IME, background/resume, relaunch/process-loss), prefer device-level orchestration such as UiAutomator or equivalent activity/device control rather than composable-only recreation shortcuts when the proof must keep the real app shell foregrounded.
- Reach the host SSH target through `10.0.2.2:22` only. Do not modify host `sshd`, host keys, firewall rules, or any system SSH configuration.
- Keep evidence within the assigned mission evidence directory and avoid capturing plaintext passwords, private keys, or passphrases in logs or screenshots.
- Prefer the existing instrumentation suites for hosts/identities flows because they already encode the expected user-visible behaviors and persistence/relaunch checks for this milestone.
- If a flow needs manual app launch or package inspection, use package `io.github.jtsang4.aterm` and keep to a single emulator session at a time.

## Evidence Expectations

- Prefer screenshots/recordings plus instrumented assertions for user-visible flows
- For SSH correctness, always include proof of remote execution from the intended target rather than only local UI state
- For repeat-use flows, capture relaunch/reopen steps to prove persistence and local-only behavior

## Known Coverage Gaps

- Snippets continuation milestone real-fixture proof is now covered by `SnippetRealFixtureExecutionInstrumentedTest` on emulator `5554` against `10.0.2.2:3122` for `VAL-SNIPPET-008` through `VAL-SNIPPET-015`. Reuse the targeted methods in that suite for future reruns instead of falling back to fake-session or seeded-history-only proof.
