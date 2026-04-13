---
name: ssh-terminal-worker
description: Build and verify SSH connection, trust, session lifecycle, terminal, and snippet-execution integration flows.
---

# ssh-terminal-worker

NOTE: Startup and cleanup are handled by `worker-base`. This skill defines the WORK PROCEDURE.

## When to Use This Skill

Use this worker for SSH transport, host-key trust, session management, terminal IO/rendering integration, disconnect/reconnect handling, PTY resize behavior, and snippet execution into real sessions.

## Required Skills

None.

## Work Procedure

1. Read `mission.md`, `AGENTS.md`, `.factory/library/architecture.md`, `.factory/library/android-stack.md`, and the assigned feature before changing code.
2. Run `./.factory/init.sh` and confirm the real SSH validation target is reachable from the emulator (`10.0.2.2:22` unless the feature explicitly sets up a different fixture).
3. Write failing tests first (red). Include the smallest credible unit/integration tests for session state plus instrumentation/manual checks for user-visible SSH or terminal behavior.
4. Implement the change without faking success states. A terminal must never look live when the SSH session is dead, and host trust must never be reused for a different endpoint silently.
5. For connection features, prove remote reality with target-specific command output through the visible shipped app shell (`MainActivity` / `AtermNavHost` navigation path), never through `setContent(...)`-mounted screen substitutes, coordinator-only state, or manual surface switching that bypasses the real nav flow. When the shipped UI exposes controls such as `session_connect_*`, `session_reconnect_*`, or `session_trust_accept`, the proof must tap those visible controls instead of asserting visibility and then calling coordinator APIs directly. For relaunch/restart validation, use a real activity/app relaunch path; in-process `AppContainer` swapping or rebuilding persistence objects inside the same Compose root is not acceptable evidence. For terminal features, do not hand off the work as complete while the UI is still transcript-backed instead of live PTY-driven; return partial progress to the orchestrator instead.
6. Run the focused validators during iteration, then the repo baseline validators plus the affected instrumentation suite before handoff.
7. Manually exercise lifecycle and terminal truthfulness whenever the feature touches connect/disconnect, resize, rotation, backgrounding, special keys, paste, or snippet dispatch to a live session. If exec-mode automation must stand in for manual interaction, it is only acceptable when the checks still drive the live SSH fixture through the real app/session surface; fake session-controller substitutes are not acceptable evidence for special keys, paste, resize, disconnect truthfulness, or representative full-screen behavior. For snippet execution/history work, only treat a run as successful when evidence ties the exact dispatched snippet payload to the intended remote session; generic transcript movement or unrelated remote output is not enough. When lifecycle proof must preserve control of the real app shell (IME, rotation, background/resume, relaunch/process-loss), prefer device-level orchestration such as UiAutomator or equivalent activity/device control instead of composable-only recreation shortcuts. Record these observations explicitly in `handoff.verification.interactiveChecks`; missing live interactive evidence is a procedure deviation.
8. Stop any emulator or SSH fixture processes you started and write a detailed handoff with explicit commands and interactive observations.

## Example Handoff

```json
{
  "salientSummary": "Implemented host-key trust + real SSH session startup, then validated reconnect/disconnect and special-key terminal behavior against the host SSH service at 10.0.2.2:22.",
  "whatWasImplemented": "Wired Apache MINA SSHD-based connection flows, added first-use trust prompts with persisted known-host decisions, surfaced auth/network failures, connected the live shell to the terminal adapter, and delivered special-key actions plus truthful disconnect state into the session UI.",
  "whatWasLeftUndone": "Recent-host rediscovery and snippet-history polish remain for later personal-polish features; the current work focuses on real session correctness and terminal truthfulness.",
  "verification": {
    "commandsRun": [
      {
        "command": "JAVA_HOME=/root/.local/share/aterm-jdk-17 ANDROID_SDK_ROOT=/root/Android/Sdk PATH=/root/.local/share/aterm-jdk-17/bin:/root/Android/Sdk/cmdline-tools/latest/bin:/root/Android/Sdk/platform-tools:/root/Android/Sdk/emulator:$PATH ./gradlew testDebugUnitTest --max-workers=4",
        "exitCode": 0,
        "observation": "SSH session-state and known-host persistence tests passed."
      },
      {
        "command": "JAVA_HOME=/root/.local/share/aterm-jdk-17 ANDROID_SDK_ROOT=/root/Android/Sdk PATH=/root/.local/share/aterm-jdk-17/bin:/root/Android/Sdk/cmdline-tools/latest/bin:/root/Android/Sdk/platform-tools:/root/Android/Sdk/emulator:$PATH ./gradlew connectedDebugAndroidTest --max-workers=1",
        "exitCode": 0,
        "observation": "Instrumentation tests covering trust prompts, real SSH connect, disconnect, and snippet dispatch passed on emulator-5554."
      }
    ],
    "interactiveChecks": [
      {
        "action": "Connected from emulator to 10.0.2.2:22, accepted the host key, ran a remote-only proof command, disconnected, and reconnected.",
        "observed": "The first run showed a fingerprint prompt, later reconnect skipped trust, and the remote-only proof command confirmed the intended target host."
      },
      {
        "action": "Used Ctrl+C, Tab completion, arrow-history navigation, and a representative full-screen terminal program in the live session.",
        "observed": "Special keys reached the remote shell correctly, full-screen redraw behavior remained coherent, and returning to the shell restored normal prompt interaction."
      }
    ]
  },
  "tests": {
    "added": [
      {
        "file": "feature/session/src/androidTest/.../RealSshSessionFlowTest.kt",
        "cases": [
          {
            "name": "trusted host prompt persists and does not repeat for unchanged endpoint",
            "verifies": "Known-host decisions are reused only for the same trusted endpoint."
          },
          {
            "name": "snippet execution in active session lands in the current transcript",
            "verifies": "Snippet dispatch reuses the live session rather than creating an unrelated one."
          }
        ]
      }
    ]
  },
  "discoveredIssues": []
}
```

## When to Return to Orchestrator

- Real SSH validation is blocked because the host target is unreachable or environment constraints changed
- Terminal rendering correctness requires a stack change that contradicts the documented architecture
- The feature needs cross-cutting product decisions about session model, trust semantics, or snippet-target semantics that are not resolved in the mission artifacts
